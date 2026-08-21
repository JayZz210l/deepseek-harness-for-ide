package com.deepseek.dsh.ide.process

import com.deepseek.dsh.ide.i18n.DshBundle
import com.deepseek.dsh.ide.settings.DshSettingsState
import com.deepseek.dsh.ide.stats.DshUsageStats
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.Duration
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Per-project owner of the local `dsh web` server process.
 *
 * Lifecycle operations are serialized on a single-thread executor, so the manager is safe to
 * call from any thread. Status changes are published on the project message bus via
 * [DshServerTopics.SERVER_STATUS] (always on the EDT).
 *
 * The manager stops the spawned process when the project is closed ([dispose]).
 */
class DshProcessManager(private val project: Project) : Disposable {

    private val log = thisLogger()

    private val lifecycle: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "DeepSeekHarness-Process").apply { isDaemon = true }
    }

    private val logLines = Collections.synchronizedList(LinkedList<String>())

    private val nodeResolver = NodeExecutableResolver()

    @Volatile
    private var currentStatus: DshServerStatus = DshServerStatus()

    @Volatile
    private var currentProcess: Process? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var runningSinceNanos = 0L

    private var proxy: DshApiProxy? = null

    private var ideBridge: DshIdeBridge? = null

    /** Guards the one-time native→proxy fallback retry inside [startInternal]. */
    private var nativeRetryDepth = 0

    /** Guards the plugin-sync entry point against duplicate clicks while one sync is queued/running. */
    @Volatile
    private var pluginSyncQueued = false

    /** Guards the agent-preset sync entry point against duplicate clicks. */
    @Volatile
    private var presetSyncQueued = false

    /** Guards the restore-default-plugins entry point against duplicate clicks. */
    @Volatile
    private var pluginResetQueued = false

    private val disposed = AtomicBoolean(false)

    /** Latest published status; safe to read from any thread. */
    fun currentStatus(): DshServerStatus = currentStatus

    /** Current uptime of the running server in milliseconds; 0 when not running. */
    fun currentUptimeMs(): Long {
        val since = runningSinceNanos
        if (since == 0L) return 0L
        return (System.nanoTime() - since) / 1_000_000L
    }

    /** Rolling snapshot of the captured stdout/stderr lines. */
    fun snapshotLog(): List<String> = synchronized(logLines) { ArrayList(logLines) }

    /** Idempotent async start; does nothing when a process is already running or starting. */
    fun startAsync() {
        if (disposed.get()) return
        lifecycle.execute { startInternal() }
    }

    fun stopAsync() {
        if (disposed.get()) return
        lifecycle.execute { stopInternal() }
    }

    fun restartAsync() {
        if (disposed.get()) return
        lifecycle.execute {
            stopInternal()
            startInternal()
        }
    }

    /**
     * Idempotent async one-way sync of the web-profile plugin manifests from the
     * user's main DSH home into this project's isolated home (see [DshPluginSync]).
     * A running server is stopped first and restarted afterwards, because profile
     * files must not be rewritten under a live `dsh web` instance.
     */
    fun syncPluginsFromMainHomeAsync() {
        if (disposed.get() || pluginSyncQueued) return
        pluginSyncQueued = true
        lifecycle.execute { syncPluginsInternal() }
    }

    /**
     * Idempotent async one-way sync of locally authored agent presets
     * (`<main home>/.agent-presets`) into this project's isolated home. No
     * service restart is needed: DSH re-reads preset roots on every request.
     */
    fun syncAgentPresetsFromMainHomeAsync() {
        if (disposed.get() || presetSyncQueued) return
        presetSyncQueued = true
        lifecycle.execute { syncAgentPresetsInternal() }
    }

    /**
     * Idempotent async removal of the synced plugin profile, restoring DSH's
     * shipped default web profile. A running server is stopped and restarted;
     * when the restart fails, the previous synced profile is restored.
     */
    fun resetPluginsToDefaultAsync() {
        if (disposed.get() || pluginResetQueued) return
        pluginResetQueued = true
        lifecycle.execute { resetPluginsInternal() }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        lifecycle.execute {
            killCurrentProcess()
            stopProxy()
            stopBridge()
            currentProcess = null
            recordStop()
            publish(DshServerStatus(DshServerState.STOPPED, detail = DshBundle.message("dsh.proc.projectClosed")))
        }
        lifecycle.shutdown()
        lifecycle.awaitTermination(15, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle internals (always on the lifecycle thread)
    // ---------------------------------------------------------------------------------------------

    private fun startInternal() {
        val process = currentProcess
        if (process != null && process.isAlive) return
        stopRequested = false
        stopBridge()
        stopProxy()

        // Clear first so every diagnostic line below (command resolution, native-mode
        // preparation, spawn) survives into the visible log.
        synchronized(logLines) { logLines.clear() }

        val settings = DshSettingsState.getInstance().current
        publish(DshServerStatus(DshServerState.STARTING))

        val command = settings.dshCommand.trim()
        if (command.isEmpty()) {
            publish(DshServerStatus(DshServerState.FAILED, detail = DshBundle.message("dsh.proc.noCommand")))
            return
        }

        // Fast, actionable failure instead of a cryptic spawn error: the bundled dsh
        // runtime (and every dsh shim) needs Node.js. The inherited PATH, refreshed
        // Windows environment and optional bundled node-runtime are considered; an explicit path
        // (e.g. a full node.exe path inside the command) skips this. When Node.js is
        // missing, the status card explains it and a balloon with a one-click download
        // link points the user at nodejs.org.
        val firstToken = parseCommandTokens(command).firstOrNull() ?: ""
        val nodeResolution = resolveNode()
        if (nodeResolution.executable == null && DshBundledRuntime.binJs() != null && !looksLikeExplicitPath(firstToken)) {
            val unsupportedVersion = nodeResolution.unsupportedVersion
            val detail = if (unsupportedVersion != null) {
                DshBundle.message(
                    "dsh.proc.nodeTooOld",
                    unsupportedVersion,
                    nodeResolution.unsupportedExecutable ?: "?",
                )
            } else {
                DshBundle.message("dsh.proc.noNode")
            }
            publish(DshServerStatus(DshServerState.FAILED, detail = detail))
            notifyNoNode(unsupportedVersion)
            return
        }

        val baseTokens = buildTokens(settings, nodeResolution.executable)
        val mode = settings.fileJumpMode.ifBlank { "auto" }

        // ── Native (composition patch) preparation ────────────────────────────────────────────────
        var nativeActive = false
        var includeSettingsRow = false
        val finalTokens = baseTokens.toMutableList()
        if (mode == "auto") {
            val implRoot = implRootFromTokens(baseTokens) ?: findNpxCachedBinJs()?.let(::implRootOf)
            // The "For IDE" settings-page row is shipped inside the BUNDLED runtime's
            // node_modules; it is only added to the patch when the bundled runtime is the
            // one being launched (an external npx-cache install has no such package).
            val bundledRoot = DshBundledRuntime.installRoot()?.toString()
            includeSettingsRow = implRoot != null && bundledRoot != null && runCatching {
                File(implRoot).canonicalPath.equals(File(bundledRoot).canonicalPath, ignoreCase = SystemInfo.isWindows)
            }.getOrDefault(false)
            val patchFile = if (implRoot != null) DshNativeSupport.writeBridgeFiles(includeSettingsRow) else null
            if (implRoot != null && patchFile != null) {
                val bridge = DshIdeBridge { path -> openPathInIde(path) }
                runCatching { bridge.start() }
                if (bridge.baseUrl.isNotEmpty()) {
                    ideBridge = bridge
                    // The launcher rejects parent-level --patch before the `web`
                    // subcommand; the web subcommand owns its own --patch option, so it
                    // must come AFTER `web` (baseTokens = [cmd, web, --host, ..., --port, ...]).
                    finalTokens.add(3, "--patch")
                    finalTokens.add(4, patchFile.toString())
                    nativeActive = true
                    addLog(DshBundle.message("dsh.proc.nativeActive", patchFile, bridge.baseUrl))
                }
            }
            if (!nativeActive) {
                addLog(DshBundle.message("dsh.proc.nativeUnavailable", "no resolvable dsh installation"))
            }
        }

        val pb = ProcessBuilder(platformCommand(finalTokens))
        addNodeToPath(pb, nodeResolution.executable)
        project.basePath?.let { base ->
            runCatching { pb.directory(File(base)) }
        }
        // Isolate the plugin instance's DSH home by default: a second `dsh web` on the
        // user's main ~/.dsh is not multi-instance safe and previously interfered with
        // the standalone web UI (mixed workspaces, orphaned sessions, re-bootstrapped
        // config). Blank now resolves to a per-project home seeded one-way from the
        // main home; "default" keeps the old inherit-the-environment behavior.
        val dshHome = DshHomePolicy.resolveHome(settings.dshHomeOverride, project.basePath)
        if (dshHome != null) {
            DshHomePolicy.seedHome(Paths.get(dshHome), ::addLog)
            pb.environment()["DSH_HOME"] = dshHome
            addLog(DshBundle.message("dsh.proc.home", dshHome))
        }
        if (nativeActive) {
            val bridge = ideBridge ?: return
            val implRootEnv = implRootFromTokens(baseTokens) ?: findNpxCachedBinJs()?.let(::implRootOf) ?: ""
            pb.environment()["DSH_IDE_BRIDGE_IMPL"] = implRootEnv
            pb.environment()["DSH_IDE_BRIDGE_URL"] = bridge.baseUrl
            pb.environment()["DSH_IDE_BRIDGE_TOKEN"] = bridge.token
            // Link the "For IDE" settings package into the DSH profile's node_modules
            // before boot: the client-module scanner resolves row names against the
            // profile directory, so the junction must exist when dsh starts.
            if (includeSettingsRow && implRootEnv.isNotEmpty()) {
                val linkHome = dshHome ?: DshHomePolicy.mainHome().toString()
                DshNativeSupport.ensureClientSettingsLink(implRootEnv, linkHome, ::addLog)
            }
        }

        addLog(DshBundle.message("dsh.proc.logStarted", finalTokens.joinToString(" ")))

        val spawned: Process
        try {
            spawned = pb.start()
        } catch (e: IOException) {
            addLog("启动失败: ${e.message}")
            publish(
                DshServerStatus(
                    DshServerState.FAILED,
                    detail = DshBundle.message("dsh.proc.spawnFailed", e.message ?: e.javaClass.simpleName)
                )
            )
            return
        }
        currentProcess = spawned
        addLog(DshBundle.message("dsh.proc.logPid", spawned.pid()))

        val outputLines = LinkedBlockingQueue<String>()
        pump(spawned.inputStream, "out", outputLines)
        pump(spawned.errorStream, "err", outputLines)

        val url = awaitUrl(spawned, outputLines)
        if (url == null) {
            currentProcess = null
            if (stopRequested || disposed.get()) return
            // Native attempt died before serving: retry once without the patch, via the proxy.
            if (nativeActive && mode == "auto" && nativeRetryDepth == 0) {
                nativeRetryDepth++
                stopBridge()
                addLog(DshBundle.message("dsh.proc.nativeFailed", exitDescription(spawned)))
                startInternal()
                return
            }
            val exit = exitDescription(spawned)
            publish(
                DshServerStatus(
                    DshServerState.FAILED,
                    detail = DshBundle.message("dsh.proc.exitedEarly", exit)
                )
            )
            return
        }
        nativeRetryDepth = 0

        // The `dsh web:` URL line is printed only after the Loader settles, so the server is
        // already listening at this point. Without the native gateway, start the file-open
        // proxy in front of it: the embedded browser loads the proxy URL, everything is
        // forwarded byte-for-byte and `POST /api/host.openPath` is answered locally.
        val useProxyFallback = mode == "proxy" || (mode == "auto" && !nativeActive)
        val realPort = url.substringAfterLast(':').toIntOrNull() ?: 0
        var browserUrl = url
        if (useProxyFallback && realPort > 0) {
            val newProxy = DshApiProxy { path -> openPathInIde(path) }
            val proxyPort = runCatching { newProxy.start(realPort) }.getOrNull()
            if (proxyPort != null) {
                proxy = newProxy
                browserUrl = "http://127.0.0.1:$proxyPort"
                addLog(DshBundle.message("dsh.proc.proxy", browserUrl, url))
            } else {
                addLog(DshBundle.message("dsh.proc.proxyFailed", url))
            }
        }

        publish(DshServerStatus(DshServerState.STARTING, url = browserUrl, realUrl = url))
        addLog(DshBundle.message("dsh.proc.ready", url))

        // Deterministic default workspace, BEFORE the browser starts loading: the web
        // app runs its one-shot initial workspace selection against its FIRST baseline
        // pull, so a workspace that appears later is never auto-selected (the user is
        // stuck on the empty "choose workspace" hero). The browser only loads after
        // RUNNING is published below, so doing this synchronously on the lifecycle
        // thread with short timeouts removes the race; failures only log.
        ensureProjectWorkspace(url)

        DshUsageStats.getInstance().recordStart()
        runningSinceNanos = System.nanoTime()
        currentStatus = DshServerStatus(DshServerState.RUNNING, url = browserUrl, realUrl = url, pid = spawned.pid())
        publish(currentStatus)

        // Warn early when this instance has no DeepSeek API key: otherwise the first
        // prompt dies with a raw "llm-deepseek: no API key" error in the process log
        // while the embedded UI never updates (stuck "session-<id>" title, no answer).
        // After the environment checks, show the update announcement once per version.
        // DshApiClient talks to the real dsh port directly, so `url` is the target.
        ApplicationManager.getApplication().executeOnPooledThread {
            preflightCredentials(url)
            showUpdateNotice()
        }

        // Watch the process; unexpected exits surface as FAILED (or trigger auto-restart).
        spawned.onExit().thenApply { exit ->
            lifecycle.execute { onProcessExit(spawned, exit.exitValue()) }
            null
        }
    }

    /** Extracts the dsh installation root from a resolved `.../lib/bin.js` token, if present. */
    private fun implRootFromTokens(tokens: List<String>): String? =
        tokens.firstOrNull { it.replace('\\', '/').endsWith("/lib/bin.js") }?.let(::implRootOf)

    private fun implRootOf(binJs: String): String? {
        val normalized = binJs.replace('\\', '/')
        val marker = "/node_modules/@deepseek-ai/dsh/lib/bin.js"
        return if (normalized.endsWith(marker)) normalized.substringBefore(marker) else null
    }

    private fun onProcessExit(process: Process, exitCode: Int) {
        if (disposed.get()) return
        if (currentProcess !== process) return // a newer run replaced this one
        currentProcess = null
        if (stopRequested) return // stopInternal already published STOPPED

        stopProxy()
        stopBridge()
        recordStop()
        DshUsageStats.getInstance().recordCrash()
        addLog(DshBundle.message("dsh.proc.logExit", exitCode))
        val settings = DshSettingsState.getInstance().current
        if (settings.autoRestartOnExit) {
            addLog(DshBundle.message("dsh.proc.autoRestart"))
            lifecycle.schedule({ startInternal() }, 2, TimeUnit.SECONDS)
        } else {
            publish(
                DshServerStatus(
                    DshServerState.FAILED,
                    detail = DshBundle.message("dsh.proc.unexpectedExit", exitCode)
                )
            )
        }
    }

    private fun stopInternal() {
        val process = currentProcess
        if (process == null) {
            stopProxy()
            stopBridge()
            publish(DshServerStatus(DshServerState.STOPPED))
            return
        }
        stopRequested = true
        publish(DshServerStatus(DshServerState.STOPPING))
        killCurrentProcess()
        stopProxy()
        stopBridge()
        currentProcess = null
        recordStop()
        publish(DshServerStatus(DshServerState.STOPPED))
    }

    private fun recordStop() {
        if (runningSinceNanos != 0L) {
            val uptimeMs = (System.nanoTime() - runningSinceNanos) / 1_000_000L
            DshUsageStats.getInstance().recordStop(uptimeMs)
            runningSinceNanos = 0L
        }
    }

    private fun stopProxy() {
        proxy?.stop()
        proxy = null
    }

    private fun stopBridge() {
        ideBridge?.stop()
        ideBridge = null
    }

    private fun killCurrentProcess() {
        val process = currentProcess ?: return
        try {
            if (SystemInfo.isWindows) {
                // Kill the whole tree; the dsh launcher may have spawned child node processes.
                runCatching {
                    ProcessBuilder("taskkill", "/PID", process.pid().toString(), "/T", "/F")
                        .start()
                        .waitFor(10, TimeUnit.SECONDS)
                }
            }
            runCatching { process.destroy() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                runCatching { process.destroyForcibly() }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Plugin sync from the main DSH home (always on the lifecycle thread)
    // ---------------------------------------------------------------------------------------------

    private fun syncPluginsInternal() {
        val syncLog = mutableListOf<String>()
        try {
            val settings = DshSettingsState.getInstance().current
            val targetHome = DshHomePolicy.resolveHome(settings.dshHomeOverride, project.basePath)
            val mainHome = DshHomePolicy.mainHome()
            if (targetHome == null || sameDirectoryPath(targetHome, mainHome.toString())) {
                notify(DshBundle.message("dsh.notify.syncPlugins.sameHome"), NotificationType.INFORMATION)
                return
            }

            if (!DshPluginSync.hasPlugins(mainHome)) {
                notify(DshBundle.message("dsh.notify.syncPlugins.nothing", mainHome), NotificationType.INFORMATION)
                return
            }

            // Reassure the user BEFORE the embedded page disappears: the stop +
            // pnpm install + restart sequence looks like a crash without context.
            notify(DshBundle.message("dsh.notify.syncPlugins.started"), NotificationType.INFORMATION)

            val wasRunning = currentStatus.state == DshServerState.RUNNING
            if (wasRunning) stopInternal()

            // SYNCING keeps the status card visible with a "this is normal" hint
            // and disables the start/stop/restart toolbar actions until the sync
            // (or the automatic restart) settles.
            publish(DshServerStatus(DshServerState.SYNCING, detail = DshBundle.message("dsh.status.syncing.detail")))

            // Lines are appended to the rolling log LIVE (install output arrives on
            // its own pump thread), so an open Log tab shows progress while waiting.
            val result = DshPluginSync.sync(mainHome, Paths.get(targetHome)) { line ->
                syncLog += line
                addLog(line)
            }
            when {
                result.error == DshPluginSync.ERROR_PNPM_NOT_FOUND -> {
                    notifyNoPnpm()
                }
                result.error != null -> {
                    notify(
                        DshBundle.message("dsh.notify.syncPlugins.failed", result.error),
                        NotificationType.ERROR,
                    )
                }
                result.changed -> {
                    notify(DshBundle.message("dsh.notify.syncPlugins.done"), NotificationType.INFORMATION)
                }
                else -> {
                    notify(DshBundle.message("dsh.notify.syncPlugins.upToDate"), NotificationType.INFORMATION)
                }
            }

            if (wasRunning) {
                startInternal()
            } else {
                publish(DshServerStatus(DshServerState.STOPPED))
            }
        } finally {
            // startInternal clears the rolling log at its beginning, so the sync
            // transcript is appended again afterwards to survive the restart.
            syncLog.forEach(::addLog)
            pluginSyncQueued = false
        }
    }

    /** Error balloon with a one-click action opening the pnpm installation page. */
    private fun notifyNoPnpm() {
        ApplicationManager.getApplication().invokeLater {
            if (disposed.get() || project.isDisposed) return@invokeLater
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("DeepSeekHarness")
                .createNotification(DshBundle.message("dsh.notify.syncPlugins.pnpmMissing"), NotificationType.ERROR)
            notification.addAction(
                NotificationAction.createSimpleExpiring(
                    DshBundle.message("dsh.notify.syncPlugins.pnpmInstall"),
                ) {
                    runCatching { BrowserUtil.browse("https://pnpm.io/installation") }
                },
            )
            notification.notify(project)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Agent-preset sync from the main DSH home (always on the lifecycle thread)
    // ---------------------------------------------------------------------------------------------

    private fun syncAgentPresetsInternal() {
        val syncLog = mutableListOf<String>()
        try {
            val settings = DshSettingsState.getInstance().current
            val targetHome = DshHomePolicy.resolveHome(settings.dshHomeOverride, project.basePath)
            val mainHome = DshHomePolicy.mainHome()
            if (targetHome == null || sameDirectoryPath(targetHome, mainHome.toString())) {
                notify(DshBundle.message("dsh.notify.syncPlugins.sameHome"), NotificationType.INFORMATION)
                return
            }

            if (!DshPresetSync.hasPresets(mainHome)) {
                notify(DshBundle.message("dsh.notify.syncPresets.nothing", mainHome), NotificationType.INFORMATION)
                return
            }

            val result = DshPresetSync.sync(mainHome, Paths.get(targetHome)) { line ->
                syncLog += line
                addLog(line)
            }
            when {
                result.error != null -> {
                    notify(DshBundle.message("dsh.notify.syncPresets.failed", result.error), NotificationType.ERROR)
                }
                result.changed -> {
                    notify(DshBundle.message("dsh.notify.syncPresets.done", mainHome), NotificationType.INFORMATION)
                }
                else -> {
                    notify(DshBundle.message("dsh.notify.syncPresets.upToDate"), NotificationType.INFORMATION)
                }
            }
        } finally {
            syncLog.forEach(::addLog)
            presetSyncQueued = false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Restore-default-plugins (always on the lifecycle thread)
    // ---------------------------------------------------------------------------------------------

    private fun resetPluginsInternal() {
        try {
            val settings = DshSettingsState.getInstance().current
            val targetHome = DshHomePolicy.resolveHome(settings.dshHomeOverride, project.basePath)
            val mainHome = DshHomePolicy.mainHome()
            if (targetHome == null || sameDirectoryPath(targetHome, mainHome.toString())) {
                notify(DshBundle.message("dsh.notify.resetPlugins.sameHome"), NotificationType.WARNING)
                return
            }

            val homePath = Paths.get(targetHome)
            if (!DshPluginReset.hasWebProfile(homePath)) {
                notify(DshBundle.message("dsh.notify.resetPlugins.nothing"), NotificationType.INFORMATION)
                return
            }

            // Same reassurance pattern as the plugin sync: the embedded page
            // disappears during the reset and comes back by itself.
            notify(DshBundle.message("dsh.notify.resetPlugins.started"), NotificationType.INFORMATION)

            val wasRunning = currentStatus.state == DshServerState.RUNNING
            if (wasRunning) stopInternal()

            publish(DshServerStatus(DshServerState.RESETTING, detail = DshBundle.message("dsh.status.resetting.detail")))

            val outcome = DshPluginReset.removeWebProfile(homePath) { addLog(it) }
            if (outcome.error != null) {
                addLog("Plugin reset failed: ${outcome.error}")
                notify(DshBundle.message("dsh.notify.resetPlugins.failed", outcome.error), NotificationType.ERROR)
            }

            if (wasRunning) {
                startInternal()
                if (currentStatus.state == DshServerState.RUNNING) {
                    // The re-initialized default profile booted; the synced copy can go.
                    DshPluginReset.discardBackup(homePath) { addLog(it) }
                    notify(DshBundle.message("dsh.notify.resetPlugins.done"), NotificationType.INFORMATION)
                } else {
                    // Never leave the user on a half-reset profile that failed to boot.
                    DshPluginReset.restoreBackup(homePath) { addLog(it) }
                    notify(
                        DshBundle.message("dsh.notify.resetPlugins.restartFailed", currentStatus.detail.orEmpty()),
                        NotificationType.ERROR,
                    )
                }
            } else {
                DshPluginReset.discardBackup(homePath) { addLog(it) }
                notify(DshBundle.message("dsh.notify.resetPlugins.doneStopped"), NotificationType.INFORMATION)
            }
        } finally {
            pluginResetQueued = false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Output capture / readiness
    // ---------------------------------------------------------------------------------------------

    private fun pump(input: InputStream, tag: String, queue: LinkedBlockingQueue<String>?) {
        val thread = Thread {
            try {
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        addLog("[$tag] $line")
                        queue?.offer(line)
                    }
                }
            } catch (ignored: IOException) {
                // stream closed by process exit
            }
        }
        thread.isDaemon = true
        thread.name = "DeepSeekHarness-output-$tag"
        thread.start()
    }

    private val URL_PATTERN: Pattern = Pattern.compile("https?://[0-9A-Za-z.\\-]+:\\d+")

    /**
     * Blocks (lifecycle thread) until the dsh process prints its `dsh web: <url>` line or exits.
     * Returns the parsed URL, or null when the process died first or the 90 s timeout elapsed.
     */
    private fun awaitUrl(process: Process, queue: LinkedBlockingQueue<String>): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (System.nanoTime() < deadline && !stopRequested && !disposed.get()) {
            if (!process.isAlive) return null
            val line = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            val matcher = URL_PATTERN.matcher(line)
            if (matcher.find()) return matcher.group()
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // Command building
    // ---------------------------------------------------------------------------------------------

    private fun buildTokens(settings: DshSettingsState.Settings, nodeExecutable: String?): List<String> {
        val resolved = resolveCommandTokens(settings, nodeExecutable)
        val tokens = (if (resolved.isEmpty()) mutableListOf(settings.dshCommand.trim()) else resolved.toMutableList())
        tokens += "web"
        tokens += listOf("--host", settings.host.trim().ifBlank { "127.0.0.1" })
        // Always pass --port explicitly: the shipped web profile defaults to 3080, which
        // conflicts with any already-running dsh web instance. 0 = let the OS pick a port.
        val port = settings.port.coerceIn(0, 65535)
        tokens += listOf("--port", port.toString())
        return tokens
    }

    /**
     * Resolves the user-configured dsh command into executable tokens.
     *
     * - explicit paths (containing separators, a drive letter, or a known extension) are used verbatim;
     * - a bare name is looked up on PATH (with PATHEXT variants on Windows);
     * - when nothing is on PATH, the npm/npx caches are searched: `node <cached bin.js>` is
     *   preferred (no shim quoting pitfalls), the npx `dsh.cmd` shim is the last resort.
     */
    private fun resolveCommandTokens(settings: DshSettingsState.Settings, nodeExecutable: String?): List<String> {
        val parsed = parseCommandTokens(settings.dshCommand)
        if (parsed.isEmpty()) return emptyList()
        val exe = parsed.first()
        val tail = parsed.drop(1)

        if (looksLikeExplicitPath(exe)) return parsed

        findOnPath(exe)?.let { found ->
            addLog(DshBundle.message("dsh.proc.locating", found))
            return listOf(found) + tail
        }

        // Bundled runtime: the plugin ships its own copy of DeepSeek Harness, so a
        // machine without a global `dsh` install still works. The Node.js executable
        // is bundled too — PATH wins, the plugin's node-runtime is the fallback.
        val bundledBinJs = DshBundledRuntime.binJs()
        if (bundledBinJs != null) {
            if (nodeExecutable != null) {
                addLog(DshBundle.message("dsh.proc.locatingBundled", DshBundledRuntime.version() ?: "?"))
                return listOf(nodeExecutable, bundledBinJs.toString()) + tail
            }
            addLog(DshBundle.message("dsh.proc.bundledNeedsNode"))
        }

        val binJs = findNpxCachedBinJs()
        if (binJs != null) {
            if (nodeExecutable != null) {
                addLog(DshBundle.message("dsh.proc.locatingNpx", nodeExecutable, binJs))
                return listOf(nodeExecutable, binJs) + tail
            }
        }

        findNpxShim()?.let { shim ->
            addLog(DshBundle.message("dsh.proc.locatingShim", shim))
            return listOf(shim) + tail
        }

        addLog(DshBundle.message("dsh.proc.locatingMissing"))
        return parsed
    }

    /** Splits a user command string into tokens, honoring double quotes. */
    private fun parseCommandTokens(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in raw.trim()) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.setLength(0)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun looksLikeExplicitPath(exe: String): Boolean {
        if (exe.contains(File.separatorChar) || exe.contains('/')) return true
        if (exe.length >= 2 && exe[1] == ':') return true // Windows drive letter
        val lower = exe.lowercase()
        return lower.endsWith(".cmd") || lower.endsWith(".exe") || lower.endsWith(".bat")
            || lower.endsWith(".com") || lower.endsWith(".ps1") || lower.endsWith(".js")
    }

    /** Resolve and execute-probe Node.js, including Windows environment updates after IDE launch. */
    private fun resolveNode(): NodeExecutableResolver.Resolution {
        val resolution = nodeResolver.resolve(DshBundledRuntime.nodeExe())
        if (resolution.executable != null) {
            if (resolution.source == NodeExecutableResolver.Source.BUNDLED) {
                addLog(DshBundle.message("dsh.proc.locatingBundledNode"))
            }
            val messageKey = if (resolution.source == NodeExecutableResolver.Source.CURRENT_WINDOWS_ENVIRONMENT) {
                "dsh.proc.nodeDetectedFresh"
            } else {
                "dsh.proc.nodeDetected"
            }
            addLog(DshBundle.message(messageKey, resolution.version ?: "?", resolution.executable))
        }
        return resolution
    }

    /** Ensure subprocesses launched by dsh can resolve the same Node executable. */
    private fun addNodeToPath(processBuilder: ProcessBuilder, nodeExecutable: String?) {
        val nodeDir = nodeExecutable?.let(::File)?.parentFile?.absolutePath ?: return
        val environment = processBuilder.environment()
        val pathKey = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = SystemInfo.isWindows) }
            ?: if (SystemInfo.isWindows) "Path" else "PATH"
        val currentPath = environment[pathKey].orEmpty()
        val alreadyPresent = currentPath.split(File.pathSeparatorChar).any {
            it.trim().trim('"').equals(nodeDir, ignoreCase = SystemInfo.isWindows)
        }
        if (!alreadyPresent) {
            environment[pathKey] = if (currentPath.isBlank()) nodeDir else "$nodeDir${File.pathSeparator}$currentPath"
        }
    }

    private fun findOnPath(name: String): String? {
        val candidates = mutableListOf(name)
        if (SystemInfo.isWindows) {
            val pathExt = System.getenv("PATHEXT") ?: ".EXE;.BAT;.CMD"
            candidates += pathExt.split(';').filter { it.isNotBlank() }.map { name + it.lowercase() }
        }
        val pathVar = System.getenv("PATH") ?: return null
        for (rawDir in pathVar.split(File.pathSeparator)) {
            val dir = rawDir.trim().trim('"')
            if (dir.isBlank()) continue
            for (candidate in candidates) {
                val file = File(dir, candidate)
                if (file.isFile) return file.absolutePath
            }
        }
        return null
    }

    /** Searches the npm npx cache for the DeepSeek Harness CLI entry, newest match first. */
    private fun findNpxCachedBinJs(): String? =
        findInNpxCache("node_modules", "@deepseek-ai", "dsh", "lib", "bin.js")

    private fun findNpxShim(): String? {
        val shimName = if (SystemInfo.isWindows) "dsh.cmd" else "dsh"
        return findInNpxCache("node_modules", ".bin", shimName)
    }

    private fun findInNpxCache(vararg segments: String): String? {
        val roots = mutableListOf<File>()
        if (SystemInfo.isWindows) {
            System.getenv("LOCALAPPDATA")?.let { roots += File(File(it, "npm-cache"), "_npx") }
        }
        System.getProperty("user.home")?.let { roots += File(File(it, ".npm"), "_npx") }

        var best: File? = null
        for (root in roots) {
            val hashDirs = root.listFiles { f -> f.isDirectory } ?: continue
            for (hashDir in hashDirs) {
                var file = File(hashDir, segments.first())
                for (i in 1 until segments.size) file = File(file, segments[i])
                if (file.isFile && (best == null || file.lastModified() > best.lastModified())) {
                    best = file
                }
            }
        }
        return best?.absolutePath
    }

    /**
     * Windows cannot exec `.cmd`/`.ps1` npm shims reliably from ProcessBuilder, so the whole
     * command line is handed to `cmd.exe /d /s /c` with one outer pair of quotes (the `/s`
     * form preserves all inner quotes); on other platforms `/bin/sh -c` keeps a single
     * user-editable command string (which may itself contain arguments) working.
     */
    private fun platformCommand(tokens: List<String>): List<String> =
        if (SystemInfo.isWindows) {
            listOf("cmd.exe", "/d", "/s", "/c", "\"" + tokens.joinToString(" ") { quoteCmd(it) } + "\"")
        } else {
            listOf("/bin/sh", "-c", tokens.joinToString(" ") { quoteSh(it) })
        }

    private fun quoteCmd(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""

    private fun quoteSh(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun exitDescription(process: Process): String =
        if (process.isAlive) "仍在运行" else "exit=${runCatching { process.exitValue() }.getOrDefault(-1)}"

    // ---------------------------------------------------------------------------------------------
    // IDE file opening (target of the /api host.openPath interception)
    // ---------------------------------------------------------------------------------------------

    private fun openPathInIde(path: String) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed.get() || project.isDisposed) return@invokeLater
            // The "For IDE" settings-section buttons travel through host.openPath
            // as marker paths and never reach the file system.
            when {
                path.equals(SYNC_PLUGINS_PATH, ignoreCase = true) -> {
                    syncPluginsFromMainHomeAsync()
                    return@invokeLater
                }
                path.equals(SYNC_AGENT_PRESETS_PATH, ignoreCase = true) -> {
                    syncAgentPresetsFromMainHomeAsync()
                    return@invokeLater
                }
                path.equals(RESET_PLUGINS_PATH, ignoreCase = true) -> {
                    resetPluginsToDefaultAsync()
                    return@invokeLater
                }
            }
            // URLs — e.g. the feedback link of the "For IDE" settings section — open in
            // the system browser instead of being treated as file paths.
            if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
                runCatching { BrowserUtil.browse(path) }
                return@invokeLater
            }
            val file = File(path)
            if (file.isDirectory) {
                runCatching { RevealFileAction.openFile(file) }
                return@invokeLater
            }
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            if (virtualFile == null) {
                notify(DshBundle.message("dsh.notify.fileMissing", path), NotificationType.WARNING)
                return@invokeLater
            }

            // Evolution: a file opened from the DeepSeek Harness UI can land in the
            // IDE's native diff viewer instead of the plain editor. `auto` prefers the
            // VCS baseline diff when the file is modified (the agent just edited it);
            // `file` keeps the old behavior.
            val mode = DshSettingsState.getInstance().current.fileOpenMode.ifBlank { "auto" }
            if (mode == "file" || !openVcsDiff(virtualFile)) {
                openFileInEditor(virtualFile)
            }
        }
    }

    private fun openFileInEditor(virtualFile: com.intellij.openapi.vfs.VirtualFile) {
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    /** Opens IntelliJ's native diff of the working file against its VCS baseline. */
    private fun openVcsDiff(virtualFile: com.intellij.openapi.vfs.VirtualFile): Boolean {
        val change = try {
            ChangeListManager.getInstance(project).getChange(virtualFile)
        } catch (error: Throwable) {
            log.warn("DeepSeek Harness: VCS change lookup failed for ${virtualFile.path}", error)
            null
        } ?: return false
        if (change.type == Change.Type.DELETED) return false
        val beforeRevision = change.beforeRevision ?: return false
        return try {
            val beforeText = beforeRevision.content ?: return false
            val factory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(
                DshBundle.message("dsh.diff.title", virtualFile.name),
                factory.create(project, beforeText),
                factory.create(project, virtualFile),
                DshBundle.message("dsh.diff.vcsBefore"),
                DshBundle.message("dsh.diff.workspace"),
            )
            DiffManager.getInstance().showDiff(project, request)
            true
        } catch (error: Throwable) {
            log.warn("DeepSeek Harness: VCS diff failed for ${virtualFile.path}", error)
            false
        }
    }

    private fun notify(content: String, type: NotificationType) {
        // Safe from any thread: notifications must be created and shown on the EDT.
        ApplicationManager.getApplication().invokeLater {
            if (disposed.get() || project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance()
                .getNotificationGroup("DeepSeekHarness")
                .createNotification(content, type)
                .notify(project)
        }
    }

    /** Error balloon with a one-click action opening the Node.js download page. */
    private fun notifyNoNode(unsupportedVersion: String?) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed.get() || project.isDisposed) return@invokeLater
            val content = if (unsupportedVersion != null) {
                DshBundle.message("dsh.notify.nodeTooOld", unsupportedVersion)
            } else {
                DshBundle.message("dsh.notify.noNode")
            }
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("DeepSeekHarness")
                .createNotification(content, NotificationType.ERROR)
            notification.addAction(
                NotificationAction.createSimpleExpiring(
                    DshBundle.message("dsh.notify.noNode.download"),
                ) {
                    runCatching { BrowserUtil.browse("https://nodejs.org/") }
                },
            )
            notification.notify(project)
        }
    }

    /**
     * Checks `DEEPSEEK_API_KEY` through `/api credentials.describe` and surfaces an
     * actionable warning when the key is missing, so a dead first turn never goes
     * unexplained. Runs on a pooled thread; failures to determine the state only
     * produce a log line, never a false alarm.
     */
    private fun preflightCredentials(baseUrl: String) {
        if (disposed.get()) return
        val configured = runCatching {
            DshApiClient.credentialConfigured(baseUrl, "DEEPSEEK_API_KEY")
        }.getOrNull()
        when (configured) {
            true -> addLog(DshBundle.message("dsh.proc.apiKeyOk"))
            false -> {
                addLog(DshBundle.message("dsh.proc.noApiKey"))
                notify(DshBundle.message("dsh.notify.noApiKey"), NotificationType.WARNING)
            }
            else -> addLog(DshBundle.message("dsh.proc.apiKeyUnknown"))
        }
    }

    /**
     * Shows the update announcement once per plugin version, after the startup
     * environment checks have completed: the notification carries the version, the
     * build date (baked into the jar at build time) and the release notes. The last
     * shown version is persisted in the plugin settings, so each update announces
     * itself exactly once.
     */
    private fun showUpdateNotice() {
        if (disposed.get()) return
        val version = runCatching {
            PluginManagerCore.getPlugin(PluginId.getId("com.deepseek.dsh.ide"))?.version
        }.getOrNull() ?: return
        val settings = DshSettingsState.getInstance().current
        if (settings.lastUpdateNoticeVersion == version) return
        settings.lastUpdateNoticeVersion = version
        val date = DshBuildInfo.buildDate() ?: "?"
        notify(
            DshBundle.message("dsh.notify.update.title", version, date) + "\n" +
                DshBundle.message("dsh.notify.update.notes"),
            NotificationType.INFORMATION,
        )
    }

    /**
     * Makes the IDE project land on one canonical workspace in the embedded harness.
     *
     * One project must not silently split into several workspaces: the project base
     * path is the solution root, but the user (or an earlier broken flow) may already
     * have conversations under a sub-directory of it. Creating a fresh empty
     * base-path workspace next to those would steal the web app's "most recent
     * workspace" selection on every reopen, hiding the existing conversations.
     *
     * So the canonical workspace is chosen among the workspaces whose path is the
     * base path or a descendant, by the one holding the most non-blank sessions
     * (ties prefer the exact base path); a new base-path workspace is created only
     * when no project-scoped workspace exists at all. The chosen workspace is moved
     * to the front. Runs synchronously on the lifecycle thread BEFORE the browser
     * loads; failures only log.
     */
    private fun ensureProjectWorkspace(baseUrl: String) {
        if (disposed.get()) return
        val base = project.basePath ?: return
        val shortTimeout = Duration.ofSeconds(5)
        try {
            val workspaces = DshApiClient.listWorkspaces(baseUrl, shortTimeout)
            val scoped = workspaces.filter { sameOrUnder(it.path, base) }

            val target: DshApiClient.WorkspaceInfo?
            if (scoped.isEmpty()) {
                target = DshApiClient.createWorkspace(baseUrl, base, shortTimeout)
            } else if (scoped.size == 1) {
                target = scoped.first()
            } else {
                val nonBlank = DshApiClient.listSessions(baseUrl, shortTimeout)
                    .filter { !it.blank }
                    .map { it.sessionId }
                    .toHashSet()
                target = scoped.maxWithOrNull(compareBy(
                    { ws -> ws.sessionIds.count { it in nonBlank } },
                    { ws -> if (sameDirectoryPath(ws.path, base)) 1 else 0 },
                ))
                if (target != null && !sameDirectoryPath(target.path, base)) {
                    addLog(DshBundle.message("dsh.proc.workspaceMultiple", target.path))
                }
            }

            if (target == null) {
                addLog(DshBundle.message("dsh.proc.workspaceFailed", base))
                return
            }
            val first = workspaces.firstOrNull()
            if (first != null && first.workspaceId != target.workspaceId) {
                runCatching {
                    DshApiClient.insertWorkspaceBefore(baseUrl, target.workspaceId, first.workspaceId, shortTimeout)
                }
                addLog(DshBundle.message("dsh.proc.workspaceFront"))
            }
            if (scoped.isEmpty()) {
                addLog(DshBundle.message("dsh.proc.workspaceAdopted", base))
            } else {
                addLog(DshBundle.message("dsh.proc.workspaceCanonical", target.path))
            }
        } catch (error: Exception) {
            addLog(DshBundle.message("dsh.proc.workspaceFailedDetail", error.message ?: error.javaClass.simpleName))
            log.warn("DeepSeek Harness workspace adoption failed", error)
        }
    }

    /** True when [path] is [base] or lives under it, compared case-tolerantly on Windows. */
    private fun sameOrUnder(path: String, base: String): Boolean {
        val p = canonical(path) ?: return false
        val b = canonical(base) ?: return false
        val equals = if (SystemInfo.isWindows) p.equals(b, ignoreCase = true) else p == b
        if (equals) return true
        val prefix = "$b/"
        return if (SystemInfo.isWindows) p.startsWith(prefix, ignoreCase = true) else p.startsWith(prefix)
    }

    /** Case-tolerant absolute-path comparison (Windows paths are case-insensitive). */
    private fun sameDirectoryPath(a: String, b: String): Boolean {
        val normalizedA = canonical(a)
        val normalizedB = canonical(b)
        if (normalizedA == null || normalizedB == null) return false
        return if (SystemInfo.isWindows) {
            normalizedA.equals(normalizedB, ignoreCase = true)
        } else {
            normalizedA == normalizedB
        }
    }

    private fun canonical(path: String): String? = runCatching {
        File(path).canonicalPath.replace('\\', '/').trimEnd('/')
    }.getOrNull()

    // ---------------------------------------------------------------------------------------------
    // Publishing / logging
    // ---------------------------------------------------------------------------------------------

    private fun addLog(line: String) {
        val cap = DshSettingsState.getInstance().current.maxLogLines.coerceAtLeast(100)
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > cap) logLines.removeFirst()
        }
    }

    private fun publish(status: DshServerStatus) {
        currentStatus = status
        ApplicationManager.getApplication().invokeLater {
            if (!disposed.get() && !project.isDisposed) {
                project.messageBus.syncPublisher(DshServerTopics.SERVER_STATUS).onStatusChanged(status)
            }
        }
    }

    companion object {
        /**
         * Special `host.openPath` targets the "For IDE" settings section sends
         * when the user clicks its management buttons: recognized by
         * [openPathInIde] and routed to the matching manager entry point instead
         * of the file system.
         */
        const val SYNC_PLUGINS_PATH = "dsh-ide://sync-plugins"
        const val SYNC_AGENT_PRESETS_PATH = "dsh-ide://sync-agent-presets"
        const val RESET_PLUGINS_PATH = "dsh-ide://reset-plugins"
    }
}

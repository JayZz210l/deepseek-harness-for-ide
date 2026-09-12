package com.deepseek.dsh.ide.process

import com.deepseek.dsh.ide.i18n.DshBundle
import com.deepseek.dsh.ide.settings.DshSettingsState
import com.deepseek.dsh.ide.stats.DshUsageStats
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeRegistry
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.Locale

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

    /**
     * DSH edits files from a child process, so an IDE whose native file watcher
     * missed an event can keep a visible document stale indefinitely.  While
     * DSH is running, periodically refresh only the files already open in an
     * editor.  This deliberately never reloads an unsaved document.
     */
    private var openEditorRefreshTask: ScheduledFuture<*>? = null

    /** Last clean editor text and the pre-external-edit baseline used outside VCS projects. */
    private val openEditorContents = ConcurrentHashMap<String, String>()
    private val externalEditBaselines = ConcurrentHashMap<String, String>()

    /**
     * Durable snapshot used by the browser @ source. It is updated on editor
     * open/close/selection events and by the existing polling task, so a menu
     * opened during IDE/JCEF startup never has to race a one-shot VFS query.
     */
    @Volatile
    private var openEditorPathsSnapshot: List<String> = emptyList()

    /** Exact hunk data received while an Edit row is active, retained for its completed row. */
    private val toolEditDiffs = ConcurrentHashMap<String, ToolEditDiff>()

    /** Guards the one-time native→proxy fallback retry inside [startInternal]. */
    private var nativeRetryDepth = 0

    /** Guards one automatic recovery from a synced profile using removed DSH APIs. */
    private var pluginCompatibilityRetryDepth = 0

    /** Lets transactional plugin sync observe the failed boot and perform its own rollback. */
    private var pluginSyncValidationActive = false

    /** Guards the one-shot `--no-open` capability probe for external dsh runtimes. */
    private var noOpenProbeDone = false

    /** Result of the `--no-open` capability probe; only meaningful once [noOpenProbeDone] is true. */
    private var noOpenSupported = false

    /** Guards the plugin-sync entry point against duplicate clicks while one sync is queued/running. */
    @Volatile
    private var pluginSyncQueued = false

    /** Guards the agent-preset sync entry point against duplicate clicks. */
    @Volatile
    private var presetSyncQueued = false

    /** Guards the restore-default-plugins entry point against duplicate clicks. */
    @Volatile
    private var pluginResetQueued = false

    @Volatile
    private var pluginCommandQueued = false

    private val disposed = AtomicBoolean(false)

    init {
        // Keep the browser-facing @ source in sync with IDE tab/selection events
        // instead of making every menu opening race FileEditorManager startup.
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) =
                    refreshOpenEditorPathsSnapshot()

                override fun fileClosed(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) =
                    refreshOpenEditorPathsSnapshot()

                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) =
                    refreshOpenEditorPathsSnapshot()
            },
        )
    }

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
    fun resetPluginsToDefaultAsync(restartAfterReset: Boolean = false) {
        if (disposed.get() || pluginResetQueued) return
        pluginResetQueued = true
        lifecycle.execute { resetPluginsInternal(restartAfterReset) }
    }

    /** Installs one plugin directly into this project's isolated DSH profile. */
    fun installPluginFromCommandAsync(command: String) {
        val spec = DshPluginCommand.parse(command)
        if (spec == null) {
            notify(DshBundle.message("dsh.notify.installPlugin.invalid"), NotificationType.ERROR)
            return
        }
        if (disposed.get() || pluginCommandQueued) return
        pluginCommandQueued = true
        lifecycle.execute { installPluginInternal(spec) }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        stopOpenEditorRefresh()
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

        // Isolate the plugin instance's DSH home by default: a second `dsh web` on the
        // user's main ~/.dsh is not multi-instance safe and previously interfered with
        // the standalone web UI (mixed workspaces, orphaned sessions, re-bootstrapped
        // config). Blank now resolves to a per-project home seeded one-way from the
        // main home; "default" keeps the old inherit-the-environment behavior.
        // Resolved BEFORE the token build: the `--no-open` capability probe runs the
        // resolved command against this same isolated home, so the probe never touches
        // the user's main DSH home.
        val dshHome = DshHomePolicy.resolveHome(settings.dshHomeOverride, project.basePath)
        if (dshHome != null) {
            DshHomePolicy.seedHome(Paths.get(dshHome), ::addLog)
        }

        val commandResolution = resolveCommandTokens(settings, nodeResolution.executable)
        val baseTokens = DshWebLaunch.buildTokens(
            resolvedCommand = commandResolution.tokens,
            host = settings.host,
            port = settings.port,
            patchFile = null,
            // The current `dsh web` opens the default browser once ready; the embedded
            // JCEF browser is the UI, so suppress the external tab whenever the resolved
            // runtime supports the flag (bundled runtime always does; external runtimes
            // are probed once via `dsh web --help`).
            noOpen = supportsNoOpen(commandResolution, dshHome),
        )
        val mode = settings.fileJumpMode.ifBlank { "auto" }

        // ── Native (composition patch) preparation ────────────────────────────────────────────────
        var nativeActive = false
        var includeSettingsRow = false
        var bundledSettingsPackage: Path? = null
        val finalTokens = baseTokens.toMutableList()
        if (mode == "auto") {
            val implRoot = implRootFromTokens(baseTokens) ?: findNpxCachedBinJs()?.let(::implRootOf)
            // The "For IDE" settings-page row is shipped inside this plugin's bundled
            // runtime. Mount it into the project profile even when the user selected an
            // external dsh/npx runtime: the browser extension must not disappear merely
            // because command resolution preferred a different executable.
            bundledSettingsPackage = DshBundledRuntime.installRoot()
                ?.resolve("node_modules/dsh-ide-settings")
            includeSettingsRow = implRoot != null && bundledSettingsPackage?.let(Files::isDirectory) == true
            val modernControllers = implRoot != null && File(
                implRoot,
                "node_modules/@deepseek-ai/dsh-api-gateway/package.json",
            ).isFile
            val patchFile = if (implRoot != null) {
                DshNativeSupport.writeBridgeFiles(includeSettingsRow, modernControllers)
            } else null
            if (implRoot != null && patchFile != null) {
                val bridge = DshIdeBridge { path -> openPathInIde(path) }
                runCatching { bridge.start() }
                if (bridge.baseUrl.isNotEmpty()) {
                    ideBridge = bridge
                    // Launcher flags must end at the first unknown token, so
                    // `--patch <file>` goes IMMEDIATELY after `web`, before any
                    // `--host`/`--port` inner argument. The previous layout
                    // (`web --host --patch <file> 127.0.0.1 ...`) was parsed as
                    // web-app arguments by current launchers and failed every
                    // boot with "too many arguments".
                    val webIndex = finalTokens.indexOf("web")
                    if (webIndex >= 0) {
                        finalTokens.add(webIndex + 1, "--patch")
                        finalTokens.add(webIndex + 2, patchFile.toString())
                        nativeActive = true
                        addLog(DshBundle.message("dsh.proc.nativeActive", patchFile, bridge.baseUrl))
                    }
                }
            }
            if (!nativeActive) {
                addLog(DshBundle.message("dsh.proc.nativeUnavailable", "no resolvable dsh installation"))
            }
        }

        val pb = ProcessBuilder(platformCommand(finalTokens))
        addNodeToPath(pb, nodeResolution.executable)
        // The bundled runtime is launched as `node .../bin.js`, so it previously
        // worked for the IDE itself but was invisible to server-side community
        // plugins that locate `dsh.cmd` on PATH (plugin install/update/remove).
        // Expose our packaged launcher only to this child process; the user's
        // machine PATH and global npm installation remain untouched.
        if (commandResolution.source == CommandResolution.Source.BUNDLED) {
            DshBundledRuntime.cliBinDir()?.let { addDirectoryToPath(pb, it.toString()) }
        }
        project.basePath?.let { base ->
            runCatching { pb.directory(File(base)) }
        }
        if (dshHome != null) {
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
                DshNativeSupport.ensureClientSettingsLink(bundledSettingsPackage, linkHome, ::addLog)
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
            if (!pluginSyncValidationActive && pluginCompatibilityRetryDepth == 0 && hasRemovedPluginApiFailure()) {
                val targetHome = DshHomePolicy.resolveHome(
                    DshSettingsState.getInstance().current.dshHomeOverride,
                    project.basePath,
                )?.let(Paths::get)
                val mainHome = DshHomePolicy.mainHome()
                val quarantine = if (targetHome != null && !sameDirectoryPath(targetHome.toString(), mainHome.toString())) {
                    DshPluginSync.quarantineIncompatibleProfile(targetHome, ::addLog)
                } else null
                if (quarantine != null) {
                    pluginCompatibilityRetryDepth++
                    nativeRetryDepth = 0
                    stopBridge()
                    notify(DshBundle.message("dsh.notify.syncPlugins.autoRecovered", quarantine), NotificationType.WARNING)
                    startInternal()
                    return
                }
            }
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
        pluginCompatibilityRetryDepth = 0

        // The `dsh web:` URL line is printed only after the Loader settles, so the server is
        // already listening at this point. Without the native gateway, start the file-open
        // proxy in front of it: the embedded browser loads the proxy URL, everything is
        // forwarded byte-for-byte and `POST /api/host.openPath` is answered locally.
        val parsedServerUri = runCatching { java.net.URI.create(url) }.getOrNull()
        val realPort = parsedServerUri?.port ?: 0
        var browserUrl = url
        if (realPort > 0) {
            // The browser always uses the local proxy.  Besides the legacy
            // host.openPath fallback it exposes the IDE's live editor tabs to
            // the client-side @ source; native host integration still travels
            // through the composition bridge unchanged.
            val newProxy = DshApiProxy(
                onOpenPath = { path -> openPathInIde(path) },
                openFilesJson = ::openEditorFilesJson,
            )
            val proxyPort = runCatching { newProxy.start(realPort) }.getOrNull()
            if (proxyPort != null) {
                proxy = newProxy
                browserUrl = "http://127.0.0.1:$proxyPort" +
                    (parsedServerUri?.rawQuery?.let { "?$it" } ?: "")
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
        // Agent presets are synced first: the isolated home inherits the main home's
        // settings.yaml, which may name a preset (e.g. an anchored persona) that only
        // exists in the main home — without it every session.create fails and the web
        // UI cannot select any workspace at all.
        syncAgentPresetsForStartup()
        ensureProjectWorkspace(url)

        DshUsageStats.getInstance().recordStart()
        runningSinceNanos = System.nanoTime()
        currentStatus = DshServerStatus(DshServerState.RUNNING, url = browserUrl, realUrl = url, pid = spawned.pid())
        publish(currentStatus)
        startOpenEditorRefresh()

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

    /** Active editor first, followed by the remaining open tabs in IDE order. */
    private fun openEditorFilesJson(): String {
        // Refresh once at request time as a final guard for IDEs that do not
        // publish a selection event during an editor replacement. Keep the
        // durable snapshot when the manager reports a transient empty state.
        val current = captureOpenEditorPaths()
        if (current.isNotEmpty()) openEditorPathsSnapshot = current
        val paths = if (current.isNotEmpty()) current else openEditorPathsSnapshot
        return paths.joinToString(prefix = "[", postfix = "]", transform = ::jsonString)
    }

    /** Refreshes the durable current/open-editor snapshot; safe from any thread. */
    private fun refreshOpenEditorPathsSnapshot() {
        if (disposed.get() || project.isDisposed) return
        val current = captureOpenEditorPaths()
        if (current.isNotEmpty() || !hasOpenEditorFiles()) openEditorPathsSnapshot = current
    }

    private fun captureOpenEditorPaths(): List<String> {
        if (disposed.get() || project.isDisposed) return emptyList()
        var paths: List<String> = emptyList()
        val capture = {
            if (!project.isDisposed) {
                val files = FileEditorManager.getInstance(project)
                paths = (files.selectedFiles.toList() + files.openFiles)
                    .distinct()
                    .map(::editorPath)
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) capture()
        else ApplicationManager.getApplication().invokeAndWait(capture)
        return paths
    }

    private fun hasOpenEditorFiles(): Boolean {
        if (disposed.get() || project.isDisposed) return false
        var hasFiles = false
        val capture = {
            if (!project.isDisposed) hasFiles = FileEditorManager.getInstance(project).openFiles.isNotEmpty()
        }
        if (ApplicationManager.getApplication().isDispatchThread) capture()
        else ApplicationManager.getApplication().invokeAndWait(capture)
        return hasFiles
    }

    private fun editorPath(file: com.intellij.openapi.vfs.VirtualFile): String =
        project.basePath?.let { base ->
            runCatching { File(base).toPath().relativize(file.toNioPath()).toString() }.getOrNull()
        }?.replace('\\', '/') ?: file.path.replace('\\', '/')

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
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

        stopOpenEditorRefresh()
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
        stopOpenEditorRefresh()
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

    private fun startOpenEditorRefresh() {
        stopOpenEditorRefresh()
        // Populate before the first browser page load; the old one-shot HTTP
        // lookup could return [] during IDE tab restoration.
        refreshOpenEditorPathsSnapshot()
        openEditorRefreshTask = lifecycle.scheduleWithFixedDelay(
            { refreshOpenEditorsFromDisk() },
            1,
            1,
            TimeUnit.SECONDS,
        )
    }

    private fun stopOpenEditorRefresh() {
        openEditorRefreshTask?.cancel(false)
        openEditorRefreshTask = null
        openEditorContents.clear()
        externalEditBaselines.clear()
        toolEditDiffs.clear()
        openEditorPathsSnapshot = emptyList()
    }

    /**
     * Compares open editors with the real file contents on disk instead of with
     * VFS timestamps. The latter can remain stale when the native watcher misses
     * a DSH write (notably after atomic replaces), and a first polling pass can
     * otherwise mistake an already changed file for the initial state.
     */
    private fun refreshOpenEditorsFromDisk() {
        if (disposed.get() || project.isDisposed || currentStatus.state != DshServerState.RUNNING) return
        var openFiles: List<OpenEditorSnapshot> = emptyList()
        ApplicationManager.getApplication().invokeAndWait {
            if (!project.isDisposed) {
                val documents = FileDocumentManager.getInstance()
                val editors = FileEditorManager.getInstance(project)
                openEditorPathsSnapshot = (editors.selectedFiles.toList() + editors.openFiles)
                    .distinct()
                    .map(::editorPath)
                openFiles = editors.openFiles.map { file ->
                    val document = documents.getCachedDocument(file)
                    OpenEditorSnapshot(
                        file = file,
                        text = document?.text,
                        unsaved = document != null && documents.isDocumentUnsaved(document),
                    )
                }
            }
        }
        val livePaths = HashSet<String>()
        for ((openFile, editorText, unsaved) in openFiles) {
            if (!openFile.isInLocalFileSystem || editorText == null) continue
            val path = openFile.path
            livePaths += path
            val previousText = openEditorContents.put(path, editorText)

            // Preserve the previous clean text if IntelliJ's watcher refreshed
            // the document before this polling pass observed the disk change.
            if (!unsaved && previousText != null && previousText != editorText) {
                externalEditBaselines.putIfAbsent(path, previousText)
            }

            val diskText = try {
                Files.readString(Paths.get(path), openFile.charset)
            } catch (error: Exception) {
                log.debug("Unable to inspect open editor on disk: $path", error)
                continue
            }
            if (!unsaved && normalizeEditorText(diskText) != normalizeEditorText(editorText)) {
                // Keep the first pre-change snapshot until the user opens Diff.
                externalEditBaselines.putIfAbsent(path, previousText ?: editorText)
                val refreshed = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path)) ?: continue
                // Force VFS metadata/content caches to observe the external write
                // before FileDocumentManager reloads the clean document on EDT.
                refreshed.refresh(false, false)
                reloadCleanDocument(refreshed)
            }
        }
        openEditorContents.keys.retainAll(livePaths)
        externalEditBaselines.keys.retainAll(livePaths)
    }

    private fun normalizeEditorText(text: String): String {
        val withoutBom = if (text.startsWith('\uFEFF')) text.substring(1) else text
        return withoutBom.replace("\r\n", "\n").replace('\r', '\n')
    }

    private data class OpenEditorSnapshot(
        val file: com.intellij.openapi.vfs.VirtualFile,
        val text: String?,
        val unsaved: Boolean,
    )

    private fun reloadCleanDocument(file: com.intellij.openapi.vfs.VirtualFile) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed.get() || project.isDisposed || !file.isValid) return@invokeLater
            val documents = FileDocumentManager.getInstance()
            val document = documents.getCachedDocument(file) ?: return@invokeLater
            // Recheck on EDT so a keystroke made after the background snapshot
            // can never be overwritten by the external refresh.
            if (!documents.isDocumentUnsaved(document)) {
                documents.reloadFromDisk(document)
                openEditorContents[file.path] = document.text
            }
        }
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
            if (result.error == DshPluginSync.ERROR_PNPM_NOT_FOUND) {
                notifyNoPnpm()
                if (wasRunning) startInternal() else publish(DshServerStatus(DshServerState.STOPPED))
            } else if (result.error != null) {
                notify(DshBundle.message("dsh.notify.syncPlugins.failed", result.error), NotificationType.ERROR)
                if (wasRunning) startInternal() else publish(DshServerStatus(DshServerState.STOPPED))
            } else if (!result.changed) {
                notify(DshBundle.message("dsh.notify.syncPlugins.upToDate"), NotificationType.INFORMATION)
                if (wasRunning) startInternal() else publish(DshServerStatus(DshServerState.STOPPED))
            } else {
                // pnpm success only proves installation. Boot once with the current
                // bundled DSH before committing, because community plugins may import
                // APIs removed by a newer DSH release.
                pluginSyncValidationActive = true
                try {
                    startInternal()
                } finally {
                    pluginSyncValidationActive = false
                }
                if (currentStatus.state == DshServerState.RUNNING) {
                    DshPluginSync.commit(Paths.get(targetHome)) { line -> syncLog += line; addLog(line) }
                    val message = if (result.skippedPackages.isEmpty()) {
                        DshBundle.message("dsh.notify.syncPlugins.done")
                    } else {
                        DshBundle.message(
                            "dsh.notify.syncPlugins.doneWithSkipped",
                            result.skippedPackages.joinToString(", "),
                        )
                    }
                    notify(message, NotificationType.INFORMATION)
                    if (!wasRunning) stopInternal()
                } else {
                    val failure = currentStatus.detail.orEmpty()
                    DshPluginSync.rollback(Paths.get(targetHome)) { line -> syncLog += line; addLog(line) }
                    notify(DshBundle.message("dsh.notify.syncPlugins.incompatible", failure), NotificationType.ERROR)
                    if (wasRunning) startInternal() else publish(DshServerStatus(DshServerState.STOPPED))
                }
            }
        } finally {
            // startInternal clears the rolling log at its beginning, so the sync
            // transcript is appended again afterwards to survive the restart.
            syncLog.forEach(::addLog)
            pluginSyncQueued = false
        }
    }

    private fun hasRemovedPluginApiFailure(): Boolean = snapshotLog().takeLast(400).any { line ->
        line.contains("does not provide an export named 'installSettingsSection'") ||
            line.contains("does not provide an export named 'settingsNamespace'") ||
            line.contains("Cannot find package '@deepseek-ai/dsh-host-apiproxy'")
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

    private fun resetPluginsInternal(restartAfterReset: Boolean = false) {
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
                if (restartAfterReset && currentStatus.state != DshServerState.RUNNING) startInternal()
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

            // A failed/stopped server has no RUNNING transition to trigger a
            // restart, but the recovery action in the toolbar must bring the
            // clean default profile back online immediately.
            val shouldRestart = wasRunning || restartAfterReset
            if (shouldRestart) {
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

    private fun installPluginInternal(spec: String) {
        try {
            val settings = DshSettingsState.getInstance().current
            val targetHome = DshHomePolicy.resolveHome(settings.dshHomeOverride, project.basePath)
            if (targetHome == null) {
                notify(DshBundle.message("dsh.notify.installPlugin.failed", "无法解析项目 DSH_HOME"), NotificationType.ERROR)
                return
            }
            val wasRunning = currentStatus.state == DshServerState.RUNNING
            if (wasRunning) stopInternal()
            publish(DshServerStatus(DshServerState.SYNCING, detail = DshBundle.message("dsh.status.installingPlugin")))

            val node = resolveNode()
            if (node.executable == null) {
                notify(DshBundle.message("dsh.notify.installPlugin.failed", DshBundle.message("dsh.proc.noNode")), NotificationType.ERROR)
                publish(DshServerStatus(DshServerState.FAILED, detail = DshBundle.message("dsh.proc.noNode")))
                return
            }
            val command = resolveCommandTokens(settings, node.executable)
            val tokens = command.tokens + listOf("plugin", "--profile", "web", "add", spec)
            val processBuilder = ProcessBuilder(platformCommand(tokens))
            addNodeToPath(processBuilder, node.executable)
            if (command.source == CommandResolution.Source.BUNDLED) {
                DshBundledRuntime.cliBinDir()?.let { addDirectoryToPath(processBuilder, it.toString()) }
            }
            processBuilder.environment()["DSH_HOME"] = targetHome
            project.basePath?.let { processBuilder.directory(File(it)) }
            processBuilder.redirectErrorStream(true)
            val process = runCatching { processBuilder.start() }.getOrElse {
                notify(DshBundle.message("dsh.notify.installPlugin.failed", it.message ?: it.javaClass.simpleName), NotificationType.ERROR)
                publish(DshServerStatus(DshServerState.FAILED, detail = it.message))
                return
            }
            addLog(DshBundle.message("dsh.proc.installPlugin.command", spec))
            val output = StringBuilder()
            val pump = Thread {
                runCatching {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach { line -> output.appendLine(line); addLog("[plugin] $line") }
                    }
                }
            }.apply { isDaemon = true; name = "DeepSeekHarness-plugin-install"; start() }
            val finished = process.waitFor(5, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                notify(DshBundle.message("dsh.notify.installPlugin.failed", "安装超时"), NotificationType.ERROR)
                publish(DshServerStatus(DshServerState.FAILED, detail = "插件安装超时"))
                return
            }
            pump.join(5_000)
            if (process.exitValue() != 0) {
                val detail = output.toString().trim().takeLast(6000)
                notify(DshBundle.message("dsh.notify.installPlugin.failed", detail), NotificationType.ERROR)
                if (wasRunning) startInternal() else publish(DshServerStatus(DshServerState.FAILED, detail = detail))
                return
            }
            notify(DshBundle.message("dsh.notify.installPlugin.done", spec), NotificationType.INFORMATION)
            // A direct install is intended to be immediately usable, including
            // when the service was stopped or had failed before the action.
            startInternal()
        } finally {
            pluginCommandQueued = false
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

    private val URL_PATTERN: Pattern = Pattern.compile("https?://[0-9A-Za-z.\\-]+:\\d+(?:/[^\\s()]*)?")

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

    /** How the configured dsh command resolved; decides `--no-open` probing. */
    data class CommandResolution(
        val tokens: List<String>,
        val source: Source,
    ) {
        enum class Source { EXPLICIT, PATH, BUNDLED, NPX_BIN_JS, NPX_SHIM, UNRESOLVED }
    }

    /**
     * Resolves the user-configured dsh command into executable tokens.
     *
     * - explicit paths (containing separators, a drive letter, or a known extension) are used verbatim;
     * - the default bare `dsh` command prefers the plugin's pinned bundled runtime;
     * - any other bare name is looked up on PATH (with PATHEXT variants on Windows);
     * - when nothing is on PATH, the npm/npx caches are searched: `node <cached bin.js>` is
     *   preferred (no shim quoting pitfalls), the npx `dsh.cmd` shim is the last resort.
     */
    private fun resolveCommandTokens(settings: DshSettingsState.Settings, nodeExecutable: String?): CommandResolution {
        val parsed = parseCommandTokens(settings.dshCommand)
        val fallback = CommandResolution(
            if (parsed.isEmpty()) mutableListOf(settings.dshCommand.trim()) else parsed,
            CommandResolution.Source.UNRESOLVED,
        )
        if (parsed.isEmpty()) return fallback
        val exe = parsed.first()
        val tail = parsed.drop(1)

        if (looksLikeExplicitPath(exe)) {
            return CommandResolution(parsed, CommandResolution.Source.EXPLICIT)
        }

        // A global dsh on PATH must not silently change the runtime, schema, or
        // client APIs used by the plugin. The default bare command means "use the
        // managed runtime"; an explicit executable/path remains an opt-in escape
        // hatch for users who intentionally want an external installation.
        val bundledBinJs = DshBundledRuntime.binJs()
        if (DshBundledRuntime.shouldPreferBundled(parsed) && bundledBinJs != null) {
            if (nodeExecutable != null) {
                addLog(DshBundle.message("dsh.proc.locatingBundled", DshBundledRuntime.version() ?: "?"))
                return CommandResolution(listOf(nodeExecutable, bundledBinJs.toString()) + tail, CommandResolution.Source.BUNDLED)
            }
            addLog(DshBundle.message("dsh.proc.bundledNeedsNode"))
        }

        findOnPath(exe)?.let { found ->
            addLog(DshBundle.message("dsh.proc.locating", found))
            return CommandResolution(listOf(found) + tail, CommandResolution.Source.PATH)
        }

        // If a custom bare command was not found on PATH, retain the bundled
        // runtime as the general availability fallback.
        if (bundledBinJs != null) {
            if (nodeExecutable != null) {
                addLog(DshBundle.message("dsh.proc.locatingBundled", DshBundledRuntime.version() ?: "?"))
                return CommandResolution(listOf(nodeExecutable, bundledBinJs.toString()) + tail, CommandResolution.Source.BUNDLED)
            }
            addLog(DshBundle.message("dsh.proc.bundledNeedsNode"))
        }

        val binJs = findNpxCachedBinJs()
        if (binJs != null) {
            if (nodeExecutable != null) {
                addLog(DshBundle.message("dsh.proc.locatingNpx", nodeExecutable, binJs))
                return CommandResolution(listOf(nodeExecutable, binJs) + tail, CommandResolution.Source.NPX_BIN_JS)
            }
        }

        findNpxShim()?.let { shim ->
            addLog(DshBundle.message("dsh.proc.locatingShim", shim))
            return CommandResolution(listOf(shim) + tail, CommandResolution.Source.NPX_SHIM)
        }

        addLog(DshBundle.message("dsh.proc.locatingMissing"))
        return fallback
    }

    /**
     * Whether the resolved runtime accepts `dsh web --no-open`.
     *
     * The plugin pins the bundled runtime version, so its capability is known at
     * build time. An external runtime (PATH/npx) may be any DSH release, so its
     * support is probed ONCE per project session: `dsh web --help` prints the web
     * app's flag family, which names `--no-open` exactly when the flag exists.
     * The probe only parses help text — it never starts a server — and runs
     * against the isolated DSH home so the user's main home stays untouched.
     * A probe failure errs toward NOT passing the flag: an older runtime that
     * rejects unknown flags must keep booting, and older releases never opened
     * the default browser anyway.
     */
    private fun supportsNoOpen(resolution: CommandResolution, dshHome: String?): Boolean {
        if (resolution.source == CommandResolution.Source.BUNDLED) return true
        if (!noOpenProbeDone) {
            noOpenProbeDone = true
            noOpenSupported = probeNoOpen(resolution, dshHome)
            addLog(
                DshBundle.message(
                    if (noOpenSupported) "dsh.proc.noOpenSupported" else "dsh.proc.noOpenUnsupported",
                    resolution.tokens.joinToString(" "),
                )
            )
        }
        return noOpenSupported
    }

    /** One-shot `dsh web --help` probe; returns true when the help names `--no-open`. */
    private fun probeNoOpen(resolution: CommandResolution, dshHome: String?): Boolean {
        val probeTokens = DshWebLaunch.helpProbeTokens(resolution.tokens)
        val pb = ProcessBuilder(platformCommand(probeTokens))
        if (dshHome != null) pb.environment()["DSH_HOME"] = dshHome
        pb.redirectErrorStream(true)
        val output = StringBuilder()
        return try {
            val process = pb.start()
            val pump = Thread {
                runCatching {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                        if (output.length < 128 * 1024) output.appendLine(line)
                    }
                }
            }
            pump.isDaemon = true
            pump.start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            output.toString().contains("--no-open")
        } catch (error: Exception) {
            addLog(DshBundle.message("dsh.proc.noOpenProbeFailed", error.message ?: error.javaClass.simpleName))
            false
        }
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
        addDirectoryToPath(processBuilder, nodeDir)
    }

    /** Prepend one directory to the subprocess PATH without mutating the IDE environment. */
    private fun addDirectoryToPath(processBuilder: ProcessBuilder, directory: String) {
        val environment = processBuilder.environment()
        val pathKey = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = SystemInfo.isWindows) }
            ?: if (SystemInfo.isWindows) "Path" else "PATH"
        val currentPath = environment[pathKey].orEmpty()
        val alreadyPresent = currentPath.split(File.pathSeparatorChar).any {
            it.trim().trim('"').equals(directory, ignoreCase = SystemInfo.isWindows)
        }
        if (!alreadyPresent) {
            environment[pathKey] = if (currentPath.isBlank()) directory else "$directory${File.pathSeparator}$currentPath"
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

    /** Entry point for the JCEF-native settings-page bridge. */
    fun openPathFromBrowser(path: String, line: Int? = null) = openPathInIde(path, line)

    /** Native JCEF bridge endpoint for the @ source; bypasses the DSH HTTP proxy. */
    fun openEditorFilesJsonFromBrowser(): String = openEditorFilesJson()

    /** Opens the exact before/after fragments carried by a DSH Edit tool result. */
    fun openDiffFromBrowser(path: String, beforeText: String, afterText: String) {
        // Some DSH frontends expose the hunk-backed file button only while the
        // tool is running. Keep the authoritative hunk so clicking the same
        // completed Edit row can still open the exact Diff instead of falling
        // back to a plain path open after the row settles.
        toolEditDiffs[pathKey(path)] = ToolEditDiff(beforeText, afterText)
        ApplicationManager.getApplication().invokeLater {
            if (disposed.get() || project.isDisposed) return@invokeLater
            val file = resolveIdeFile(path)
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            val displayName = virtualFile?.name ?: file.name.ifBlank { path }
            try {
                val factory = DiffContentFactory.getInstance()
                // Text-only DiffContent is classified as diff.txt, which prevents the IDE from
                // applying the target language's syntax highlighter. Preserve the edited file's
                // type for both snapshots, including the short interval before VFS sees a new file.
                val fileType = virtualFile?.fileType
                    ?: FileTypeRegistry.getInstance().getFileTypeByFileName(displayName)
                val request = SimpleDiffRequest(
                    DshBundle.message("dsh.diff.title", displayName),
                    factory.create(project, beforeText, fileType),
                    factory.create(project, afterText, fileType),
                    DshBundle.message("dsh.diff.beforeEdit"),
                    DshBundle.message("dsh.diff.afterEdit"),
                )
                DiffManager.getInstance().showDiff(project, request)
            } catch (error: Throwable) {
                log.warn("DeepSeek Harness: tool edit diff failed for $path", error)
                openPathInIde(path)
            }
        }
    }

    private fun openPathInIde(path: String, line: Int? = null) {
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
                path.equals(OPEN_SETTINGS_DOCUMENT_PATH, ignoreCase = true) -> {
                    openSettingsDocumentInIde()
                    return@invokeLater
                }
            }
            // URLs — e.g. the feedback link of the "For IDE" settings section — open in
            // the system browser instead of being treated as file paths.
            if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
                runCatching { BrowserUtil.browse(path) }
                return@invokeLater
            }
            val file = resolveIdeFile(path)
            if (file.isDirectory) {
                openDirectoryInIde(file)
                return@invokeLater
            }
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            if (virtualFile == null) {
                notify(DshBundle.message("dsh.notify.fileMissing", path), NotificationType.WARNING)
                return@invokeLater
            }

            val mode = DshSettingsState.getInstance().current.fileOpenMode.ifBlank { "auto" }
            val exactEdit = toolEditDiffs.remove(pathKey(virtualFile.path))
            if (exactEdit != null) {
                // Exact Edit hunks are authoritative and should work even when
                // the VCS change list has not caught up with an atomic write.
                if (!showFileDiff(
                        virtualFile,
                        exactEdit.beforeText,
                        DshBundle.message("dsh.diff.beforeEdit"),
                    )
                ) {
                    openFileInEditor(virtualFile, line)
                }
                return@invokeLater
            }

            // Evolution: a file opened from the DeepSeek Harness UI can land in the
            // IDE's native diff viewer instead of the plain editor. `auto` prefers the
            // VCS baseline diff when the file is modified (the agent just edited it);
            // `file` keeps the old behavior.
            if (mode == "file") {
                openFileInEditor(virtualFile, line)
            } else {
                openDiffOrFileAsync(virtualFile, line)
            }
        }
    }

    /** Resolves the relative path displayed by a Tool row against this IDE project. */
    private fun resolveIdeFile(path: String): File {
        val expanded = if (path == "~") {
            System.getProperty("user.home")
        } else if (path.startsWith("~/") || path.startsWith("~\\")) {
            System.getProperty("user.home") + path.substring(1)
        } else {
            path
        }
        val candidate = File(expanded)
        return if (candidate.isAbsolute) candidate else File(project.basePath ?: ".", expanded)
    }

    /** Opens project directories in the IDE Project view; external directories fall back to Explorer/Finder. */
    private fun openDirectoryInIde(directory: File) {
        val base = project.basePath
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)
        if (base != null && virtualFile != null && sameOrUnder(directory.path, base)) {
            runCatching { ProjectView.getInstance(project).select(null, virtualFile, true) }
                .onFailure { error ->
                    log.warn("DeepSeek Harness: project directory reveal failed for ${directory.path}", error)
                    runCatching { RevealFileAction.openFile(directory) }
                }
        } else {
            runCatching { RevealFileAction.openFile(directory) }
        }
    }

    /** Materializes the active DSH settings file and opens it in this IDE rather than a desktop editor. */
    private fun openSettingsDocumentInIde() {
        val settings = DshSettingsState.getInstance().current
        val home = DshHomePolicy.resolveHome(settings.dshHomeOverride, project.basePath)
            ?.let(Paths::get)
            ?: DshHomePolicy.mainHome()
        val document = home.resolve("settings.yaml")
        runCatching {
            Files.createDirectories(document.parent)
            if (!Files.exists(document)) Files.createFile(document)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(document.toFile())
                ?.let(::openFileInEditor)
                ?: error("settings file is not visible to the IDE VFS")
        }.onFailure { error ->
            log.warn("DeepSeek Harness: settings document open failed for $document", error)
            notify(DshBundle.message("dsh.notify.fileMissing", document.toString()), NotificationType.WARNING)
        }
    }

    private fun openFileInEditor(virtualFile: com.intellij.openapi.vfs.VirtualFile, line: Int? = null) {
        if (line != null && line > 0) {
            FileEditorManager.getInstance(project).openTextEditor(
                OpenFileDescriptor(project, virtualFile, line - 1, 0),
                true,
            )
        } else {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
    }

    private fun pathKey(path: String): String =
        resolveIdeFile(path).absoluteFile.normalize().path.lowercase(Locale.ROOT)

    private data class ToolEditDiff(
        val beforeText: String,
        val afterText: String,
    )

    /**
     * Loads potentially blocking VCS/VFS content away from the EDT, then returns to
     * the EDT to create and show the native diff. Newer JetBrains builds explicitly
     * reject GitContentRevision.getContent() on the event-dispatch thread.
     */
    private fun openDiffOrFileAsync(virtualFile: com.intellij.openapi.vfs.VirtualFile, line: Int? = null) {
        val change = try {
            ChangeListManager.getInstance(project).getChange(virtualFile)
        } catch (error: Throwable) {
            log.warn("DeepSeek Harness: VCS change lookup failed for ${virtualFile.path}", error)
            null
        }
        val beforeRevision = change
            ?.takeUnless { it.type == Change.Type.DELETED }
            ?.beforeRevision

        ApplicationManager.getApplication().executeOnPooledThread {
            val vcsBeforeText = beforeRevision?.let { revision ->
                try {
                    revision.content
                } catch (error: Throwable) {
                    log.warn("DeepSeek Harness: VCS content load failed for ${virtualFile.path}", error)
                    null
                }
            }
            val externalBeforeText = if (vcsBeforeText == null) {
                externalEditBaselines.remove(virtualFile.path)
            } else {
                null
            }
            val externalCurrentText = externalBeforeText?.let {
                runCatching { String(virtualFile.contentsToByteArray(), virtualFile.charset) }
                    .onFailure { error ->
                        log.warn("DeepSeek Harness: external edit content load failed for ${virtualFile.path}", error)
                    }
                    .getOrNull()
            }

            ApplicationManager.getApplication().invokeLater {
                if (disposed.get() || project.isDisposed || !virtualFile.isValid) return@invokeLater
                val shown = when {
                    vcsBeforeText != null -> showFileDiff(
                        virtualFile,
                        vcsBeforeText,
                        DshBundle.message("dsh.diff.vcsBefore"),
                    )
                    externalBeforeText != null &&
                        externalCurrentText != null &&
                        externalBeforeText != externalCurrentText -> showFileDiff(
                            virtualFile,
                            externalBeforeText,
                            DshBundle.message("dsh.diff.beforeEdit"),
                        )
                    else -> false
                }
                if (!shown) openFileInEditor(virtualFile, line)
            }
        }
    }

    /** Creates and displays diff UI; must be called on the EDT. */
    private fun showFileDiff(
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        beforeText: String,
        beforeTitle: String,
    ): Boolean = try {
            val factory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(
                DshBundle.message("dsh.diff.title", virtualFile.name),
                factory.create(project, beforeText, virtualFile.fileType),
                factory.create(project, virtualFile),
                beforeTitle,
                DshBundle.message("dsh.diff.workspace"),
            )
            DiffManager.getInstance().showDiff(project, request)
            true
        } catch (error: Throwable) {
            log.warn("DeepSeek Harness: native diff failed for ${virtualFile.path}", error)
            false
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
        val version = DshBuildInfo.version() ?: return
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
     *
     * Selection policies differ between DSH releases, so the workspace is made the
     * auto-selected one under BOTH known rules: the list-front move covers releases
     * that select the first workspace, and a fresh blank session in the target
     * workspace covers releases that select the most recently active one (its
     * `updatedAt` is now — the web UI then reuses exactly that blank session when
     * it opens the workspace). See [DshWorkspacePolicy] for the recency rule.
     */
    private fun ensureProjectWorkspace(baseUrl: String) {
        if (disposed.get()) return
        val base = project.basePath ?: return
        val shortTimeout = Duration.ofSeconds(5)
        try {
            if (DshApiClient.isModernRemote(baseUrl, shortTimeout)) {
                ensureModernProjectWorkspace(baseUrl, base, shortTimeout)
                return
            }
            val workspaces = DshApiClient.listWorkspaces(baseUrl, shortTimeout)
            val scoped = workspaces.filter { sameOrUnder(it.path, base) }
            val sessions = DshApiClient.listSessions(baseUrl, shortTimeout)

            val target: DshApiClient.WorkspaceInfo?
            if (scoped.isEmpty()) {
                target = DshApiClient.createWorkspace(baseUrl, base, shortTimeout)
            } else if (scoped.size == 1) {
                target = scoped.first()
            } else {
                val nonBlank = sessions.filter { !it.blank }.map { it.sessionId }.toHashSet()
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

            // Recency-policy selection (current releases): a blank session is only
            // minted when the target is NOT already the activity-most-recent workspace,
            // so repeated project opens never accumulate blank sessions.
            if (DshWorkspacePolicy.needsBlankSessionBump(workspaces, sessions, target)) {
                runCatching {
                    // Workspace-scoped create: keeps the session accounted inside the
                    // workspace, exactly like the web UI's own New Session flow; older
                    // dsh generations fall back to a cwd-only create inside the client.
                    DshApiClient.createSession(baseUrl, target.workspaceId, target.path)
                }.onSuccess {
                    addLog(DshBundle.message("dsh.proc.workspaceBlankSession"))
                }.onFailure { error ->
                    addLog(DshBundle.message("dsh.proc.workspaceBlankFailed", error.message ?: error.javaClass.simpleName))
                }
            }
        } catch (error: Exception) {
            addLog(DshBundle.message("dsh.proc.workspaceFailedDetail", error.message ?: error.javaClass.simpleName))
            log.warn("DeepSeek Harness workspace adoption failed", error)
        }
    }

    /**
     * DSH 0.1.2 replaced the unary workspace list with a WebSocket follow stream.
     * `workspace/create` is idempotent and returns the complete target row, so it
     * is sufficient for deterministic project adoption without implementing a
     * second streaming client in the IDE process.
     */
    private fun ensureModernProjectWorkspace(baseUrl: String, base: String, timeout: Duration) {
        val target = DshApiClient.createWorkspace(baseUrl, base, timeout)
        if (target == null) {
            addLog(DshBundle.message("dsh.proc.workspaceFailed", base))
            return
        }
        addLog(DshBundle.message("dsh.proc.workspaceCanonical", target.path))
        val sessions = DshApiClient.listSessions(baseUrl, timeout)
        val hasBlank = target.sessionIds.any { id -> sessions.any { it.sessionId == id && it.blank } }
        if (!hasBlank) {
            runCatching { DshApiClient.createSession(baseUrl, target.workspaceId, target.path) }
                .onSuccess { addLog(DshBundle.message("dsh.proc.workspaceBlankSession")) }
                .onFailure { error ->
                    addLog(DshBundle.message("dsh.proc.workspaceBlankFailed", error.message ?: error.javaClass.simpleName))
                }
        }
    }

    /**
     * One-way copy of the main home's locally authored agent presets into this
     * project's isolated home, BEFORE the web UI loads. The seeded settings.yaml
     * can name a preset (e.g. an anchored persona preset) that only exists in the
     * main home; without it, EVERY `session.create` fails with
     * `agent-preset-not-found` and the web UI cannot select any workspace. Runs on
     * the lifecycle thread with the usual failure-only-log contract; idempotent
     * (byte-compare per file) so the manual sync button stays available for
     * mid-session edits.
     */
    private fun syncAgentPresetsForStartup() {
        if (disposed.get()) return
        try {
            val settings = DshSettingsState.getInstance().current
            val targetHome = DshHomePolicy.resolveHome(settings.dshHomeOverride, project.basePath)
            val mainHome = DshHomePolicy.mainHome()
            if (targetHome == null || sameDirectoryPath(targetHome, mainHome.toString())) return
            if (!DshPresetSync.hasPresets(mainHome)) return
            val result = DshPresetSync.sync(mainHome, Paths.get(targetHome)) { addLog(it) }
            if (result.error != null) {
                addLog(DshBundle.message("dsh.proc.presetSyncFailed", result.error))
            } else if (result.changed) {
                addLog(DshBundle.message("dsh.proc.presetsSynced", mainHome.toString()))
            }
        } catch (error: Exception) {
            addLog(DshBundle.message("dsh.proc.presetSyncFailed", error.message ?: error.javaClass.simpleName))
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
        const val OPEN_SETTINGS_DOCUMENT_PATH = "dsh-ide://open-settings-document"
    }
}

package com.deepseek.dsh.ide.process

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.concurrent.TimeUnit

/**
 * One-click, one-way plugin-list sync from the user's main DSH home into the
 * project's isolated home. Pure JDK on purpose — testable outside the IDE.
 *
 * The external workflow stays unchanged: users install plugins with
 * `dsh plugin --profile web add <pkg>` in a terminal (which writes
 * `profiles/web/package.json`, `cordis.patch.yml` and `pnpm-workspace.yaml`
 * under `~/.dsh`). This helper copies ONLY those manifest files into the
 * isolated home and runs `pnpm install` there, so the IDE instance loads the
 * same plugin set on its next start. `node_modules` is never copied —
 * absolute symlinks/junctions in it do not survive relocation and pnpm
 * rebuilds it from the manifest anyway.
 *
 * Failure safety: the complete previous profile is moved aside as a transaction
 * backup. The caller commits only after the new DSH process reaches RUNNING;
 * install or boot failure restores the old profile including node_modules.
 */
object DshPluginSync {

    const val PROFILE = "web"

    /** Special install-error marker surfaced by [sync]; the caller maps it to user guidance. */
    const val ERROR_PNPM_NOT_FOUND = "pnpm-not-found"

    private val SYNC_FILES = listOf(
        "package.json",
        "cordis.patch.yml",
        "pnpm-workspace.yaml",
        "pnpm-lock.yaml",
    )

    const val BACKUP_NAME = "$PROFILE.dsh-ide-sync-bak"
    private const val INSTALL_TIMEOUT_MINUTES = 5L
    private const val MAX_CAPTURED_LINES = 500

    data class Result(
        /** True when at least one manifest file was copied into the isolated home. */
        val changed: Boolean,
        /** Human-readable failure, [ERROR_PNPM_NOT_FOUND], or null on success. */
        val error: String? = null,
        /** A complete profile backup is waiting for post-restart commit/rollback. */
        val pendingValidation: Boolean = false,
        /** Packages whose loader rows were disabled because they import removed DSH APIs. */
        val skippedPackages: List<String> = emptyList(),
    )

    private val isWindows: Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    /** True when the main home has a web-profile manifest worth syncing. */
    fun hasPlugins(mainHome: Path): Boolean =
        Files.isRegularFile(mainHome.resolve("profiles").resolve(PROFILE).resolve("package.json"))

    /**
     * Copies the main home's web-profile plugin manifests into [targetHome] and
     * materializes their dependencies with pnpm. Returns the outcome; the log
     * callback receives every file action and captured pnpm output line.
     */
    fun sync(mainHome: Path, targetHome: Path, log: (String) -> Unit): Result {
        val sourceDir = mainHome.resolve("profiles").resolve(PROFILE)
        val targetDir = targetHome.resolve("profiles").resolve(PROFILE)
        if (!Files.isRegularFile(sourceDir.resolve("package.json"))) {
            return Result(changed = false)
        }

        val profilesDir = targetHome.resolve("profiles")
        val backupDir = profilesDir.resolve(BACKUP_NAME)
        val changed = SYNC_FILES.any { name ->
            val source = sourceDir.resolve(name)
            Files.isRegularFile(source) && !sameSyncContent(name, source, targetDir.resolve(name))
        }
        if (!changed) return Result(changed = false)

        try {
            // An interrupted previous transaction always prefers its last-known-good profile.
            if (Files.exists(backupDir)) {
                deleteTree(targetDir)
                Files.move(backupDir, targetDir)
                log("Recovered interrupted plugin sync: $targetDir")
            }
            if (Files.exists(targetDir)) Files.move(targetDir, backupDir)
            Files.createDirectories(targetDir)
            for (name in SYNC_FILES) {
                val source = sourceDir.resolve(name)
                if (!Files.isRegularFile(source)) continue
                val dest = targetDir.resolve(name)
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
                log("Copied: $source -> $dest")
            }

            val installError = install(targetDir, log)
            if (installError != null) {
                rollback(targetHome, log)
                return Result(changed = changed, error = installError)
            }
            val compatibility = DshPluginCompatibility.apply(targetDir, log)
            return Result(
                changed = true,
                pendingValidation = true,
                skippedPackages = compatibility.packages,
            )
        } catch (error: Exception) {
            rollback(targetHome, log)
            return Result(
                changed = changed,
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // pnpm install
    // ---------------------------------------------------------------------------------------------

    /**
     * Runs `pnpm install` in the profile directory. On Windows the command goes
     * through `cmd.exe /c` because pnpm shims are batch scripts and cannot be
     * exec'd reliably from ProcessBuilder (same pitfall as the dsh launcher).
     */
    private fun install(profileDir: Path, log: (String) -> Unit): String? {
        val pnpm = findOnPath() ?: return ERROR_PNPM_NOT_FOUND

        val builder = if (isWindows) {
            ProcessBuilder("cmd.exe", "/d", "/s", "/c", "\"" + quoteCmd(pnpm) + " install\"")
        } else {
            ProcessBuilder(pnpm, "install")
        }
        builder.directory(profileDir.toFile())
        builder.redirectErrorStream(true)

        val process = try {
            builder.start()
        } catch (error: IOException) {
            return "pnpm failed to start: ${error.message}"
        }

        log("Running: pnpm install (in $profileDir)")

        val lines = mutableListOf<String>()
        val pump = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(lines) {
                            if (lines.size < MAX_CAPTURED_LINES) lines += line
                        }
                    }
                }
            } catch (ignored: IOException) {
                // stream closed by process exit
            }
        }, "DeepSeekHarness-pnpm-install").apply {
            isDaemon = true
            start()
        }

        val finished = try {
            process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            runCatching { process.destroyForcibly() }
            pump.join(5_000)
            return "pnpm install timed out after ${INSTALL_TIMEOUT_MINUTES} minutes"
        }
        pump.join(5_000)

        val captured = synchronized(lines) { ArrayList(lines) }
        captured.forEach(log)

        val exitCode = runCatching { process.exitValue() }.getOrDefault(1)
        if (exitCode != 0) {
            val tail = captured.takeLast(20).joinToString("\n")
            return "pnpm install exited with code $exitCode\n$tail"
        }
        return null
    }

    private fun findOnPath(): String? {
        val names = if (isWindows) listOf("pnpm.cmd", "pnpm.exe", "pnpm") else listOf("pnpm")
        val pathVar = System.getenv("PATH") ?: return null
        for (dir in pathVar.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            for (name in names) {
                val file = File(dir, name)
                if (file.isFile) return file.absolutePath
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // File helpers
    // ---------------------------------------------------------------------------------------------

    private fun sameContent(source: Path, dest: Path): Boolean =
        Files.exists(dest) && runCatching {
            Files.readAllBytes(source).contentEquals(Files.readAllBytes(dest))
        }.getOrDefault(false)

    private fun sameSyncContent(name: String, source: Path, dest: Path): Boolean {
        if (name != "cordis.patch.yml") return sameContent(source, dest)
        if (!Files.isRegularFile(dest)) return false
        return runCatching {
            DshPluginCompatibility.sourcePatchContent(Files.readString(source)) ==
                DshPluginCompatibility.sourcePatchContent(Files.readString(dest))
        }.getOrDefault(false)
    }

    /** Restores the complete pre-sync profile, including its installed dependency tree. */
    fun rollback(targetHome: Path, log: (String) -> Unit) {
        val profiles = targetHome.resolve("profiles")
        val target = profiles.resolve(PROFILE)
        val backup = profiles.resolve(BACKUP_NAME)
        runCatching {
            deleteTree(target)
            if (Files.exists(backup)) Files.move(backup, target)
            log("Rolled back incompatible plugin sync: $target")
        }.onFailure { log("Plugin sync rollback failed for $target: ${it.message}") }
    }

    fun commit(targetHome: Path, log: (String) -> Unit) {
        val backup = targetHome.resolve("profiles").resolve(BACKUP_NAME)
        runCatching {
            deleteTree(backup)
            log("Plugin sync compatibility confirmed; backup removed: $backup")
        }.onFailure { log("Plugin sync backup cleanup failed: ${it.message}") }
    }

    /** Preserve a currently broken profile for inspection and allow defaults to seed on retry. */
    fun quarantineIncompatibleProfile(targetHome: Path, log: (String) -> Unit): Path? = runCatching {
        val profiles = targetHome.resolve("profiles")
        val web = profiles.resolve(PROFILE)
        if (!Files.exists(web)) return null
        var quarantine = profiles.resolve("$PROFILE.dsh-ide-incompatible-bak")
        var suffix = 2
        while (Files.exists(quarantine)) quarantine = profiles.resolve("$PROFILE.dsh-ide-incompatible-bak-$suffix").also { suffix++ }
        Files.move(web, quarantine)
        log("Quarantined incompatible plugin profile: $web -> $quarantine")
        quarantine
    }.getOrNull()

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun quoteCmd(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""
}

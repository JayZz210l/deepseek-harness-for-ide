package com.deepseek.dsh.ide.process

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
 * Failure safety: every overwritten destination is backed up first and
 * restored when the install fails, so a broken manifest from the main home
 * can never leave the IDE instance unbootable.
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

    private const val BACKUP_SUFFIX = ".dsh-ide-sync-bak"
    private const val INSTALL_TIMEOUT_MINUTES = 5L
    private const val MAX_CAPTURED_LINES = 500

    data class Result(
        /** True when at least one manifest file was copied into the isolated home. */
        val changed: Boolean,
        /** Human-readable failure, [ERROR_PNPM_NOT_FOUND], or null on success. */
        val error: String? = null,
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

        val backups = mutableListOf<Pair<Path, Path>>()
        val created = mutableListOf<Path>()
        var changed = false
        try {
            Files.createDirectories(targetDir)
            for (name in SYNC_FILES) {
                val source = sourceDir.resolve(name)
                if (!Files.isRegularFile(source)) continue
                val dest = targetDir.resolve(name)
                if (sameContent(source, dest)) continue

                if (Files.exists(dest)) {
                    val backup = targetDir.resolve(name + BACKUP_SUFFIX)
                    Files.copy(dest, backup, StandardCopyOption.REPLACE_EXISTING)
                    backups.add(dest to backup)
                } else {
                    created.add(dest)
                }
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
                changed = true
                log("Copied: $source -> $dest")
            }

            val installError = install(targetDir, log)
            if (installError != null) {
                rollback(backups, created, log)
                return Result(changed = changed, error = installError)
            }

            backups.forEach { (_, backup) -> runCatching { Files.deleteIfExists(backup) } }
            return Result(changed = changed)
        } catch (error: Exception) {
            rollback(backups, created, log)
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

    /**
     * Returns the target profile directory to its pre-sync state: overwritten
     * files are restored from backups, and files that did not exist before are
     * removed again (a half-installed manifest would fail the next DSH boot).
     */
    private fun rollback(
        backups: List<Pair<Path, Path>>,
        created: List<Path>,
        log: (String) -> Unit,
    ) {
        for ((dest, backup) in backups) {
            runCatching {
                Files.copy(backup, dest, StandardCopyOption.REPLACE_EXISTING)
                Files.deleteIfExists(backup)
                log("Restored: $dest")
            }.onFailure { log("Restore failed for $dest: ${it.message}") }
        }
        for (dest in created) {
            runCatching {
                Files.deleteIfExists(dest)
                log("Removed incomplete sync file: $dest")
            }.onFailure { log("Cleanup failed for $dest: ${it.message}") }
        }
    }

    private fun quoteCmd(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""
}

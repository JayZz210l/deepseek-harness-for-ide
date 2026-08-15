package com.deepseek.dsh.ide.process

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * "Restore default plugins" for the project's isolated DSH home: remove the
 * synced `profiles/web` directory so DSH re-initializes the shipped default
 * web profile on its next boot (`loadProfile` recreates `package.json`,
 * `cordis.patch.yml` and `pnpm-workspace.yaml` from the profile template).
 *
 * The removal is a rename, not a delete-first: `profiles/web` becomes
 * `profiles/web.dsh-ide-reset-bak`, so the caller can atomically restore it
 * when the post-reset restart fails. Pure JDK, testable outside the IDE.
 */
object DshPluginReset {

    private const val PROFILE = "web"
    const val BACKUP_NAME = "$PROFILE.dsh-ide-reset-bak"

    data class Outcome(
        /** True when a profile directory existed and was moved aside. */
        val removed: Boolean,
        /** Human-readable failure, or null on success. */
        val error: String? = null,
    )

    fun hasWebProfile(projectHome: Path): Boolean =
        Files.exists(projectHome.resolve("profiles").resolve(PROFILE))

    /** Renames `<home>/profiles/web` to the backup name; returns the outcome. */
    fun removeWebProfile(projectHome: Path, log: (String) -> Unit): Outcome {
        val profiles = projectHome.resolve("profiles")
        val web = profiles.resolve(PROFILE)
        val backup = profiles.resolve(BACKUP_NAME)
        return try {
            if (!Files.exists(web)) return Outcome(removed = false)
            deleteTree(backup) // stale backup from an interrupted reset
            Files.createDirectories(profiles)
            Files.move(web, backup)
            log("Moved plugin profile aside: $web -> $backup")
            Outcome(removed = true)
        } catch (error: Exception) {
            Outcome(
                removed = Files.exists(backup),
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    /** Deletes the backup once the post-reset restart has proven successful. */
    fun discardBackup(projectHome: Path, log: (String) -> Unit) {
        val backup = projectHome.resolve("profiles").resolve(BACKUP_NAME)
        deleteTree(backup)
        log("Default plugin profile confirmed; backup removed: $backup")
    }

    /**
     * Puts the previous synced profile back: a partially re-initialized
     * `web` directory is removed and the backup renamed into its place.
     */
    fun restoreBackup(projectHome: Path, log: (String) -> Unit) {
        val profiles = projectHome.resolve("profiles")
        val web = profiles.resolve(PROFILE)
        val backup = profiles.resolve(BACKUP_NAME)
        if (!Files.exists(backup)) return
        deleteTree(web)
        runCatching {
            Files.move(backup, web)
            log("Restored previous plugin profile: $web")
        }.onFailure { log("Restore of the plugin profile failed: ${it.message}") }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

package com.deepseek.dsh.ide.process

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

/**
 * One-way sync of locally authored agent presets from the user's main DSH
 * home into the project's isolated home. Pure JDK, testable outside the IDE.
 *
 * DSH stores user presets as directories under `<DSH_HOME>/.agent-presets`
 * (each directory = one preset: `cordis.yml` plus optional `preset.yml`
 * metadata and assets). Preset discovery re-reads its roots on every call,
 * so unlike the plugin-list sync this copy needs no service restart — the
 * presets appear in the picker as soon as the copy finishes.
 *
 * Semantics: one-way. A preset directory that exists in both homes is
 * replaced by the main-home copy; presets that exist only in the isolated
 * home are left untouched. Replacing is atomic per preset (rename old →
 * backup, copy new, delete backup), and a failure anywhere rolls every
 * touched preset back to its pre-sync state.
 */
object DshPresetSync {

    /** Harness-home directory holding locally authored presets (`dsh-agent-presets`). */
    const val USER_PRESET_DIR = ".agent-presets"

    private const val BACKUP_SUFFIX = ".dsh-ide-sync-bak"

    data class Result(
        /** True when at least one preset directory was copied. */
        val changed: Boolean,
        /** Human-readable failure, or null on success. */
        val error: String? = null,
    )

    /** True when the main home has at least one preset directory to sync. */
    fun hasPresets(mainHome: Path): Boolean {
        val root = mainHome.resolve(USER_PRESET_DIR)
        if (!Files.isDirectory(root)) return false
        return Files.list(root).use { stream ->
            stream.anyMatch { Files.isDirectory(it) }
        }
    }

    /**
     * Copies every preset directory from `<mainHome>/.agent-presets` into
     * `<targetHome>/.agent-presets`. Returns the outcome; the log callback
     * receives one line per file-level action.
     */
    fun sync(mainHome: Path, targetHome: Path, log: (String) -> Unit): Result {
        val sourceRoot = mainHome.resolve(USER_PRESET_DIR)
        val targetRoot = targetHome.resolve(USER_PRESET_DIR)
        if (!Files.isDirectory(sourceRoot)) return Result(changed = false)

        val presets = Files.list(sourceRoot).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .sorted(Comparator.comparing { it.fileName.toString() })
                .toList()
        }
        if (presets.isEmpty()) return Result(changed = false)

        val backedUp = mutableListOf<Pair<Path, Path>>() // dest -> renamed original
        val created = mutableListOf<Path>()
        var changed = false
        try {
            Files.createDirectories(targetRoot)
            for (preset in presets) {
                val name = preset.fileName.toString()
                val dest = targetRoot.resolve(name)
                if (sourceAlreadySynced(preset, dest)) continue
                if (Files.exists(dest)) {
                    val backup = targetRoot.resolve(name + BACKUP_SUFFIX)
                    deleteTree(backup) // stale backup from an interrupted run
                    Files.move(dest, backup)
                    backedUp.add(dest to backup)
                    log("Backed up existing preset: $dest")
                } else {
                    created.add(dest)
                }
                copyTree(preset, dest)
                changed = true
                log("Synced preset: $name -> $dest")
            }

            backedUp.forEach { (_, backup) -> deleteTree(backup) }
            return Result(changed = changed)
        } catch (error: Exception) {
            // Put every touched preset back: incomplete new copies go away,
            // renamed originals return to their place.
            for (dest in created) deleteTree(dest)
            for ((dest, backup) in backedUp) {
                deleteTree(dest)
                runCatching { Files.move(backup, dest) }
                    .onFailure { log("Preset rollback failed for $dest: ${it.message}") }
            }
            return Result(
                changed = changed,
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // File-tree helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * True when every file in the source preset already exists under [dest]
     * with identical bytes. Extra files in [dest] are deliberately ignored:
     * this sync is one-way and never deletes preset-local additions.
     */
    private fun sourceAlreadySynced(source: Path, dest: Path): Boolean =
        Files.isDirectory(dest) && Files.walk(source).use { paths ->
            paths.allMatch { path ->
                val target = dest.resolve(source.relativize(path).toString())
                when {
                    Files.isDirectory(path) -> Files.isDirectory(target)
                    else -> Files.isRegularFile(target) && runCatching {
                        Files.readAllBytes(path).contentEquals(Files.readAllBytes(target))
                    }.getOrDefault(false)
                }
            }
        }

    private fun copyTree(source: Path, dest: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = dest.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

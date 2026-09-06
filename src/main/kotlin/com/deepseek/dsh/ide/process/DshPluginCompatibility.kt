package com.deepseek.dsh.ide.process

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Filters plugin loader rows that statically import APIs removed in DSH 0.1.2. */
object DshPluginCompatibility {

    private const val MANAGED_START = "# dsh-ide compatibility filters: start"
    private const val MANAGED_END = "# dsh-ide compatibility filters: end"
    private val REMOVED_API_MARKERS = listOf(
        "installSettingsSection",
        "settingsNamespace",
        "@deepseek-ai/dsh-host-apiproxy",
    )

    data class Result(val packages: List<String>, val loaderIds: List<String>)

    /** Removes the generated compatibility layer for manifest comparisons. */
    fun sourcePatchContent(value: String): String = value
        .substringBefore(MANAGED_START)
        .lineSequence()
        .filterNot { it.trim() == "[]" }
        .joinToString("\n")
        .trim()

    fun apply(profileDir: Path, log: (String) -> Unit): Result {
        val modules = profileDir.resolve("node_modules")
        if (!Files.isDirectory(modules)) return Result(emptyList(), emptyList())
        val packageDirs = packageDirectories(modules)
        val incompatible = packageDirs.mapNotNull { dir ->
            val name = packageName(dir) ?: return@mapNotNull null
            if (usesRemovedApi(dir)) name else null
        }.toSortedSet()
        if (incompatible.isEmpty()) return Result(emptyList(), emptyList())

        val ids = linkedSetOf<String>()
        for (dir in packageDirs) {
            val patch = dir.resolve("cordis.patch.yml")
            if (!Files.isRegularFile(patch)) continue
            var pendingId: String? = null
            for (line in Files.readAllLines(patch, StandardCharsets.UTF_8)) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("- id:") -> pendingId = scalar(trimmed.substringAfter(':'))
                    trimmed.startsWith("id:") -> pendingId = scalar(trimmed.substringAfter(':'))
                    trimmed.startsWith("name:") -> {
                        val name = scalar(trimmed.substringAfter(':'))
                        val id = pendingId
                        if (id != null && incompatible.any { name == it || name.startsWith("$it/") }) ids += id
                        pendingId = null
                    }
                }
            }
        }

        if (ids.isNotEmpty()) {
            val profilePatch = profileDir.resolve("cordis.patch.yml")
            val original = if (Files.isRegularFile(profilePatch)) Files.readString(profilePatch) else ""
            val base = sourcePatchContent(original)
            val managed = buildString {
                append(MANAGED_START).append('\n')
                append("# Rows below use DSH APIs removed in 0.1.2; compatible rows remain enabled.\n")
                for (id in ids) append("- id: '").append(id.replace("'", "''")).append("'\n  disabled: true\n")
                append(MANAGED_END).append('\n')
            }
            Files.writeString(profilePatch, listOf(base, managed).filter { it.isNotBlank() }.joinToString("\n") + "\n")
        }
        log("Compatibility filter skipped packages: ${incompatible.joinToString(", ")}")
        if (ids.isNotEmpty()) log("Disabled incompatible loader rows: ${ids.joinToString(", ")}")
        return Result(incompatible.toList(), ids.toList())
    }

    private fun packageDirectories(modules: Path): List<Path> {
        val result = mutableListOf<Path>()
        Files.list(modules).use { entries ->
            entries.filter { Files.isDirectory(it) && it.fileName.toString() != ".pnpm" }.forEach { entry ->
                if (entry.fileName.toString().startsWith("@")) {
                    Files.list(entry).use { scoped -> scoped.filter { Files.isDirectory(it) }.forEach(result::add) }
                } else result.add(entry)
            }
        }
        return result
    }

    private fun packageName(dir: Path): String? {
        val manifest = dir.resolve("package.json")
        if (!Files.isRegularFile(manifest)) return null
        return Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(Files.readString(manifest))?.groupValues?.get(1)
    }

    private fun usesRemovedApi(dir: Path): Boolean {
        // pnpm exposes packages through directory symlinks/junctions. Resolve
        // them before walking so Windows providers do not stop at the link.
        val lib = runCatching { dir.toRealPath().resolve("lib") }.getOrElse { dir.resolve("lib") }
        if (!Files.isDirectory(lib)) return false
        return runCatching {
            Files.walk(lib).use { files ->
                files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".js") }
                    .anyMatch { file ->
                        Files.size(file) <= 5_000_000L && REMOVED_API_MARKERS.any { Files.readString(file).contains(it) }
                    }
            }
        }.getOrDefault(false)
    }

    private fun scalar(value: String): String = value.trim().removeSurrounding("'").removeSurrounding("\"")
}

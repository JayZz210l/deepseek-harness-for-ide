package com.deepseek.dsh.ide.process

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/**
 * Resolves the DSH home for the plugin-managed `dsh web` instance and keeps an
 * isolated home seeded with the user's existing credentials and settings.
 *
 * Why an isolated home by default: `dsh web` instances sharing one DSH home are
 * not multi-instance safe (concurrent session/workspace/config writers), and the
 * previous "blank = inherit the IDE environment" default pointed the embedded
 * instance at the same `~/.dsh` the user's standalone web UI uses. That shared
 * home mixed workspaces and sessions between the two instances, orphaned the
 * IDE session when the process was stopped, and left the standalone instance
 * re-bootstrapped from scratch — what the user experienced as "the web UI lost
 * all of its configuration".
 *
 * The isolated home lives under
 * `%LOCALAPPDATA%\deepseek-harness-jetbrains\dsh-home\<project>-<hash>` — one
 * directory per project, matching the one-instance-per-project process model.
 *
 * Inheritance is strictly one-way and additive: the main home's
 * `.credentials.yaml` and `settings.yaml` are copied in when they exist and
 * differ, nothing is ever written back, and a source file that disappears does
 * NOT delete the destination — so the plugin can never clear the main config.
 */
object DshHomePolicy {

    /** Literal override value that means "inherit the IDE environment" (the old sharing behavior). */
    const val INHERIT = "default"

    private const val CREDENTIALS_FILE = ".credentials.yaml"
    private const val SETTINGS_FILE = "settings.yaml"

    /** Plugin-owned isolated home root (next to the native bridge files). */
    fun isolatedRoot(): Path = Paths.get(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
        "deepseek-harness-jetbrains",
        "dsh-home",
    )

    /**
     * The DSH_HOME value to assign to the spawned process:
     * - blank override → a per-project isolated directory under [isolatedRoot];
     * - [INHERIT] → null, i.e. inherit the IDE environment (opt-in sharing);
     * - any other non-blank value → that path, used verbatim.
     */
    fun resolveHome(override: String, projectBasePath: String?): String? {
        val trimmed = override.trim()
        if (trimmed.equals(INHERIT, ignoreCase = true)) return null
        if (trimmed.isNotEmpty()) return trimmed
        val key = projectBasePath?.let(::projectKeyOf) ?: "global"
        return isolatedRoot().resolve(key).toString()
    }

    /**
     * Seeds a resolved (non-main) home before spawn. Read-only w.r.t. the main
     * home: only files that exist there are copied, and the plugin can never
     * wipe or overwrite anything on the main home.
     *
     * Credentials are copied in the direction "main home wins": API keys are
     * managed centrally in the standalone harness, so a changed key must reach
     * the IDE instance.
     *
     * Settings are merged ADDITIVELY instead: sections and keys the isolated
     * home already stores are never rewritten. The previous copy-if-different
     * behavior made the main home's document authoritative on every start, which
     * silently reverted choices the user made in the embedded web UI — the
     * reported "my language setting is gone after every IDE restart". A
     * still-present legacy `settings.yaml` is skipped once this home migrated to
     * the DSH 0.1.7 profile document (`settings.yaml.imported` marker), because
     * DSH imports every re-appearing legacy document over the live profile
     * configuration.
     */
    fun seedHome(home: Path, log: (String) -> Unit) {
        runCatching { Files.createDirectories(home) }.onFailure {
            log("DSH home preparation failed: ${it.message}")
            return
        }
        val main = mainHome()
        if (isSameDirectory(main, home)) return // override points at the main home: nothing to copy
        copyIfChanged(main.resolve(CREDENTIALS_FILE), home.resolve(CREDENTIALS_FILE), log)
        val mainSettings = main.resolve(SETTINGS_FILE)
        if (Files.exists(home.resolve("$SETTINGS_FILE.imported"))) {
            if (Files.exists(mainSettings)) {
                log("DSH home settings already migrated into the profile document; not re-seeding $SETTINGS_FILE")
            }
            return
        }
        mergeSettingsMissing(mainSettings, home.resolve(SETTINGS_FILE), log)
    }

    /** The main user DSH home (`$DSH_HOME` of this process, else `~/.dsh`). */
    fun mainHome(): Path {
        val env = System.getenv("DSH_HOME")
        if (!env.isNullOrBlank()) return Paths.get(env.trim())
        return Paths.get(System.getProperty("user.home") ?: ".", ".dsh")
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private fun copyIfChanged(source: Path, target: Path, log: (String) -> Unit) {
        val sourceBytes = runCatching { Files.readAllBytes(source) }.getOrNull() ?: return
        val targetExists = Files.exists(target)
        val differs = !targetExists || runCatching {
            !sourceBytes.contentEquals(Files.readAllBytes(target))
        }.getOrDefault(true)
        if (!differs) return
        runCatching {
            Files.createDirectories(target.parent)
            Files.write(target, sourceBytes)
            log("DSH home seeded: $target <- $source")
        }.onFailure { log("DSH home seeding failed for $target: ${it.message}") }
    }

    /**
     * Additive flat-mapping merge: copies every section and key of [source] that
     * [target] does not have yet, and never rewrites a value the destination
     * already stores. A missing destination is seeded verbatim so the first run
     * still inherits the user's complete configuration.
     */
    internal fun mergeSettingsMissing(source: Path, target: Path, log: (String) -> Unit) {
        val sourceText = runCatching { Files.readString(source) }.getOrNull() ?: return
        if (!Files.exists(target)) {
            runCatching {
                Files.createDirectories(target.parent)
                Files.write(target, sourceText.toByteArray(StandardCharsets.UTF_8))
                log("DSH home seeded: $target <- $source")
            }.onFailure { log("DSH home seeding failed for $target: ${it.message}") }
            return
        }
        val current = runCatching { Files.readString(target) }.getOrNull() ?: return
        val sourceSections = sectionsOf(sourceText)
        if (sourceSections.isEmpty()) return
        val currentSections = sectionsOf(current)
        val currentByHeader = currentSections.values.associateBy { it.headerIndex }
        val merged = mutableListOf<String>()
        val lines = current.lines()
        var changed = false
        for ((index, line) in lines.withIndex()) {
            merged.add(line)
            val existing = currentByHeader[index] ?: continue
            val addition = sourceSections[existing.name] ?: continue
            for (candidate in addition.body) {
                val key = memberKey(candidate) ?: continue
                if (key in existing.keys) continue
                merged.add(candidate)
                changed = true
            }
        }
        for ((name, section) in sourceSections) {
            if (name in currentSections) continue
            if (merged.isNotEmpty() && merged.last().isNotBlank()) merged.add("")
            merged.add("$name:")
            merged.addAll(section.body)
            changed = true
        }
        if (!changed) return
        runCatching {
            val text = merged.joinToString("\n").trimEnd('\n') + "\n"
            Files.write(target, text.toByteArray(StandardCharsets.UTF_8))
            log("DSH home settings merged (existing values kept): $target <- $source")
        }.onFailure { log("DSH home seeding failed for $target: ${it.message}") }
    }

    private data class Section(
        val name: String,
        val headerIndex: Int,
        val keys: Set<String>,
        val body: List<String>,
    )

    /** Maps a top-level section name to the lines it owns. */
    private fun sectionsOf(text: String): Map<String, Section> {
        val lines = text.lines()
        val result = linkedMapOf<String, Section>()
        var index = 0
        while (index < lines.size) {
            val name = topLevelKey(lines[index])
            if (name == null) {
                index++
                continue
            }
            val body = mutableListOf<String>()
            var cursor = index + 1
            while (cursor < lines.size && topLevelKey(lines[cursor]) == null) {
                val line = lines[cursor]
                if (line.isNotBlank() && !line.trimStart().startsWith("#")) body.add(line)
                cursor++
            }
            result[name] = Section(name, index, body.mapNotNull(::memberKey).toSet(), body)
            index = cursor
        }
        return result
    }

    private fun topLevelKey(line: String): String? {
        if (line.isBlank() || line.trimStart().startsWith("#")) return null
        if (line.startsWith(" ") || line.startsWith("\t") || line.startsWith("-")) return null
        return scalarKey(line)
    }

    private fun memberKey(line: String): String? {
        if (line.isBlank() || line.trimStart().startsWith("#")) return null
        if (!line.startsWith(" ") && !line.startsWith("\t")) return null
        if (line.trimStart().startsWith("-")) return null
        return scalarKey(line)
    }

    private fun scalarKey(line: String): String? {
        val trimmed = line.trim()
        val separator = trimmed.indexOf(':')
        if (separator <= 0) return null
        return trimmed.substring(0, separator).trim()
            .removeSurrounding("'")
            .removeSurrounding("\"")
            .ifBlank { null }
    }

    private fun projectKeyOf(projectBasePath: String): String {
        val normalized = projectBasePath.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)
        val base = normalized.substringAfterLast('/').ifBlank { "project" }
        val sanitized = base.replace(Regex("[^a-z0-9._-]"), "_")
        val hash = Integer.toUnsignedString(normalized.hashCode(), 16)
        return "$sanitized-$hash"
    }

    private fun isSameDirectory(a: Path, b: Path): Boolean =
        a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()
}

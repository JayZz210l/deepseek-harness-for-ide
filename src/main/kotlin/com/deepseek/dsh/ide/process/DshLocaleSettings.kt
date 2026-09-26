package com.deepseek.dsh.ide.process

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Owns the DeepSeek Harness UI language of the plugin-managed instance.
 *
 * Why this exists: the DSH browser client resolves its language from an explicit
 * `locale.preference`; when that preference is absent it delegates to
 * `navigator.languages`, which inside JCEF reports Chromium's own default rather
 * than the Windows display language. A fresh project home therefore always
 * opened in English and every user had to pick a language by hand in the web UI
 * — and had to pick it again whenever the preference was lost.
 *
 * The preference lives in different documents depending on the DSH generation:
 *
 * - up to 0.1.5 the whole user configuration is a flat mapping in
 *   `<DSH_HOME>/settings.yaml` (`locale:` section, `preference:` key);
 * - 0.1.7 moved the live configuration into the profile patch document
 *   `<DSH_HOME>/profiles/<profile>/cordis.patch.yml`, an id-targeted YAML
 *   sequence, and consumes a legacy `settings.yaml` exactly once — renaming it
 *   to `settings.yaml.imported` and writing its sections into the profile
 *   document.
 *
 * Both documents are therefore maintained while they are present: an import that
 * still carried an older value would otherwise win over the profile document on
 * the next boot, which is precisely the "my language choice is gone again"
 * report. A home that has never booted DSH 0.1.7 is seeded through the legacy
 * document, which the new storage imports by design.
 *
 * The plugin setting is authoritative only when it names a language explicitly.
 * On `auto` an existing stored preference is never rewritten, so a language
 * picked in the web UI survives every IDE restart; `auto` only decides the
 * initial value (the system language) of a home that has none yet.
 */
object DshLocaleSettings {

    /** Setting value: follow the system language. */
    const val AUTO = "auto"

    /** Setting value: always Simplified Chinese. */
    const val CHINESE = "zh"

    /** Setting value: always English. */
    const val ENGLISH = "en"

    /** Selectable setting values, in UI order. */
    val OPTIONS: List<String> = listOf(AUTO, CHINESE, ENGLISH)

    private const val LEGACY_FILE = "settings.yaml"
    private const val LEGACY_IMPORTED = "settings.yaml.imported"
    private const val LOCALE_SECTION = "locale"
    private const val PREFERENCE_KEY = "preference"
    private const val PROFILE_DIR = "profiles"
    private const val PROFILE_NAME = "web"
    private const val PROFILE_PATCH = "cordis.patch.yml"

    /** What [apply] did, for logging and tests. */
    enum class Action {
        /** First run in this home: the system language was written as the default. */
        SEEDED,

        /** The explicitly configured language was written over a different stored value. */
        APPLIED,

        /** A stored preference already matched; nothing was written. */
        UNCHANGED,

        /** The user's own stored choice was kept (`auto` never overrides it). */
        KEPT,

        /** No document could be read or written; the instance keeps whatever DSH stored. */
        FAILED,
    }

    data class Result(val language: String, val action: Action)

    /** `zh` when the system language is Chinese, `en` for every other language. */
    fun systemDefaultLanguage(): String = if (systemLanguageIsChinese()) CHINESE else ENGLISH

    /** Whether the OS/JVM locale (or the IDE's display locale) is Chinese. */
    fun systemLanguageIsChinese(): Boolean = listOf(
        Locale.getDefault(),
        Locale.getDefault(Locale.Category.DISPLAY),
        Locale.getDefault(Locale.Category.FORMAT),
    ).any { it.language.equals("zh", ignoreCase = true) }

    /** The explicit language of a setting value, or null for `auto` and unknown values. */
    fun explicitLanguage(configured: String): String? =
        when (configured.trim().lowercase(Locale.ROOT)) {
            CHINESE -> CHINESE
            ENGLISH -> ENGLISH
            else -> null
        }

    /** The language id to apply: the explicit setting, else the system-derived default. */
    fun resolve(configured: String): String = explicitLanguage(configured) ?: systemDefaultLanguage()

    /** True when this home already imported its legacy settings into the profile document. */
    fun migratedToProfileDocument(home: Path): Boolean = Files.exists(home.resolve(LEGACY_IMPORTED))

    /**
     * Applies the configured language to [home] and returns what happened.
     * Failures never propagate: a language preference must not stop a boot.
     */
    fun apply(home: Path, configured: String, log: (String) -> Unit): Result {
        val explicit = explicitLanguage(configured)
        val target = explicit ?: systemDefaultLanguage()
        val patchFile = profilePatchFile(home)
        val legacyFile = home.resolve(LEGACY_FILE)
        val profileStored = readPreference(patchFile) { readProfilePreference(it) }
        val legacyStored = readPreference(legacyFile) { readLegacyPreference(it) }
        val stored = profileStored ?: legacyStored

        return runCatching {
            when {
                explicit != null -> {
                    // The legacy document must agree even when the profile document
                    // is already correct: DSH imports it once and would overwrite.
                    val profileOk = !Files.isRegularFile(patchFile) || profileStored == target
                    val legacyOk = !Files.isRegularFile(legacyFile) || legacyStored == target
                    if (profileOk && legacyOk) {
                        Result(target, Action.UNCHANGED)
                    } else {
                        write(target, patchFile, legacyFile)
                        Result(target, Action.APPLIED)
                    }
                }

                stored != null -> Result(stored, Action.KEPT)

                else -> {
                    write(target, patchFile, legacyFile)
                    Result(target, Action.SEEDED)
                }
            }
        }.getOrElse { error ->
            log("DSH language preference update failed for $home: ${error.message ?: error.javaClass.simpleName}")
            Result(stored ?: target, Action.FAILED)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Document I/O
    // ---------------------------------------------------------------------------------------------

    private fun profilePatchFile(home: Path): Path =
        home.resolve(PROFILE_DIR).resolve(PROFILE_NAME).resolve(PROFILE_PATCH)

    private fun readPreference(file: Path, reader: (String) -> String?): String? = runCatching {
        if (!Files.isRegularFile(file)) return@runCatching null
        reader(Files.readString(file, StandardCharsets.UTF_8))
    }.getOrNull()

    /**
     * Writes [language] into every settings document this home actually uses.
     * The profile patch is the live document of DSH 0.1.7+; a still-present
     * legacy `settings.yaml` is kept in sync because DSH imports it exactly once.
     * A home with neither has not booted yet: the legacy document is created and
     * the new storage imports it.
     */
    private fun write(language: String, patchFile: Path, legacyFile: Path) {
        var written = false
        if (Files.isRegularFile(patchFile)) {
            val text = Files.readString(patchFile, StandardCharsets.UTF_8)
            val next = upsertProfilePreference(text, language)
            if (next != text) Files.writeString(patchFile, next, StandardCharsets.UTF_8)
            written = true
        }
        if (Files.isRegularFile(legacyFile)) {
            val text = Files.readString(legacyFile, StandardCharsets.UTF_8)
            val next = upsertLegacyPreference(text, language)
            if (next != text) Files.writeString(legacyFile, next, StandardCharsets.UTF_8)
            written = true
        }
        if (!written) {
            Files.createDirectories(legacyFile.parent)
            Files.writeString(legacyFile, upsertLegacyPreference("", language), StandardCharsets.UTF_8)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Legacy settings.yaml (`locale:` section, `preference:` key)
    // ---------------------------------------------------------------------------------------------

    /** Reads `locale.preference` out of a flat `settings.yaml` mapping. */
    fun readLegacyPreference(text: String): String? {
        val lines = text.lines()
        val sectionStart = lines.indexOfFirst { topLevelKey(it) == LOCALE_SECTION }
        if (sectionStart < 0) return null
        val sectionEnd = sectionEnd(lines, sectionStart + 1)
        for (index in sectionStart + 1 until sectionEnd) {
            if (indentedKey(lines[index]) == PREFERENCE_KEY) {
                return unquote(lines[index].substringAfter(':').trim()).ifBlank { null }
            }
        }
        return null
    }

    /** Sets `locale.preference`, creating the section when the document has none. */
    fun upsertLegacyPreference(text: String, language: String): String {
        val lines = text.lines().toMutableList()
        dropTrailingBlank(lines)
        val sectionStart = lines.indexOfFirst { topLevelKey(it) == LOCALE_SECTION }
        if (sectionStart < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("$LOCALE_SECTION:")
            lines.add("  $PREFERENCE_KEY: $language")
            return lines.joinToString("\n") + "\n"
        }
        val sectionEnd = sectionEnd(lines, sectionStart + 1)
        val preferenceIndex = (sectionStart + 1 until sectionEnd)
            .firstOrNull { indentedKey(lines[it]) == PREFERENCE_KEY }
        if (preferenceIndex != null) {
            val indent = indentOf(lines[preferenceIndex])
            lines[preferenceIndex] = "$indent$PREFERENCE_KEY: $language"
        } else {
            val indent = (sectionStart + 1 until sectionEnd)
                .firstOrNull { lines[it].isNotBlank() && !isComment(lines[it]) }
                ?.let { indentOf(lines[it]) }
                ?.takeIf { it.isNotEmpty() }
                ?: "  "
            lines.add(sectionStart + 1, "$indent$PREFERENCE_KEY: $language")
        }
        return lines.joinToString("\n") + "\n"
    }

    // ---------------------------------------------------------------------------------------------
    // Profile patch cordis.patch.yml (id-targeted YAML sequence)
    // ---------------------------------------------------------------------------------------------

    /** Reads `preference` from the document's `- id: locale` row. */
    fun readProfilePreference(text: String): String? {
        val lines = text.lines()
        val start = localeRowStart(lines) ?: return null
        val end = rowEnd(lines, start)
        val configIndex = (start until end).firstOrNull { rowLevelKey(lines[it]) == "config" } ?: return null
        val inline = lines[configIndex].substringAfter(':', "").trim()
        if (inline.isNotEmpty()) {
            return inlineFlowValue(inline, PREFERENCE_KEY)
        }
        val blockEnd = configBlockEnd(lines, configIndex, end)
        for (index in configIndex + 1 until blockEnd) {
            if (keyOf(lines[index]) == PREFERENCE_KEY) {
                return unquote(lines[index].substringAfter(':').trim()).ifBlank { null }
            }
        }
        return null
    }

    /** Sets `preference` inside the `- id: locale` row, creating the row when absent. */
    fun upsertProfilePreference(text: String, language: String): String {
        val lines = text.lines().toMutableList()
        dropTrailingBlank(lines)
        val start = localeRowStart(lines)
        if (start == null) {
            val row = listOf("- id: $LOCALE_SECTION", "  config:", "    $PREFERENCE_KEY: $language")
            val empty = lines.indexOfFirst { it.trim() == "[]" }
            if (empty >= 0) {
                lines.removeAt(empty)
                lines.addAll(empty, row)
            } else {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                lines.addAll(row)
            }
            return lines.joinToString("\n") + "\n"
        }
        val end = rowEnd(lines, start)
        val configIndex = (start until end).firstOrNull { rowLevelKey(lines[it]) == "config" }
        if (configIndex == null) {
            lines.add(start + 1, "  config:")
            lines.add(start + 2, "    $PREFERENCE_KEY: $language")
            return lines.joinToString("\n") + "\n"
        }
        val inline = lines[configIndex].substringAfter(':', "").trim()
        if (inline.isNotEmpty()) {
            val entries = inline.removeSurrounding("{", "}").split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && keyOf(it) != PREFERENCE_KEY }
            lines[configIndex] = "  config: { ${(entries + "$PREFERENCE_KEY: $language").joinToString(", ")} }"
            return lines.joinToString("\n") + "\n"
        }
        val blockEnd = configBlockEnd(lines, configIndex, end)
        val preferenceIndex = (configIndex + 1 until blockEnd)
            .firstOrNull { keyOf(lines[it]) == PREFERENCE_KEY }
        if (preferenceIndex != null) {
            val indent = indentOf(lines[preferenceIndex])
            lines[preferenceIndex] = "$indent$PREFERENCE_KEY: $language"
        } else {
            val indent = (configIndex + 1 until blockEnd)
                .firstOrNull { lines[it].isNotBlank() && !isComment(lines[it]) }
                ?.let { indentOf(lines[it]) }
                ?.takeIf { it.isNotEmpty() }
                ?: "    "
            lines.add(configIndex + 1, "$indent$PREFERENCE_KEY: $language")
        }
        return lines.joinToString("\n") + "\n"
    }

    // ---------------------------------------------------------------------------------------------
    // YAML line helpers
    // ---------------------------------------------------------------------------------------------

    private fun dropTrailingBlank(lines: MutableList<String>) {
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
    }

    private fun isComment(line: String): Boolean = line.trimStart().startsWith("#")

    private fun indentOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

    /** Whether the line is a top-level mapping key (column 0, not a comment or sequence item). */
    private fun topLevelKey(line: String): String? {
        if (line.isBlank() || isComment(line)) return null
        if (line.startsWith(" ") || line.startsWith("\t") || line.startsWith("-")) return null
        return keyOf(line)
    }

    /** Whether the line is an indented mapping key (a section member). */
    private fun indentedKey(line: String): String? {
        if (line.isBlank() || isComment(line)) return null
        if (!line.startsWith(" ") && !line.startsWith("\t")) return null
        val trimmed = line.trim()
        if (trimmed.startsWith("-")) return null
        return keyOf(line)
    }

    /** A row-level key of a sequence item, e.g. `config:` in `  config:`. */
    private fun rowLevelKey(line: String): String? {
        if (line.isBlank() || isComment(line) || indentOf(line).length != 2) return null
        val trimmed = line.trim()
        if (trimmed.startsWith("-")) return null
        return keyOf(line)
    }

    /** The key of a `key: value` line, or null when the line carries no key. */
    private fun keyOf(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.startsWith("-") || trimmed.isEmpty()) return null
        val index = trimmed.indexOf(':')
        if (index <= 0) return null
        return unquote(trimmed.substring(0, index).trim())
    }

    /** Index one past the last line of the top-level section starting at [from]. */
    private fun sectionEnd(lines: List<String>, from: Int): Int {
        for (index in from until lines.size) {
            val line = lines[index]
            if (line.isBlank() || isComment(line)) continue
            if (indentOf(line).isEmpty()) return index
        }
        return lines.size
    }

    /** Start index of the last top-level `- ` row of the document. */
    private fun isRowStart(line: String): Boolean =
        line.startsWith("- ") || line.trimEnd() == "-"

    private fun rowEnd(lines: List<String>, start: Int): Int {
        for (index in start + 1 until lines.size) {
            if (isRowStart(lines[index])) return index
        }
        return lines.size
    }

    /** The `id` of the `- id: locale` row, or null when the document has none. */
    private fun localeRowStart(lines: List<String>): Int? {
        var found: Int? = null
        var index = 0
        while (index < lines.size) {
            if (!isRowStart(lines[index])) {
                index++
                continue
            }
            val end = rowEnd(lines, index)
            if (rowId(lines, index, end) == LOCALE_SECTION) found = index
            index = end
        }
        return found
    }

    /** Reads a row's own `id:`; nested `insert:` children are not row ids. */
    private fun rowId(lines: List<String>, start: Int, end: Int): String? {
        val inline = lines[start].removePrefix("-").trim()
        if (inline.isNotEmpty()) {
            // `- insert:` and friends are not addressable rows.
            if (keyOf(inline) != "id") return null
            return unquote(inline.substringAfter(':').trim()).ifBlank { null }
        }
        for (index in start + 1 until end) {
            val line = lines[index]
            if (line.isBlank() || isComment(line)) continue
            if (indentOf(line).length != 2) continue
            if (keyOf(line) == "id") return unquote(line.substringAfter(':').trim()).ifBlank { null }
        }
        return null
    }

    /** Index one past the last line of a `config:` block. */
    private fun configBlockEnd(lines: List<String>, configIndex: Int, rowEnd: Int): Int {
        for (index in configIndex + 1 until rowEnd) {
            val line = lines[index]
            if (line.isBlank() || isComment(line)) continue
            if (indentOf(line).length <= 2) return index
        }
        return rowEnd
    }

    private fun inlineFlowValue(inline: String, key: String): String? {
        val inner = inline.removeSurrounding("{", "}")
        return inner.split(',').map { it.trim() }
            .firstOrNull { keyOf(it) == key }
            ?.substringAfter(':')
            ?.let { unquote(it.trim()) }
            ?.ifBlank { null }
    }

    private fun unquote(value: String): String =
        value.removeSurrounding("'").removeSurrounding("\"").trim()
}

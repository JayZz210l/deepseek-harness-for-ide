package com.deepseek.dsh.ide.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DshLocaleSettingsTest {

    // ---------------------------------------------------------------------------------------------
    // Setting resolution
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `explicit setting wins over the system language`() {
        assertEquals(DshLocaleSettings.CHINESE, DshLocaleSettings.resolve("zh"))
        assertEquals(DshLocaleSettings.ENGLISH, DshLocaleSettings.resolve("EN"))
        assertEquals(DshLocaleSettings.CHINESE, DshLocaleSettings.resolve(" zh "))
    }

    @Test
    fun `auto resolves to the system default`() {
        assertEquals(DshLocaleSettings.systemDefaultLanguage(), DshLocaleSettings.resolve("auto"))
        assertEquals(DshLocaleSettings.systemDefaultLanguage(), DshLocaleSettings.resolve(""))
        assertEquals(DshLocaleSettings.systemDefaultLanguage(), DshLocaleSettings.resolve("nonsense"))
    }

    @Test
    fun `only zh and en are explicit values`() {
        assertEquals(DshLocaleSettings.CHINESE, DshLocaleSettings.explicitLanguage("zh"))
        assertEquals(DshLocaleSettings.ENGLISH, DshLocaleSettings.explicitLanguage("en"))
        assertNull(DshLocaleSettings.explicitLanguage("auto"))
        assertNull(DshLocaleSettings.explicitLanguage(""))
        assertNull(DshLocaleSettings.explicitLanguage("fr"))
    }

    @Test
    fun `option list is ordered auto first`() {
        assertEquals(listOf("auto", "zh", "en"), DshLocaleSettings.OPTIONS)
    }

    // ---------------------------------------------------------------------------------------------
    // Legacy settings.yaml
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `legacy writer creates the locale section`() {
        val result = DshLocaleSettings.upsertLegacyPreference("", "zh")
        assertEquals("locale:\n  preference: zh\n", result)
        assertEquals("zh", DshLocaleSettings.readLegacyPreference(result))
    }

    @Test
    fun `legacy writer keeps other sections and comments`() {
        val before = """
            # DeepSeek Harness user settings
            locale:
              preference: en
            agent-default-model:
              provider: deepseek-official
        """.trimIndent() + "\n"

        val after = DshLocaleSettings.upsertLegacyPreference(before, "zh")

        assertTrue(after.contains("# DeepSeek Harness user settings"))
        assertTrue(after.contains("agent-default-model:\n  provider: deepseek-official"))
        assertEquals("zh", DshLocaleSettings.readLegacyPreference(after))
        // Every other byte stays where it was.
        assertEquals(before.replace("preference: en", "preference: zh"), after)
    }

    @Test
    fun `legacy writer adds a missing preference to an existing section`() {
        val before = "locale:\n  # chosen in the web UI\npet:\n  visible: false\n"

        val after = DshLocaleSettings.upsertLegacyPreference(before, "en")

        assertEquals("locale:\n  preference: en\n  # chosen in the web UI\npet:\n  visible: false\n", after)
        assertEquals("en", DshLocaleSettings.readLegacyPreference(after))
    }

    @Test
    fun `legacy writer appends a new section at the end`() {
        val before = "agent-default-model:\n  model: deepseek-v4-pro\n"

        val after = DshLocaleSettings.upsertLegacyPreference(before, "zh")

        assertTrue(after.startsWith("agent-default-model:\n  model: deepseek-v4-pro\n"))
        assertEquals("zh", DshLocaleSettings.readLegacyPreference(after))
    }

    @Test
    fun `legacy reader ignores a quoted value and unrelated keys`() {
        assertEquals("zh", DshLocaleSettings.readLegacyPreference("locale:\n  preference: 'zh'\n"))
        assertNull(DshLocaleSettings.readLegacyPreference("locale:\n  other: zh\n"))
        assertNull(DshLocaleSettings.readLegacyPreference("other:\n  preference: zh\n"))
        assertNull(DshLocaleSettings.readLegacyPreference(""))
    }

    // ---------------------------------------------------------------------------------------------
    // Profile patch document (DSH 0.1.7+)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `profile writer replaces the empty sequence of a fresh profile`() {
        val before = """
            # Your patch layer for this dsh profile, applied after every bundle layer:
            # a top-level YAML array of loader patch entries.
            []
        """.trimIndent() + "\n"

        val after = DshLocaleSettings.upsertProfilePreference(before, "zh")

        assertTrue(after.contains("# Your patch layer for this dsh profile"))
        assertFalse(after.contains("[]"))
        assertEquals("zh", DshLocaleSettings.readProfilePreference(after))
    }

    @Test
    fun `profile writer keeps sibling rows and their comments`() {
        val before = """
            # managed above
            - id: locale
              name: '@deepseek-ai/dsh-client-locale'
              config:
                preference: en
            - id: ui-theme
              config:
                mode: dark
        """.trimIndent() + "\n"

        val after = DshLocaleSettings.upsertProfilePreference(before, "zh")

        assertTrue(after.contains("# managed above"))
        assertTrue(after.contains("name: '@deepseek-ai/dsh-client-locale'"))
        assertTrue(after.contains("- id: ui-theme\n  config:\n    mode: dark"))
        assertEquals(before.replace("preference: en", "preference: zh"), after)
    }

    @Test
    fun `profile writer adds config to a bare locale row`() {
        val before = "- id: locale\n- id: ui-theme\n"

        val after = DshLocaleSettings.upsertProfilePreference(before, "en")

        assertEquals("- id: locale\n  config:\n    preference: en\n- id: ui-theme\n", after)
    }

    @Test
    fun `profile writer merges into an existing config block`() {
        val before = "- id: locale\n  config:\n    something: kept\n"

        val after = DshLocaleSettings.upsertProfilePreference(before, "zh")

        assertEquals("zh", DshLocaleSettings.readProfilePreference(after))
        assertTrue(after.contains("    something: kept"))
    }

    @Test
    fun `profile writer handles an inline config mapping`() {
        val before = "- id: locale\n  config: { something: kept }\n"

        val after = DshLocaleSettings.upsertProfilePreference(before, "en")

        assertEquals("en", DshLocaleSettings.readProfilePreference(after))
        assertTrue(after.contains("something: kept"))
    }

    @Test
    fun `profile writer ignores nested rows of an insert patch`() {
        val before = "- insert:\n    - id: dsh-ide-settings\n      name: 'dsh-ide-settings'\n"

        val after = DshLocaleSettings.upsertProfilePreference(before, "zh")

        // The nested row is not the locale row, so a top-level row is appended.
        assertTrue(after.contains("- insert:\n    - id: dsh-ide-settings"))
        assertTrue(after.contains("- id: locale\n  config:\n    preference: zh"))
        assertEquals("zh", DshLocaleSettings.readProfilePreference(after))
    }

    @Test
    fun `profile reader prefers the last matching row`() {
        val text = "- id: locale\n  config:\n    preference: en\n- id: locale\n  config:\n    preference: zh\n"
        assertEquals("zh", DshLocaleSettings.readProfilePreference(text))
    }

    // ---------------------------------------------------------------------------------------------
    // apply(): document selection and user-choice preservation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `fresh home seeds the system language through the legacy document`() {
        val home = Files.createTempDirectory("dsh-locale-fresh")

        val result = DshLocaleSettings.apply(home, "auto") {}

        assertEquals(DshLocaleSettings.systemDefaultLanguage(), result.language)
        assertEquals(DshLocaleSettings.Action.SEEDED, result.action)
        assertEquals(
            DshLocaleSettings.systemDefaultLanguage(),
            Files.readString(home.resolve("settings.yaml")).let(DshLocaleSettings::readLegacyPreference),
        )
    }

    @Test
    fun `a language picked in the web ui survives auto`() {
        val home = Files.createTempDirectory("dsh-locale-kept")
        val legacy = home.resolve("settings.yaml")
        Files.writeString(legacy, "locale:\n  preference: en\nagent-default-model:\n  model: x\n")
        val before = Files.readString(legacy)

        val result = DshLocaleSettings.apply(home, "auto") {}

        assertEquals("en", result.language)
        assertEquals(DshLocaleSettings.Action.KEPT, result.action)
        assertEquals(before, Files.readString(legacy))
    }

    @Test
    fun `an explicit setting is enforced over a stored choice`() {
        val home = Files.createTempDirectory("dsh-locale-enforced")
        val profile = profileDocument(home)
        Files.writeString(profile, "- id: locale\n  config:\n    preference: en\n")

        val result = DshLocaleSettings.apply(home, "zh") {}

        assertEquals("zh", result.language)
        assertEquals(DshLocaleSettings.Action.APPLIED, result.action)
        assertEquals("zh", DshLocaleSettings.readProfilePreference(Files.readString(profile)))
    }

    @Test
    fun `an already matching explicit setting writes nothing`() {
        val home = Files.createTempDirectory("dsh-locale-unchanged")
        val profile = profileDocument(home)
        Files.writeString(profile, "- id: locale\n  config:\n    preference: zh\n")
        val before = Files.readString(profile)

        val result = DshLocaleSettings.apply(home, "zh") {}

        assertEquals(DshLocaleSettings.Action.UNCHANGED, result.action)
        assertEquals(before, Files.readString(profile))
        assertFalse(Files.exists(home.resolve("settings.yaml")))
    }

    @Test
    fun `a migrated home is seeded through the profile document`() {
        val home = Files.createTempDirectory("dsh-locale-migrated")
        Files.writeString(home.resolve("settings.yaml.imported"), "locale:\n  preference: en\n")
        val profile = profileDocument(home)
        Files.writeString(profile, "[]\n")

        val result = DshLocaleSettings.apply(home, "zh") {}

        assertEquals(DshLocaleSettings.Action.APPLIED, result.action)
        assertEquals("zh", DshLocaleSettings.readProfilePreference(Files.readString(profile)))
        assertFalse(Files.exists(home.resolve("settings.yaml")))
        assertTrue(DshLocaleSettings.migratedToProfileDocument(home))
    }

    @Test
    fun `a still present legacy document is kept in sync with the profile document`() {
        val home = Files.createTempDirectory("dsh-locale-both")
        val legacy = home.resolve("settings.yaml")
        Files.writeString(legacy, "locale:\n  preference: zh\n")
        val profile = profileDocument(home)
        Files.writeString(profile, "- id: locale\n  config:\n    preference: zh\n")

        // DSH imports a re-appearing legacy document over the live configuration,
        // so an explicit change has to reach both documents.
        val result = DshLocaleSettings.apply(home, "en") {}

        assertEquals(DshLocaleSettings.Action.APPLIED, result.action)
        assertEquals("en", DshLocaleSettings.readLegacyPreference(Files.readString(legacy)))
        assertEquals("en", DshLocaleSettings.readProfilePreference(Files.readString(profile)))
    }

    @Test
    fun `a legacy value is authoritative while the profile document has none`() {
        val home = Files.createTempDirectory("dsh-locale-pending-import")
        Files.writeString(home.resolve("settings.yaml"), "locale:\n  preference: zh\n")
        val profile = profileDocument(home)
        Files.writeString(profile, "[]\n")

        val result = DshLocaleSettings.apply(home, "auto") {}

        assertEquals(DshLocaleSettings.Action.KEPT, result.action)
        assertEquals("zh", result.language)
        assertEquals("[]\n", Files.readString(profile))
    }

    /** Creates the profile directory and returns its empty patch document path. */
    private fun profileDocument(home: Path): Path {
        val dir = Files.createDirectories(home.resolve("profiles").resolve("web"))
        return dir.resolve("cordis.patch.yml")
    }
}

package com.deepseek.dsh.ide.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DshHomePolicyTest {

    @Test
    fun `a missing destination is seeded verbatim`() {
        val source = Files.createTempFile("dsh-seed-source", ".yaml")
        val target = Files.createTempDirectory("dsh-seed-target").resolve("settings.yaml")
        val text = "# main home\nlocale:\n  preference: en\npet:\n  visible: false\n"
        Files.writeString(source, text)

        DshHomePolicy.mergeSettingsMissing(source, target) {}

        assertEquals(text, Files.readString(target))
    }

    @Test
    fun `an existing value is never overwritten, missing keys are added`() {
        val source = Files.createTempFile("dsh-merge-source", ".yaml")
        val target = Files.createTempFile("dsh-merge-target", ".yaml")
        // The isolated home already stored the user's own language choice.
        Files.writeString(
            target,
            "# isolated home\nlocale:\n  preference: zh\nagent-default-model:\n  model: v4-pro\n",
        )
        // The main home still carries an older value plus a key the instance lacks.
        Files.writeString(
            source,
            "locale:\n  preference: en\nagent-default-model:\n  model: v4-flash\n  reasoningEffort: max\npet:\n  visible: true\n",
        )

        DshHomePolicy.mergeSettingsMissing(source, target) {}

        val merged = Files.readString(target)
        assertEquals("zh", DshLocaleSettings.readLegacyPreference(merged))
        assertTrue(merged.contains("# isolated home"))
        assertTrue(merged.contains("model: v4-pro"))
        assertTrue(merged.contains("reasoningEffort: max"))
        assertTrue(merged.contains("pet:\n  visible: true"))
    }

    @Test
    fun `an identical document is left untouched`() {
        val source = Files.createTempFile("dsh-same-source", ".yaml")
        val target = Files.createTempFile("dsh-same-target", ".yaml")
        val text = "locale:\n  preference: zh\n"
        Files.writeString(source, text)
        Files.writeString(target, text)

        DshHomePolicy.mergeSettingsMissing(source, target) {}

        assertEquals(text, Files.readString(target))
    }
}

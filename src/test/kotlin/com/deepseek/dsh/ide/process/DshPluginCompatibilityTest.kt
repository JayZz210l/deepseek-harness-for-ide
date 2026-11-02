package com.deepseek.dsh.ide.process

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DshPluginCompatibilityTest {
    @Test
    fun `API strings and compatibility shims never disable plugins`() {
        val profile = Files.createTempDirectory("dsh-plugin-compatibility")
        val lib = Files.createDirectories(profile.resolve("node_modules/@hytime/dsh-thinking-effort/lib"))
        Files.writeString(lib.resolve("index.js"), "function installSettingsSectionCompat() {} // settingsNamespace")
        Files.writeString(lib.resolve("index.d.ts"), "import { SettingsNamespace } from '@deepseek-ai/dsh-host-apiproxy'")
        val patch = profile.resolve("cordis.patch.yml")
        val original = "- id: thinking-effort\n  config: { enabled: true }\n"
        Files.writeString(patch, original)
        repeat(2) { DshPluginCompatibility.apply(profile) {} }
        assertEquals(original, Files.readString(patch))
    }

    @Test
    fun `old managed blocks are removed without dropping later user rows or explicit disables`() {
        val profile = Files.createTempDirectory("dsh-plugin-upgrade")
        val patch = profile.resolve("cordis.patch.yml")
        val before = "- id: user-disabled\n  disabled: true\n"
        val after = "# user settings after block\n- id: locale\n  config: { preference: zh }\n"
        Files.writeString(patch, before + "# dsh-ide compatibility filters: start\n" +
            "- id: thinking-effort\n  disabled: true\n# dsh-ide compatibility filters: end\n" + after)
        val logs = mutableListOf<String>()
        repeat(2) { DshPluginCompatibility.apply(profile, logs::add) }
        assertEquals(before + after, Files.readString(patch))
        assertEquals(1, logs.size)
    }

    @Test
    fun `incomplete managed block is preserved`() {
        val text = "# dsh-ide compatibility filters: start\n# user data\n"
        assertEquals(text, DshPluginCompatibility.sourcePatchContent(text))
    }
}

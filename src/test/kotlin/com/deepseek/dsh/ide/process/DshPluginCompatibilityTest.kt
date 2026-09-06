package com.deepseek.dsh.ide.process

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DshPluginCompatibilityTest {

    @Test
    fun `only loader rows backed by removed APIs are disabled`() {
        val profile = Files.createTempDirectory("dsh-plugin-compatibility")
        val scope = Files.createDirectories(profile.resolve("node_modules").resolve("@example"))
        val aggregate = packageDir(scope, "aggregate", "@example/aggregate", "export const ok = true")
        packageDir(
            scope,
            "legacy-settings",
            "@example/legacy-settings",
            "import { installSettingsSection } from '@deepseek-ai/dsh-client-ui-settings'",
        )
        packageDir(scope, "modern-chat", "@example/modern-chat", "export const ok = true")
        Files.writeString(
            aggregate.resolve("cordis.patch.yml"),
            """
            - insert:
                - id: legacy-settings
                  name: '@example/legacy-settings'
            - insert:
                - id: modern-chat
                  name: '@example/modern-chat'
            """.trimIndent(),
        )
        Files.writeString(profile.resolve("cordis.patch.yml"), "# user overrides\n[]\n")

        val result = DshPluginCompatibility.apply(profile) {}
        val patch = Files.readString(profile.resolve("cordis.patch.yml"))

        assertTrue(result.packages.contains("@example/legacy-settings"))
        assertTrue(result.loaderIds.contains("legacy-settings"))
        assertTrue(patch.contains("- id: 'legacy-settings'\n  disabled: true"))
        assertFalse(patch.contains("- id: 'modern-chat'\n  disabled: true"))
        assertFalse(patch.lineSequence().any { it.trim() == "[]" })
        assertTrue(
            DshPluginCompatibility.sourcePatchContent(patch) ==
                DshPluginCompatibility.sourcePatchContent("# user overrides\n[]\n"),
        )
    }

    private fun packageDir(scope: java.nio.file.Path, folder: String, name: String, source: String): java.nio.file.Path {
        val dir = Files.createDirectories(scope.resolve(folder))
        Files.writeString(dir.resolve("package.json"), "{\"name\":\"$name\"}")
        Files.writeString(Files.createDirectories(dir.resolve("lib")).resolve("index.js"), source)
        return dir
    }
}

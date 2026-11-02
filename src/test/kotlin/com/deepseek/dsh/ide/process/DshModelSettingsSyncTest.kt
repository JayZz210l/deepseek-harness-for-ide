package com.deepseek.dsh.ide.process

import org.junit.Assert.*
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files

class DshModelSettingsSyncTest {
    private val source = """
        # main profile
        - id: llm-pi-ai
          config:
            providers:
              third-party:
                baseUrl: https://example.test/v1
                api: openai-completions
                models:
                  - id: custom-model
                    name: Custom
        - id: agent-default-model
          config: { provider: third-party, model: custom-model }
        - id: locale
          config: { preference: en }
        - insert:
            - id: community-plugin
              name: some-plugin
    """.trimIndent() + "\n"

    @Test
    fun `seed migrated source without copying locale or plugin loaders`() {
        val result = DshModelSettingsSync.merge(source, "# project\n[]\n", false)
        val rows = config(result)
        assertEquals(setOf("llm-pi-ai", "agent-default-model"), rows.keys)
        assertTrue(result.contains("custom-model"))
        assertTrue(result.startsWith("# project"))
        assertFalse(result.contains("some-plugin"))
        assertEquals(result, DshModelSettingsSync.merge(source, result, false))
    }

    @Test
    fun `automatic seed keeps project overrides while manual sync replaces model config`() {
        val target = """
            # project 😀
            - id: llm-pi-ai
              disabled: false
              config:
                providers: { old: { models: [] } }
            - id: locale
              config: { preference: zh }
            - id: user-plugin
              disabled: true
        """.trimIndent() + "\n"
        val seeded = DshModelSettingsSync.merge(source, target, false)
        assertEquals(config(target)["llm-pi-ai"], config(seeded)["llm-pi-ai"])
        val synced = DshModelSettingsSync.merge(source, seeded, true)
        assertEquals(config(source)["llm-pi-ai"], config(synced)["llm-pi-ai"])
        assertEquals(config(target)["locale"], config(synced)["locale"])
        assertTrue(synced.startsWith("# project 😀"))
        assertTrue(synced.contains("- id: user-plugin\n  disabled: true"))
        assertTrue(synced.contains("  disabled: false"))
        assertEquals(synced, DshModelSettingsSync.merge(source, synced, true))
    }

    @Test
    fun `flow mappings quoted ids multiline strings and repeated ids are supported`() {
        val text = """
            - {id: 'llm-pi-ai', config: {providers: {}}}
            - id: llm-pi-ai
              config:
                providers:
                  custom:
                    baseUrl: >-
                      https://example.test
                    models: []
        """.trimIndent()
        val target = "- { id: llm-pi-ai, config: { providers: {} } }\n"
        val synced = DshModelSettingsSync.merge(text, target, true)
        assertEquals(config(text), config(synced))
    }

    @Test
    fun `Cordis custom tags survive seeding without object construction`() {
        val text = "- id: llm-deepseek\n  config: { token: !!js 'ctx.token' }\n"
        val result = DshModelSettingsSync.merge(text, "[]\n", false)
        assertTrue(result.contains("!!js"))
        assertTrue(result.contains("ctx.token"))
    }

    @Test
    fun `malformed source fails without writing target`() {
        val main = Files.createTempDirectory("dsh-model-invalid-main")
        val home = Files.createTempDirectory("dsh-model-invalid-home")
        val relative = "profiles/web/cordis.patch.yml"
        Files.createDirectories(main.resolve(relative).parent)
        Files.createDirectories(home.resolve(relative).parent)
        Files.writeString(main.resolve(relative), "- id: llm-pi-ai\n  config: [broken")
        Files.writeString(home.resolve(relative), "[]\n")
        assertTrue(runCatching { DshModelSettingsSync.sync(main, home, true) {} }.isFailure)
        assertEquals("[]\n", Files.readString(home.resolve(relative)))
    }

    @Test
    fun `home seed reads migrated main and project and never writes to main`() {
        val main = Files.createTempDirectory("dsh-model-main")
        val home = Files.createTempDirectory("dsh-model-project")
        val relative = "profiles/web/cordis.patch.yml"
        Files.createDirectories(main.resolve(relative).parent)
        Files.writeString(main.resolve(relative), source)
        Files.writeString(main.resolve("settings.yaml.imported"), "historical settings")
        Files.writeString(main.resolve(".credentials.yaml"), "secret: test")
        Files.writeString(home.resolve("settings.yaml.imported"), "historical project settings")
        DshHomePolicy.seedHomeFrom(main, home) {}
        assertTrue(Files.readString(home.resolve(relative)).contains("custom-model"))
        assertEquals(source, Files.readString(main.resolve(relative)))
        assertEquals("secret: test", Files.readString(home.resolve(".credentials.yaml")))
        assertFalse(Files.exists(home.resolve("settings.yaml")))
    }

    @Test
    fun `pre-migration legacy settings are still seeded with an empty profile`() {
        val main = Files.createTempDirectory("dsh-model-legacy-main")
        val home = Files.createTempDirectory("dsh-model-legacy-project")
        val profile = main.resolve("profiles/web/cordis.patch.yml")
        Files.createDirectories(profile.parent)
        Files.writeString(profile, "[]\n")
        val legacy = "llm-pi-ai:\n  providers: {}\nlocale:\n  preference: zh\n"
        Files.writeString(main.resolve("settings.yaml"), legacy)
        DshHomePolicy.seedHomeFrom(main, home) {}
        assertEquals(legacy, Files.readString(home.resolve("settings.yaml")))
    }

    @Test
    fun `validation rollback can suppress automatic model seeding`() {
        val main = Files.createTempDirectory("dsh-model-validation-main")
        val home = Files.createTempDirectory("dsh-model-validation-project")
        val patch = main.resolve("profiles/web/cordis.patch.yml")
        Files.createDirectories(patch.parent)
        Files.writeString(patch, source)
        DshHomePolicy.seedHomeFrom(main, home, seedModels = false) {}
        assertFalse(Files.exists(home.resolve("profiles/web/cordis.patch.yml")))
    }

    @Suppress("UNCHECKED_CAST")
    private fun config(text: String): Map<String, Any?> =
        Yaml().load<List<Map<String, Any?>>>(text).filter { it.containsKey("config") }
            .associate { it["id"] as String to it["config"] }
}

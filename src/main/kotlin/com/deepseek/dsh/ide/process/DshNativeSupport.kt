package com.deepseek.dsh.ide.process

import com.deepseek.dsh.ide.i18n.DshBundle
import com.intellij.openapi.util.SystemInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Writes the DSH composition-native bridge files into a stable local directory:
 *
 * - `ide-bridge.mjs` — the Cordis gateway replacement module (shipped as a plugin
 *   resource; it reads its runtime parameters from environment variables, so the
 *   file never needs rewriting);
 * - `patch.yml` — the `--patch` overlay replacing the `api-gateway` row with the
 *   bridge module (contains the absolute module path), and — when the bundled
 *   runtime is in use — adding the `dsh-ide-settings` row that carries the
 *   "For IDE" settings-page section into the web UI.
 *
 * Returns the patch file path, or null when the files could not be written.
 */
object DshNativeSupport {

    const val BRIDGE_RESOURCE = "/dsh/ide-bridge.mjs"

    fun baseDir(): Path = Paths.get(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
        "deepseek-harness-jetbrains",
    )

    @Synchronized
    fun writeBridgeFiles(includeSettingsRow: Boolean = true): Path? {
        return runCatching {
            val dir = baseDir()
            Files.createDirectories(dir)

            val moduleFile = dir.resolve("ide-bridge.mjs")
            javaClass.getResourceAsStream(BRIDGE_RESOURCE)?.use { input ->
                Files.newOutputStream(moduleFile).use { output -> input.copyTo(output) }
            } ?: error("bridge resource $BRIDGE_RESOURCE missing")

            // The loader hands `name` straight to dynamic import(); on Windows that must be
            // a file:// URL (a bare drive-letter path is parsed as an unsupported `c:` scheme).
            val moduleUri = moduleFile.toAbsolutePath().toUri().toString()
            val patchFile = dir.resolve("patch.yml")
            // Patch semantics: `name` on a patch row is a guard, not an override, so the
            // shipped gateway row cannot be pointed at another module. Disable it and
            // INSERT a replacement row providing the same `apiProxy` service instead.
            // The `dsh-ide-settings` row is a real package (bundled under the runtime's
            // node_modules) whose `dsh.client` declaration makes the web app load its
            // client.js — the "For IDE" settings section.
            val settingsRow = if (includeSettingsRow) "    - id: dsh-ide-settings\n      name: 'dsh-ide-settings'\n" else ""
            val yaml = (
                "# DeepSeek Harness IDE bridge patch (managed by the JetBrains plugin).\n" +
                    "# Disables the shipped api-gateway row and inserts the IDE bridge gateway,\n" +
                    "# which routes host.openPath into the IDE.\n" +
                    "- id: api-gateway\n" +
                    "  disabled: true\n" +
                    "- insert:\n" +
                    "    - id: ide-api-gateway\n" +
                    "      name: '$moduleUri'\n" +
                    settingsRow
                )
            Files.write(patchFile, yaml.toByteArray(StandardCharsets.UTF_8))
            patchFile
        }.getOrNull()
    }

    /**
     * Makes the bundled `dsh-ide-settings` package resolvable from the DSH profile:
     * the client-module scanner resolves row names against the profile directory, so
     * `$DSH_HOME/profiles/node_modules/dsh-ide-settings` must point at the package
     * shipped inside the runtime's own node_modules (a junction — no admin rights
     * needed, mirroring what dsh's profile fallback healer does for its own packages).
     */
    fun ensureClientSettingsLink(implRoot: String, dshHome: String, log: (String) -> Unit) {
        if (!SystemInfo.isWindows) return
        runCatching {
            val target = Paths.get(implRoot, "node_modules", "dsh-ide-settings")
            if (!Files.isDirectory(target)) return@runCatching
            val modulesDir = Paths.get(dshHome, "profiles", "node_modules")
            Files.createDirectories(modulesDir)
            val link = modulesDir.resolve("dsh-ide-settings")
            if (Files.exists(link)) return@runCatching
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start()
            process.waitFor(15, TimeUnit.SECONDS)
            log("DSH settings package linked: $link -> $target")
        }.onFailure { log("DSH settings package link failed: ${it.message}") }
    }
}

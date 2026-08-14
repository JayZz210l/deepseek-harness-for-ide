package com.deepseek.dsh.ide.process

import com.deepseek.dsh.ide.i18n.DshBundle
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Writes the DSH composition-native bridge files into a stable local directory:
 *
 * - `ide-bridge.mjs` — the Cordis gateway replacement module (shipped as a plugin
 *   resource; it reads its runtime parameters from environment variables, so the
 *   file never needs rewriting);
 * - `patch.yml` — the `--patch` overlay replacing the `api-gateway` row with the
 *   bridge module (contains the absolute module path).
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
    fun writeBridgeFiles(): Path? {
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
            val yaml = (
                "# DeepSeek Harness IDE bridge patch (managed by the JetBrains plugin).\n" +
                    "# Disables the shipped api-gateway row and inserts the IDE bridge gateway,\n" +
                    "# which routes host.openPath into the IDE.\n" +
                    "- id: api-gateway\n" +
                    "  disabled: true\n" +
                    "- insert:\n" +
                    "    - id: ide-api-gateway\n" +
                    "      name: '$moduleUri'\n"
                )
            Files.write(patchFile, yaml.toByteArray(StandardCharsets.UTF_8))
            patchFile
        }.getOrNull()
    }
}

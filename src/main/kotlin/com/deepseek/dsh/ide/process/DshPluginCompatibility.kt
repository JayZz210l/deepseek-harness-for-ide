package com.deepseek.dsh.ide.process

import java.nio.file.Files
import java.nio.file.Path

/** Compatibility belongs to runtime metadata checks and actual boot validation. */
object DshPluginCompatibility {
    private const val MANAGED_START = "# dsh-ide compatibility filters: start"
    private const val MANAGED_END = "# dsh-ide compatibility filters: end"

    /** Remove only complete IDE-owned blocks; keep user overrides on either side. */
    fun sourcePatchContent(value: String): String = value.replace(
        Regex("(?m)^${Regex.escape(MANAGED_START)}\\r?\\n[\\s\\S]*?^${Regex.escape(MANAGED_END)}[^\\S\\r\\n]*(?:\\r?\\n|$)"),
        "",
    )

    /** Recover existing homes on startup, even when plugin manifests are unchanged. */
    fun apply(profileDir: Path, log: (String) -> Unit) {
        val patch = profileDir.resolve("cordis.patch.yml")
        if (!Files.isRegularFile(patch)) return
        val original = Files.readString(patch)
        val cleaned = sourcePatchContent(original)
        if (cleaned == original) return
        Files.writeString(patch, cleaned.ifBlank { "[]\n" })
        log("Removed legacy IDE compatibility filters; DSH runtime will validate plugin compatibility")
    }
}

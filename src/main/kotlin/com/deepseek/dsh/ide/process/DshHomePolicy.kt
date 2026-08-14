package com.deepseek.dsh.ide.process

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
     * home: only files that exist there are copied, and only when the
     * destination is missing or different, so reconfiguring the standalone
     * harness propagates into the IDE instance on the next start while the
     * plugin can never wipe or overwrite anything on the main home.
     */
    fun seedHome(home: Path, log: (String) -> Unit) {
        runCatching { Files.createDirectories(home) }.onFailure {
            log("DSH home preparation failed: ${it.message}")
            return
        }
        val main = mainHome()
        if (isSameDirectory(main, home)) return // override points at the main home: nothing to copy
        copyIfChanged(main.resolve(CREDENTIALS_FILE), home.resolve(CREDENTIALS_FILE), log)
        copyIfChanged(main.resolve(SETTINGS_FILE), home.resolve(SETTINGS_FILE), log)
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

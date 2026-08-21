package com.deepseek.dsh.ide.process

/**
 * Pure, version-tolerant builders for the `dsh web` command line.
 *
 * The layout rules here encode what the DSH launcher accepts across its
 * releases (pure functions, so they are unit-testable outside the IDE):
 *
 * - `web` is the web-profile alias; the launcher's own flags (`--patch`,
 *   `--profile`, ...) must appear BEFORE any inner app argument, otherwise
 *   newer launchers stop option collection at the first positional argument
 *   and hand the flag to the web app, which rejects it ("too many arguments"
 *   / "unknown option"). `--patch <file>` therefore goes IMMEDIATELY after
 *   `web`, before `--host`/`--port`.
 * - `--no-open` suppresses the default-browser handoff the web app performs
 *   once the server is ready (the embedded IDE browser is the UI). It only
 *   exists in newer dsh releases, so the caller decides per resolved runtime
 *   (see [DshProcessManager.supportsNoOpen]).
 */
internal object DshWebLaunch {

    /**
     * Builds the final `dsh web ...` token list.
     *
     * @param resolvedCommand the executable tokens (e.g. `[dsh]` or `[node, .../bin.js]`
     *   plus any user-configured tail arguments).
     * @param patchFile optional `--patch` overlay; inserted right after `web`.
     * @param noOpen append `--no-open` when the resolved runtime supports it.
     */
    fun buildTokens(
        resolvedCommand: List<String>,
        host: String,
        port: Int,
        patchFile: String? = null,
        noOpen: Boolean = false,
    ): List<String> {
        val tokens = resolvedCommand.toMutableList()
        val webIndex = tokens.size
        tokens += "web"
        if (patchFile != null) {
            // Immediately after `web`: launcher flags end at the first unknown
            // token, so a patch placed after `--host` is parsed as a web-app
            // argument and fails the boot on every current DSH launcher.
            tokens.add(webIndex + 1, "--patch")
            tokens.add(webIndex + 2, patchFile)
        }
        tokens += listOf("--host", host.trim().ifBlank { "127.0.0.1" })
        // Always pass --port explicitly: the shipped web profile defaults to 3080,
        // which conflicts with any already-running dsh web instance. 0 = OS pick.
        tokens += listOf("--port", port.coerceIn(0, 65535).toString())
        if (noOpen) tokens += "--no-open"
        return tokens
    }

    /**
     * The probe command that decides whether a resolved runtime knows
     * `--no-open`: the web app prints its own flag family for
     * `dsh web --help`, and the help text names the flag exactly when the
     * runtime supports it. Parsing (not executing) the flag — the probe never
     * starts a server.
     */
    fun helpProbeTokens(resolvedCommand: List<String>): List<String> =
        resolvedCommand + listOf("web", "--help")
}

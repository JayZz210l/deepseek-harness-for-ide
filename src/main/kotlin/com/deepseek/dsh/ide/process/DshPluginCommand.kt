package com.deepseek.dsh.ide.process

/** Parses the deliberately narrow plugin-install command accepted by the IDE action. */
object DshPluginCommand {
    private val COMMAND = Regex(
        "^\\s*(?:dsh(?:\\.cmd)?)\\s+plugin\\s+--profile\\s+web\\s+add\\s+(.+?)\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val UNSAFE = Regex("[&|;<>\\\"'`\\$()\\r\\n\\u0000]")

    /** Returns an npm/git install spec, or null for a command outside the supported scope. */
    fun parse(raw: String): String? {
        val value = raw.trim()
        val spec = COMMAND.matchEntire(value)?.groupValues?.get(1)?.trim()
            ?: value.takeIf { it.isNotEmpty() && !it.any(Char::isWhitespace) }
        return spec?.takeIf { it.isNotEmpty() && !UNSAFE.containsMatchIn(it) }
    }
}

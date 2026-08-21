package com.deepseek.dsh.ide.process

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the `dsh web` token layout. The layout rules encode the DSH
 * launcher's contract: launcher-owned flags (like `--patch`) end at the first
 * token the launcher does not recognize, so they must precede every inner
 * web-app argument. This is exactly the bug that broke the previous plugin
 * releases (`web --host --patch <f> ...` → "too many arguments").
 */
class DshWebLaunchTest {

    @Test
    fun patchGoesImmediatelyAfterWeb() {
        val tokens = DshWebLaunch.buildTokens(
            resolvedCommand = listOf("dsh"),
            host = "127.0.0.1",
            port = 0,
            patchFile = "C:\\p\\patch.yml",
        )
        assertEquals(
            listOf("dsh", "web", "--patch", "C:\\p\\patch.yml", "--host", "127.0.0.1", "--port", "0"),
            tokens,
        )
    }

    @Test
    fun patchGoesImmediatelyAfterWebWithNodeBinJsCommand() {
        val tokens = DshWebLaunch.buildTokens(
            resolvedCommand = listOf("node", "C:\\dsh\\lib\\bin.js", "--trace"),
            host = "127.0.0.1",
            port = 4227,
            patchFile = "patch.yml",
        )
        assertEquals(
            listOf("node", "C:\\dsh\\lib\\bin.js", "--trace", "web", "--patch", "patch.yml", "--host", "127.0.0.1", "--port", "4227"),
            tokens,
        )
    }

    @Test
    fun noOpenAppendsAtTheEnd() {
        val tokens = DshWebLaunch.buildTokens(
            resolvedCommand = listOf("dsh"),
            host = "127.0.0.1",
            port = 0,
            noOpen = true,
        )
        assertEquals(
            listOf("dsh", "web", "--host", "127.0.0.1", "--port", "0", "--no-open"),
            tokens,
        )
    }

    @Test
    fun portIsClampedAndBlankHostDefaultsToLoopback() {
        val tokens = DshWebLaunch.buildTokens(
            resolvedCommand = listOf("dsh"),
            host = "  ",
            port = 99999,
        )
        assertEquals(
            listOf("dsh", "web", "--host", "127.0.0.1", "--port", "65535"),
            tokens,
        )
    }

    @Test
    fun helpProbeTokensParseNotExecute() {
        assertEquals(
            listOf("dsh", "web", "--help"),
            DshWebLaunch.helpProbeTokens(listOf("dsh")),
        )
    }
}

package com.deepseek.dsh.ide.process

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

class DshApiProxyTest {

    @Test
    fun `serves open editor candidates without contacting upstream`() {
        val unusedUpstreamPort = ServerSocket(0).use { it.localPort }
        val proxy = DshApiProxy(
            onOpenPath = {},
            openFilesJson = { "[\"src/Main.kt\",\"README.md\"]" },
        )
        try {
            val port = proxy.start(unusedUpstreamPort)
            val connection = URL("http://127.0.0.1:$port/__dsh_ide/open-files")
                .openConnection() as HttpURLConnection
            assertEquals(200, connection.responseCode)
            assertEquals("[\"src/Main.kt\",\"README.md\"]", connection.inputStream.bufferedReader().readText())
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `opens IDE paths through same origin endpoint`() {
        val unusedUpstreamPort = ServerSocket(0).use { it.localPort }
        var opened: String? = null
        val proxy = DshApiProxy(onOpenPath = { opened = it })
        try {
            val port = proxy.start(unusedUpstreamPort)
            val connection = URI("http://127.0.0.1:$port/__dsh_ide/open").toURL()
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("{\"path\":\"dsh-ide://sync-plugins\"}".toByteArray()) }
            assertEquals(200, connection.responseCode)
            assertEquals("dsh-ide://sync-plugins", opened)
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `opens encoded IDE actions through bodyless get endpoint`() {
        val unusedUpstreamPort = ServerSocket(0).use { it.localPort }
        var opened: String? = null
        val proxy = DshApiProxy(onOpenPath = { opened = it })
        try {
            val port = proxy.start(unusedUpstreamPort)
            val connection = URI("http://127.0.0.1:$port/__dsh_ide/open?path=dsh-ide%3A%2F%2Fsync-plugins")
                .toURL()
                .openConnection() as HttpURLConnection
            assertEquals(200, connection.responseCode)
            assertEquals("{\"opened\":true}", connection.inputStream.bufferedReader().readText())
            assertEquals("dsh-ide://sync-plugins", opened)
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `opens every settings action through compatibility endpoint`() {
        val unusedUpstreamPort = ServerSocket(0).use { it.localPort }
        val opened = mutableListOf<String>()
        val proxy = DshApiProxy(onOpenPath = { opened += it })
        try {
            val port = proxy.start(unusedUpstreamPort)
            val actions = listOf(
                "dsh-ide://sync-agent-presets",
                "dsh-ide://sync-plugins",
                "dsh-ide://reset-plugins",
            )
            for (action in actions) {
                val encoded = java.net.URLEncoder.encode(action, StandardCharsets.UTF_8)
                val connection = URI("http://127.0.0.1:$port/__dsh_ide/open?path=$encoded").toURL()
                    .openConnection() as HttpURLConnection
                assertEquals(200, connection.responseCode)
            }
            assertEquals(actions, opened)
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `intercepts 0_1_2 remote workspace open without replacing controller`() {
        val unusedUpstreamPort = ServerSocket(0).use { it.localPort }
        var opened: String? = null
        val proxy = DshApiProxy(onOpenPath = { opened = it })
        try {
            val port = proxy.start(unusedUpstreamPort)
            val connection = URI("http://127.0.0.1:$port/api/session/openWorkspacePath").toURL()
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val request = """{"type":"client-request","rpcId":"r-open","method":"session/openWorkspacePath","payload":{"args":{"request":{"path":"C:\\work\\Main.kt"}}}}"""
            connection.outputStream.use { it.write(request.toByteArray()) }
            assertEquals(200, connection.responseCode)
            assertEquals("C:\\work\\Main.kt", opened)
            val response = connection.inputStream.bufferedReader().readText()
            assertEquals(true, response.contains("\"rpcId\":\"r-open\""))
            assertEquals(true, response.contains("\"opened\":true"))
        } finally {
            proxy.stop()
        }
    }
}

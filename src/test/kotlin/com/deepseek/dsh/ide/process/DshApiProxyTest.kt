package com.deepseek.dsh.ide.process

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

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
}

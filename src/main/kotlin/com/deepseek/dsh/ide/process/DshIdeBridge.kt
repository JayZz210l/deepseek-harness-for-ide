package com.deepseek.dsh.ide.process

import com.deepseek.dsh.ide.i18n.DshBundle
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Tiny loopback HTTP receiver the DSH composition-native gateway calls for
 * `host.openPath`. The bridge only accepts requests carrying the per-start
 * bearer token (loopback binding is the primary boundary; the token is
 * defense-in-depth against other local processes).
 */
class DshIdeBridge(
    private val onOpenPath: (path: String) -> Unit,
) {

    @Volatile
    private var server: HttpServer? = null

    /** Random per-start token. */
    @Volatile
    var token: String = ""
        private set

    /** http://127.0.0.1:<port>; valid after [start]. */
    @Volatile
    var baseUrl: String = ""
        private set

    @Synchronized
    fun start() {
        stop()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "DeepSeekHarness-IdeBridge").apply { isDaemon = true }
        }
        val newToken = generateToken()
        httpServer.createContext("/open") { exchange -> handleOpen(exchange, newToken) }
        httpServer.createContext("/health") { exchange -> respond(exchange, 200, "{\"ok\":true}") }
        httpServer.start()
        token = newToken
        baseUrl = "http://127.0.0.1:${httpServer.address.port}"
        server = httpServer
    }

    @Synchronized
    fun stop() {
        runCatching { server?.stop(0) }
        server = null
    }

    private fun handleOpen(exchange: HttpExchange, expectedToken: String) {
        try {
            val auth = exchange.requestHeaders.getFirst("Authorization") ?: ""
            if (auth != "Bearer $expectedToken") {
                respond(exchange, 403, "{\"ok\":false,\"error\":\"forbidden\"}")
                return
            }
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, "{\"ok\":false,\"error\":\"method not allowed\"}")
                return
            }
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val path = Regex("\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
            if (path.isNullOrBlank()) {
                respond(exchange, 400, "{\"ok\":false,\"error\":\"missing path\"}")
                return
            }
            val unescaped = path
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
            onOpenPath(unescaped)
            respond(exchange, 200, "{\"ok\":true}")
        } catch (ignored: Exception) {
            runCatching { respond(exchange, 500, "{\"ok\":false,\"error\":\"internal\"}") }
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

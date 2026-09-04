package com.deepseek.dsh.ide.process

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local TCP forwarder between the embedded browser and the real `dsh web` port.
 * Pure JDK on purpose — the logic is unit-testable outside the IDE.
 *
 * Design: a per-request relay loop.
 * - Every client request is parsed individually, so `POST /api/host.openPath` is
 *   intercepted on ANY connection (keep-alive reuse included) and answered
 *   locally; the file opens in the IDE instead of the host desktop.
 * - Ordinary requests are forwarded to the real server one by one; responses
 *   with a Content-Length are relayed and the loop continues (keep-alive).
 * - Responses without a Content-Length (SSE streams, EOF-delimited) and
 *   WebSocket upgrades fall back to a transparent bidirectional byte pump,
 *   which dedicates the connection to that stream.
 */
class DshApiProxy(
    private val onOpenPath: (path: String) -> Unit,
    private val openFilesJson: () -> String = { "[]" },
) {

    private val running = AtomicBoolean(false)

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var targetPort: Int = 0

    /** Local port the browser should connect to; valid after [start]. */
    @Volatile
    var port: Int = 0
        private set

    @Synchronized
    fun start(realPort: Int): Int {
        stop()
        targetPort = realPort
        val socket = ServerSocket().apply { reuseAddress = true }
        socket.bind(InetSocketAddress("127.0.0.1", 0))
        server = socket
        port = socket.localPort
        running.set(true)
        Thread({ acceptLoop(socket) }, "DeepSeekHarness-Proxy").apply { isDaemon = true }.start()
        return port
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                return
            }
            Thread({ handle(client) }, "DeepSeekHarness-Proxy-Conn").apply { isDaemon = true }.start()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Connection handling
    // ---------------------------------------------------------------------------------------------

    private fun handle(client: Socket) {
        client.soTimeout = 15000
        try {
            client.use { cl ->
                while (!cl.isClosed) {
                    val headBytes = readHead(cl.getInputStream())
                    if (headBytes.isEmpty()) return
                    val head = String(headBytes, StandardCharsets.ISO_8859_1)
                    val requestLineEnd = head.indexOf("\r\n")
                    if (requestLineEnd < 0) return
                    val parts = head.substring(0, requestLineEnd).split(" ")
                    if (parts.size < 2) return
                    val method = parts[0].uppercase(Locale.ROOT)
                    val rawPath = parts[1].substringBefore('?')
                    // Accept absolute-form request lines (RFC 7230 §5.3.2); some clients send them.
                    val path = if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) {
                        runCatching { java.net.URI.create(rawPath).rawPath ?: rawPath }.getOrDefault(rawPath)
                    } else {
                        rawPath
                    }
                    val headers = parseHeaders(head.substring(requestLineEnd + 2))

                    // Only a real WebSocket handshake gets the raw-pump treatment. Clients
                    // attempting HTTP/2 cleartext upgrades (Upgrade: h2c) keep the normal
                    // bounded path: the server answers without 101 and the connection
                    // continues as HTTP/1.1 with per-request interception intact.
                    val isUpgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true

                    if (isUpgrade) {
                        forwardRaw(cl, headBytes) // transparent pump until the stream ends
                        return
                    }

                    if (method == "POST" && path == "/api/host.openPath") {
                        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                        val bodyBytes = readBody(cl.getInputStream(), contentLength)
                        interceptOpenPath(cl, String(bodyBytes, StandardCharsets.UTF_8))
                        continue
                    }

                    if (method == "GET" && path == "/__dsh_ide/open-files") {
                        respondPlain(cl.getOutputStream(), "200 OK", "application/json; charset=utf-8", openFilesJson())
                        continue
                    }

                    if (!forwardBounded(cl, headBytes, method, headers)) return
                }
            }
        } catch (ignored: IOException) {
            // connection already closed
        }
    }

    /** Relays the current request, its body, and one bounded response; returns false when the connection is done. */
    private fun forwardBounded(client: Socket, headBytes: ByteArray, method: String, headers: Map<String, String>): Boolean {
        val requestBody = if (method == "POST") {
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            readBody(client.getInputStream(), contentLength)
        } else {
            ByteArray(0)
        }

        val target = connectTarget() ?: run {
            respondPlain(client.getOutputStream(), "502 Bad Gateway", "text/plain; charset=utf-8", "upstream unreachable")
            return false
        }
        target.use { t ->
            t.getOutputStream().write(headBytes)
            if (requestBody.isNotEmpty()) t.getOutputStream().write(requestBody)
            t.getOutputStream().flush()

            val respHead = readHead(t.getInputStream())
            if (respHead.isEmpty()) return false
            val respHeaders = parseHeaders(String(respHead, StandardCharsets.ISO_8859_1))
            val respContentLength = respHeaders["content-length"]?.toIntOrNull()

            if (respContentLength == null) {
                // Streaming (SSE/EOF-delimited) or unknown: relay verbatim, then raw pump.
                client.getOutputStream().write(respHead)
                client.getOutputStream().flush()
                pump(t, client)
                return false
            }

            client.getOutputStream().write(respHead)
            val respBody = readBody(t.getInputStream(), respContentLength)
            client.getOutputStream().write(respBody)
            client.getOutputStream().flush()
            return true
        }
    }

    /** Relays the request head verbatim, then transparently pumps the upgraded/streamed connection. */
    private fun forwardRaw(client: Socket, headBytes: ByteArray) {
        val target = connectTarget() ?: run {
            respondPlain(client.getOutputStream(), "502 Bad Gateway", "text/plain; charset=utf-8", "upstream unreachable")
            return
        }
        target.use { t ->
            t.getOutputStream().write(headBytes)
            t.getOutputStream().flush()
            val respHead = readHead(t.getInputStream())
            if (respHead.isEmpty()) return
            client.getOutputStream().write(respHead)
            client.getOutputStream().flush()
            pump(t, client)
        }
    }

    private fun connectTarget(): Socket? {        val target = Socket()
        target.soTimeout = 30000
        return try {
            target.connect(InetSocketAddress("127.0.0.1", targetPort), 5000)
            target
        } catch (e: IOException) {
            runCatching { target.close() }
            null
        }
    }

    private fun interceptOpenPath(client: Socket, body: String) {
        val rpcId = extractJsonString(body, "rpcId") ?: UUID.randomUUID().toString()
        val path = extractJsonString(body, "path")?.let(::unescapeJson)
        val responseBody = if (path.isNullOrBlank()) {
            "{\"type\":\"server-response\",\"rpcId\":\"$rpcId\",\"result\":{\"ok\":false,\"error\":{\"code\":\"internal\",\"message\":\"missing path\",\"details\":{}}}}"
        } else {
            onOpenPath(path)
            "{\"type\":\"server-response\",\"rpcId\":\"$rpcId\",\"result\":{\"ok\":true,\"value\":{\"opened\":true}}}"
        }
        val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
        val head = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1)
        client.getOutputStream().write(head)
        client.getOutputStream().write(bytes)
        client.getOutputStream().flush()
    }

    // ---------------------------------------------------------------------------------------------
    // Byte-level helpers
    // ---------------------------------------------------------------------------------------------

    /** Reads the header block (up to `\r\n\r\n`); body bytes stay in the socket. */
    private fun readHead(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var state = 0 // matched prefix length of \r\n\r\n
        while (out.size() < 65536) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            when {
                state == 0 && b == 13 -> state = 1
                state == 1 && b == 10 -> state = 2
                state == 2 && b == 13 -> state = 3
                state == 3 && b == 10 -> return out.toByteArray()
                b == 13 -> state = 1
                else -> state = 0
            }
        }
        return out.toByteArray()
    }

    private fun readBody(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count.coerceIn(0, 1 shl 22))
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) break
            read += n
        }
        return bytes.copyOf(read)
    }

    private fun parseHeaders(block: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in block.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx > 0) map[line.substring(0, idx).trim().lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
        }
        return map
    }

    /** Bidirectional byte pump; returns when both directions are finished or timed out. */
    private fun pump(target: Socket, client: Socket) {
        val t2c = Thread({ copy(target.getInputStream(), client.getOutputStream()); runCatching { client.shutdownOutput() } })
        val c2t = Thread({ copy(client.getInputStream(), target.getOutputStream()); runCatching { target.shutdownOutput() } })
        t2c.isDaemon = true
        c2t.isDaemon = true
        t2c.start()
        c2t.start()
        t2c.join(300_000)
        c2t.join(300_000)
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (ignored: IOException) {
            // stream closed by peer
        }
    }

    private fun respondPlain(output: OutputStream, status: String, contentType: String, body: String) {
        runCatching {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val head = (
                "HTTP/1.1 $status\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray(StandardCharsets.ISO_8859_1)
            output.write(head)
            output.write(bytes)
            output.flush()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Minimal JSON helpers (enough for the fixed DSH wire envelope)
    // ---------------------------------------------------------------------------------------------

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun unescapeJson(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < value.length) {
                            val hex = value.substring(i + 2, i + 6).toIntOrNull(16)
                            if (hex != null) {
                                sb.append(hex.toChar())
                                i += 4
                            }
                        }
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

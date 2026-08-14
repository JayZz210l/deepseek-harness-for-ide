package com.deepseek.dsh.ide.process

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Minimal client for the DSH `/api` wire protocol (reverse-engineered from
 * `@deepseek-ai/dsh-client-connection`): a unary call is
 * `POST /api/<method>` with body `{type:"client-request", rpcId, method, payload}`,
 * answered by `{type:"server-response", rpcId, result:{ok, value}}`.
 *
 * All requests go directly to the real dsh port (bypassing the file-open proxy);
 * loopback targets always connect directly regardless of the IDE's JVM proxy.
 */
object DshApiClient {

    private fun loopbackClient(): HttpClient = HttpClient.newBuilder()
        // The JDK client defaults to HTTP_2, which sends a cleartext `Upgrade: h2c`
        // preamble. The DSH webserver routes Upgrade requests through its WebSocket
        // upgrade table, has no handler for h2c, and closes the connection without a
        // response — every plugin-side /api call then fails with "HTTP/1.1 header
        // parser received no bytes". Force plain HTTP/1.1 (the DshApiProxy forwarder
        // has the same downgrade for the same reason).
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .proxy(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                val host = uri.host
                return if (host == "127.0.0.1" || host == "localhost" || host == "::1") {
                    listOf(Proxy.NO_PROXY)
                } else {
                    ProxySelector.getDefault().select(uri)
                }
            }

            override fun connectFailed(uri: URI, sa: java.net.SocketAddress, ioe: IOException) = Unit
        })
        .build()

    private fun post(baseUrl: String, method: String, payloadJson: String, timeout: Duration = Duration.ofSeconds(20)): String {
        val body = "{\"type\":\"client-request\",\"rpcId\":\"${UUID.randomUUID()}\",\"method\":\"$method\",\"payload\":$payloadJson}"
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/$method"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = loopbackClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()}: ${response.body().take(200)}")
        }
        return response.body()
    }

    data class SessionSummary(
        val sessionId: String,
        val updatedAt: Long,
        val running: Boolean,
        val blank: Boolean,
    )

    data class WorkspaceInfo(
        val workspaceId: String,
        val path: String,
        val sessionIds: List<String> = emptyList(),
    )

    /** `workspace.list` — returns the durable workspaces in display order. */
    fun listWorkspaces(baseUrl: String, timeout: Duration = Duration.ofSeconds(20)): List<WorkspaceInfo> {
        val body = post(baseUrl, "workspace.list", "{}", timeout)
        val items = mutableListOf<WorkspaceInfo>()
        val itemPattern = Regex("\\{[^{}]*?\"workspaceId\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        for (match in itemPattern.findAll(body)) {
            val tail = body.substring(match.range.first)
            val end = tail.indexOf('}')
            val item = if (end >= 0) tail.substring(0, end + 1) else tail
            val path = Regex("\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(item)?.groupValues?.get(1)?.let(::unescapeJson) ?: continue
            val sessionIds = Regex("\"sessionIds\"\\s*:\\s*\\[([^\\]]*)\\]")
                .find(item)?.groupValues?.get(1)
                ?.split(',')
                ?.mapNotNull { it.trim().trim('"').takeIf { id -> id.isNotEmpty() } }
                ?.map(::unescapeJson)
                ?: emptyList()
            items += WorkspaceInfo(match.groupValues[1].let(::unescapeJson), path, sessionIds)
        }
        return items.distinctBy { it.workspaceId }
    }

    /** `workspace.create` — idempotently adopt an existing directory as a workspace. */
    fun createWorkspace(baseUrl: String, path: String, timeout: Duration = Duration.ofSeconds(20)): WorkspaceInfo? {
        val payload = "{\"path\":${jsonEscape(path)}}"
        val body = post(baseUrl, "workspace.create", payload, timeout)
        val id = Regex("\"workspaceId\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(body)?.groupValues?.get(1)?.let(::unescapeJson) ?: return null
        return WorkspaceInfo(id, path)
    }

    /** `workspace.insertBefore` — moves a workspace to the front (anchor omitted appends). */
    fun insertWorkspaceBefore(baseUrl: String, workspaceId: String, beforeWorkspaceId: String?, timeout: Duration = Duration.ofSeconds(20)) {
        val payload = if (beforeWorkspaceId == null) {
            "{\"workspaceId\":${jsonEscape(workspaceId)}}"
        } else {
            "{\"workspaceId\":${jsonEscape(workspaceId)},\"beforeWorkspaceId\":${jsonEscape(beforeWorkspaceId)}}"
        }
        post(baseUrl, "workspace.insertBefore", payload, timeout)
    }

    /** `session.list` — returns sessions ordered by `updatedAt` descending. */
    fun listSessions(baseUrl: String, timeout: Duration = Duration.ofSeconds(20)): List<SessionSummary> {
        val body = post(baseUrl, "session.list", "{}", timeout)
        val items = mutableListOf<SessionSummary>()
        val itemPattern = Regex("\\{[^{}]*?\"sessionId\"\\s*:\\s*\"([^\"]+)\"")
        for (match in itemPattern.findAll(body)) {
            val tail = body.substring(match.range.first)
            val end = tail.indexOf('}')
            val item = if (end >= 0) tail.substring(0, end + 1) else tail
            val updatedAt = Regex("\"updatedAt\"\\s*:\\s*(\\d+)").find(item)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val running = Regex("\"running\"\\s*:\\s*(true|false)").find(item)?.groupValues?.get(1) == "true"
            val blank = Regex("\"blank\"\\s*:\\s*(true|false)").find(item)?.groupValues?.get(1) == "true"
            items += SessionSummary(match.groupValues[1], updatedAt, running, blank)
        }
        return items.distinctBy { it.sessionId }.sortedByDescending { it.updatedAt }
    }

    /** `session.create` with the given working directory; returns the new session id. */
    fun createSession(baseUrl: String, cwd: String): String {
        val payload = "{\"cwd\":${jsonEscape(cwd)}}"
        val body = post(baseUrl, "session.create", payload)
        val sessionId = Regex("\"sessionId\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
            ?: throw IOException("no sessionId in response: ${body.take(200)}")
        return unescapeJson(sessionId)
    }

    /** `session.prompt` — queues one text message into the session. */
    fun sendPrompt(baseUrl: String, sessionId: String, text: String) {
        val content = "[{\"type\":\"text\",\"text\":${jsonEscape(text)}}]"
        val payload = "{\"sessionId\":${jsonEscape(sessionId)},\"mode\":\"queue\",\"content\":$content}"
        val body = post(baseUrl, "session.prompt", payload)
        if (!body.contains("\"accepted\":true")) {
            throw IOException("unexpected response: ${body.take(200)}")
        }
    }

    /**
     * `credentials.describe` — whether one credential reference is configured.
     * Returns `null` when the answer cannot be determined (wire error or a
     * protocol the response does not match); a transport failure surfaces as
     * [IOException] through [post].
     */
    fun credentialConfigured(baseUrl: String, ref: String): Boolean? {
        val body = post(baseUrl, "credentials.describe", """{"refs":["$ref"]}""")
        val pattern = Regex("\"$ref\"\\s*:\\s*\\{[^}]*?\"configured\"\\s*:\\s*(true|false)")
        return pattern.find(body)?.groupValues?.get(1)?.toBoolean()
    }

    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 16)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun unescapeJson(value: String): String =
        value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")
}

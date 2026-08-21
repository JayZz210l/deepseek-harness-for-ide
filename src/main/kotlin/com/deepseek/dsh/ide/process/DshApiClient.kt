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
 * Response parsing goes through [DshJson]: a real JSON tree with field lookup
 * anywhere in the nesting, instead of shape-specific regexes. DSH response
 * envelopes and field nesting change between releases; tree-walking keeps this
 * client working across those updates (see [DshJson] for the details).
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
        /** ISO-8601 creation instant; the fallback recency signal for empty workspaces. */
        val createdAt: String? = null,
    )

    /** `workspace.list` — returns the durable workspaces in display order. */
    fun listWorkspaces(baseUrl: String, timeout: Duration = Duration.ofSeconds(20)): List<WorkspaceInfo> =
        parseWorkspaceListBody(post(baseUrl, "workspace.list", "{}", timeout))

    /** Parsing half of [listWorkspaces]; exposed for unit tests (pure). */
    internal fun parseWorkspaceListBody(body: String): List<WorkspaceInfo> {
        val root = DshJson.parse(body) ?: return emptyList()
        val items = DshJson.findObjects(root, "workspaceId").mapNotNull { obj ->
            val id = obj.members["workspaceId"]?.asString() ?: return@mapNotNull null
            val path = obj.members["path"]?.asString() ?: return@mapNotNull null
            val sessionIds = (obj.members["sessionIds"] as? DshJson.Node.Arr)?.items
                ?.mapNotNull { it.asString() } ?: emptyList()
            val createdAt = obj.members["createdAt"]?.asString()
            WorkspaceInfo(id, path, sessionIds, createdAt)
        }
        return items.distinctBy { it.workspaceId }
    }

    /** `workspace.create` — idempotently adopt an existing directory as a workspace. */
    fun createWorkspace(baseUrl: String, path: String, timeout: Duration = Duration.ofSeconds(20)): WorkspaceInfo? {
        val payload = "{\"path\":${jsonEscape(path)}}"
        // Both the flat (older dsh) and the `{workspace:{...}, created}` (newer dsh)
        // response shapes carry a workspaceId string somewhere in the tree.
        val id = DshJson.parse(post(baseUrl, "workspace.create", payload, timeout))
            ?.let { DshJson.findString(it, "workspaceId") }
            ?: return null
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
    fun listSessions(baseUrl: String, timeout: Duration = Duration.ofSeconds(20)): List<SessionSummary> =
        parseSessionListBody(post(baseUrl, "session.list", "{}", timeout))

    /** Parsing half of [listSessions]; exposed for unit tests (pure). */
    internal fun parseSessionListBody(body: String): List<SessionSummary> {
        val root = DshJson.parse(body) ?: return emptyList()
        val items = DshJson.findObjects(root, "sessionId").mapNotNull { obj ->
            val id = obj.members["sessionId"]?.asString() ?: return@mapNotNull null
            val updatedAt = (obj.members["updatedAt"] as? DshJson.Node.Num)?.toLongOrNull() ?: 0L
            val running = (obj.members["running"] as? DshJson.Node.Bool)?.value ?: false
            val blank = (obj.members["blank"] as? DshJson.Node.Bool)?.value ?: false
            SessionSummary(id, updatedAt, running, blank)
        }
        return items.distinctBy { it.sessionId }.sortedByDescending { it.updatedAt }
    }

    /**
     * `session.create` for the given workspace and working directory; returns the
     * new session id. The workspace-scoped form (`{workspaceId}`) is what the web
     * UI itself uses and keeps the session accounted inside the workspace; DSH
     * accepts workspaceId OR cwd, never both, and older dsh generations that only
     * knew `{cwd}` answer a business error to the workspace form, so the legacy
     * cwd-only form is retried once.
     */
    fun createSession(baseUrl: String, workspaceId: String?, cwd: String?): String {
        if (workspaceId != null) {
            val payload = "{\"workspaceId\":${jsonEscape(workspaceId)}}"
            val attempt = runCatching { post(baseUrl, "session.create", payload) }.getOrNull()
            if (attempt != null) {
                DshJson.parse(attempt)?.let { DshJson.findString(it, "sessionId") }?.let { return it }
            }
        }
        val payload = "{\"cwd\":${jsonEscape(cwd.orEmpty())}}"
        val body = post(baseUrl, "session.create", payload)
        return DshJson.parse(body)?.let { DshJson.findString(it, "sessionId") }
            ?: throw IOException("no sessionId in response: ${body.take(200)}")
    }

    /** `session.create` with the given working directory; returns the new session id. */
    fun createSession(baseUrl: String, cwd: String): String = createSession(baseUrl, null, cwd)

    /** `session.prompt` — queues one text message into the session. */
    fun sendPrompt(baseUrl: String, sessionId: String, text: String) {
        val content = "[{\"type\":\"text\",\"text\":${jsonEscape(text)}}]"
        val payload = "{\"sessionId\":${jsonEscape(sessionId)},\"mode\":\"queue\",\"content\":$content}"
        val body = post(baseUrl, "session.prompt", payload)
        val accepted = DshJson.parse(body)?.let { DshJson.findBoolean(it, "accepted") }
        if (accepted != true && !body.contains("\"accepted\":true")) {
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
        val root = DshJson.parse(body) ?: return null
        // value.credentials.<ref> = {configured, source?, writable} — find the
        // sub-object named after the ref, wherever the envelope nests it.
        for ((_, view) in DshJson.namedSubObjects(root, ref)) {
            val configured = view.members["configured"] as? DshJson.Node.Bool
            if (configured != null) return configured.value
        }
        return null
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
}

package com.deepseek.dsh.ide.process

import com.intellij.util.messages.Topic

enum class DshServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
    SYNCING,
    RESETTING,
}

/**
 * Immutable snapshot of the managed `dsh web` server.
 *
 * @param url      the URL the embedded browser loads — normally the file-open proxy
 *                 port; only non-null once known.
 * @param realUrl  the real `dsh web` port, used by plugin-side `/api` calls and logging.
 * @param detail   a human-readable detail, mainly the failure reason or process exit description.
 */
data class DshServerStatus(
    val state: DshServerState = DshServerState.STOPPED,
    val url: String? = null,
    val realUrl: String? = null,
    val detail: String? = null,
    val pid: Long? = null,
)

fun interface DshServerStatusListener {
    fun onStatusChanged(status: DshServerStatus)
}

object DshServerTopics {
    @JvmField
    val SERVER_STATUS: Topic<DshServerStatusListener> =
        Topic.create("DeepSeekHarness.serverStatus", DshServerStatusListener::class.java)
}

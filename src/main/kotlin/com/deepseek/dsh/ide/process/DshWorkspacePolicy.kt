package com.deepseek.dsh.ide.process

/**
 * Pure workspace-selection policy shared by [DshProcessManager] and the tests.
 *
 * DSH web releases changed their startup auto-selection rule over time: older
 * releases selected the FIRST workspace in the durable order, newer releases
 * select the workspace with the most recent activity. The plugin makes the IDE
 * workspace win under BOTH rules: it moves the workspace to the front (old
 * rule) and — via a fresh blank session — makes it the activity-most-recent
 * (new rule). [mostRecentWorkspaceId] mirrors the newer rule exactly, so the
 * plugin only mints that blank session when it is actually needed.
 */
internal object DshWorkspacePolicy {

    /**
     * The workspace the web UI auto-selects at startup under the recency policy
     * (mirrors `recentWorkspace` in the DSH web client): the workspace whose
     * newest member session `updatedAt` is latest, falling back to its
     * `createdAt` for workspaces without sessions. Ties keep workspace list order.
     */
    fun mostRecentWorkspaceId(
        workspaces: List<DshApiClient.WorkspaceInfo>,
        sessions: List<DshApiClient.SessionSummary>,
    ): String? {
        var selected: String? = null
        var selectedTime = Long.MIN_VALUE
        for (workspace in workspaces) {
            var latest = Long.MIN_VALUE
            for (sessionId in workspace.sessionIds) {
                val session = sessions.firstOrNull { it.sessionId == sessionId }
                if (session != null) latest = maxOf(latest, session.updatedAt)
            }
            if (latest == Long.MIN_VALUE) {
                latest = parseEpochMillis(workspace.createdAt)
            }
            if (selected == null || latest > selectedTime) {
                selected = workspace.workspaceId
                selectedTime = latest
            }
        }
        return selected
    }

    /** True when [target] still needs a startup blank session to win the recency rule. */
    fun needsBlankSessionBump(
        workspaces: List<DshApiClient.WorkspaceInfo>,
        sessions: List<DshApiClient.SessionSummary>,
        target: DshApiClient.WorkspaceInfo,
    ): Boolean {
        val hasBlank = sessions.any { it.blank && it.sessionId in target.sessionIds }
        if (!hasBlank) return true
        return mostRecentWorkspaceId(workspaces, sessions) != target.workspaceId
    }

    /** ISO-8601 instant (e.g. `2026-08-21T10:21:32.367Z`) to epoch millis; 0 when unparsable. */
    fun parseEpochMillis(iso: String?): Long =
        iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
}

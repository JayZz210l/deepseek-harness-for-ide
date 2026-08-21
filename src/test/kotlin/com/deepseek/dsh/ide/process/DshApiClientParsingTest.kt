package com.deepseek.dsh.ide.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the response-parsing half of [DshApiClient], using realistic
 * envelopes from both older and current DSH releases. The parser is
 * nesting-tolerant by design: the same functions must accept the flat legacy
 * shapes AND the current wrapped ones.
 */
class DshApiClientParsingTest {

    @Test
    fun parsesCurrentWorkspaceListEnvelope() {
        val body = """
            {"type":"server-response","rpcId":"r1","result":{"ok":true,"value":{"items":[
              {"workspaceId":"w-1","path":"C:\\proj","title":"proj","sessionIds":["s-a"],
               "createdAt":"2026-08-21T10:21:32.367Z","updatedAt":"2026-08-21T10:21:32.367Z"},
              {"workspaceId":"w-2","path":"C:\\proj\\sub","title":"sub","sessionIds":["s-b","s-c"],
               "createdAt":"2026-08-20T00:00:00.000Z","updatedAt":"2026-08-20T00:00:00.000Z"}
            ],"archivedSessionIds":[]}}}
        """.trimIndent()
        val workspaces = DshApiClient.parseWorkspaceListBody(body)
        assertEquals(2, workspaces.size)
        assertEquals("w-1", workspaces[0].workspaceId)
        assertEquals("C:\\proj", workspaces[0].path)
        assertEquals(listOf("s-a"), workspaces[0].sessionIds)
        assertEquals("2026-08-21T10:21:32.367Z", workspaces[0].createdAt)
    }

    @Test
    fun parsesLegacyFlatWorkspaceListShape() {
        // Older releases shipped a flatter value; field lookup must survive.
        val body = """{"ok":true,"value":[{"workspaceId":"w-x","path":"/t"}]}"""
        val workspaces = DshApiClient.parseWorkspaceListBody(body)
        assertEquals(1, workspaces.size)
        assertEquals("w-x", workspaces[0].workspaceId)
        assertEquals("/t", workspaces[0].path)
    }

    @Test
    fun parsesCurrentSessionListWithNestedProjections() {
        val body = """
            {"type":"server-response","rpcId":"r2","result":{"ok":true,"value":{"items":[
              {"sessionId":"s-1","updatedAt":1787309331371,"running":true,"blank":false,
               "projections":{"asOfSeq":77,"values":{"sessionListMetadata":{"blank":false,"lastPromptAt":1}}}},
              {"sessionId":"s-2","updatedAt":100,"running":false,"blank":true},
              {"sessionId":"s-3","updatedAt":200,"running":false,"blank":false}
            ]}}}
        """.trimIndent()
        val sessions = DshApiClient.parseSessionListBody(body)
        assertEquals(3, sessions.size)
        assertEquals("s-1", sessions[0].sessionId) // sorted by updatedAt desc
        assertEquals(1787309331371L, sessions[0].updatedAt)
        assertTrue(sessions[0].running)
        assertFalse(sessions[0].blank)
        assertEquals("s-3", sessions[1].sessionId)
        assertFalse(sessions[1].blank)
        assertEquals("s-2", sessions[2].sessionId)
        assertTrue(sessions[2].blank)
        // The nested projections object must not confuse the walkers: no phantom
        // extra session (sessionListMetadata has no sessionId member).
        assertEquals(3, sessions.size)
    }

    @Test
    fun unparsableBodiesYieldEmptyLists() {
        assertTrue(DshApiClient.parseWorkspaceListBody("<html>gateway timeout</html>").isEmpty())
        assertTrue(DshApiClient.parseSessionListBody("").isEmpty())
    }

    @Test
    fun recencyPolicyMirrorsWebClient() {
        // Mirrors recentWorkspace(): max member-session updatedAt, createdAt
        // fallback for empty workspaces, list order breaks ties.
        val workspaces = listOf(
            DshApiClient.WorkspaceInfo("w-empty-new", "/e", emptyList(), "2026-08-21T10:00:00.000Z"),
            DshApiClient.WorkspaceInfo("w-old-active", "/o", listOf("s-old"), "2026-08-20T00:00:00.000Z"),
        )
        val sessions = listOf(DshApiClient.SessionSummary("s-old", 1786791922818L, running = false, blank = false))
        // The older workspace's session activity (Aug 15) beats the empty workspace's
        // recent creation (Aug 21)? No: creation is Aug 21 10:00, session is earlier.
        assertEquals("w-empty-new", DshWorkspacePolicy.mostRecentWorkspaceId(workspaces, sessions))

        val newerSession = listOf(
            DshApiClient.SessionSummary("s-old", 1787400000000L, running = false, blank = false),
        )
        assertEquals("w-old-active", DshWorkspacePolicy.mostRecentWorkspaceId(workspaces, newerSession))
    }

    @Test
    fun blankSessionBumpDecisions() {
        val target = DshApiClient.WorkspaceInfo("w-target", "/p", listOf("s-blank"), "2026-08-21T10:00:00.000Z")
        val other = DshApiClient.WorkspaceInfo("w-other", "/p/sub", listOf("s-new"), "2026-08-20T00:00:00.000Z")
        val workspaces = listOf(target, other)

        // Target has no blank at all -> bump.
        val none = listOf(DshApiClient.SessionSummary("s-new", 1787400000000L, running = false, blank = false))
        assertTrue(DshWorkspacePolicy.needsBlankSessionBump(workspaces, none, target))

        // Target has a blank and IS the most recent -> no bump.
        val blankRecent = listOf(
            DshApiClient.SessionSummary("s-blank", 1787500000000L, running = false, blank = true),
            DshApiClient.SessionSummary("s-new", 1787400000000L, running = false, blank = false),
        )
        assertFalse(DshWorkspacePolicy.needsBlankSessionBump(workspaces, blankRecent, target))

        // Target has a STALE blank while another workspace is more recent -> bump.
        val blankStale = listOf(
            DshApiClient.SessionSummary("s-blank", 100L, running = false, blank = true),
            DshApiClient.SessionSummary("s-new", 1787400000000L, running = false, blank = false),
        )
        assertTrue(DshWorkspacePolicy.needsBlankSessionBump(workspaces, blankStale, target))
    }

    @Test
    fun parseEpochMillisHandlesIsoAndGarbage() {
        // 2026-08-21T10:21:32.367Z = 1787307692.367s since epoch.
        assertEquals(1787307692367L, DshWorkspacePolicy.parseEpochMillis("2026-08-21T10:21:32.367Z"))
        assertEquals(0L, DshWorkspacePolicy.parseEpochMillis(null))
        assertEquals(0L, DshWorkspacePolicy.parseEpochMillis("not-a-date"))
    }
}

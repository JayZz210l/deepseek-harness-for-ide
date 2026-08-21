package com.deepseek.dsh.ide.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the minimal JSON parser + tree walkers that back [DshApiClient].
 * The point of the design: DSH keeps re-nesting its response envelopes, so the
 * walkers must find fields anywhere in the tree.
 */
class DshJsonTest {

    private fun parsed(body: String): DshJson.Node = DshJson.parse(body) ?: error("expected valid JSON")

    @Test
    fun parsesNestedEnvelopeAndFindsFieldsAnywhere() {
        val body = """
            {"type":"server-response","rpcId":"r1",
             "result":{"ok":true,"value":{"items":[
               {"workspaceId":"w-1","path":"C:\\proj","sessionIds":["s-a","s-b"],
                "createdAt":"2026-08-21T10:21:32.367Z"},
               {"workspaceId":"w-2","path":"C:\\other","sessionIds":[],
                "createdAt":"2026-08-20T00:00:00.000Z"}
             ],"archivedSessionIds":["s-b"]}}}
        """.trimIndent()
        val root = parsed(body)
        assertEquals(listOf("w-1", "w-2"), DshJson.findStrings(root, "workspaceId"))
        val sessionIds = (DshJson.findObjects(root, "sessionIds").first()
            .members["sessionIds"] as DshJson.Node.Arr).items.mapNotNull { it.asString() }
        assertEquals(listOf("s-a", "s-b"), sessionIds)
        assertEquals("w-1", DshJson.findString(root, "workspaceId"))
        assertEquals(true, DshJson.findBoolean(root, "ok"))
        assertEquals(2, DshJson.findObjects(root, "workspaceId").size)
    }

    @Test
    fun parsesUnicodeEscapesIncludingSurrogatePairs() {
        val root = parsed("{\"emoji\":\"\\ud83d\\ude00\",\"cn\":\"\\u4f60\\u597d\"}") as DshJson.Node.Obj
        assertEquals("\uD83D\uDE00", (root.members["emoji"] as DshJson.Node.Str).value)
        assertEquals("你好", (root.members["cn"] as DshJson.Node.Str).value)
    }

    @Test
    fun parsesEscapesNumbersBooleansAndNull() {
        val root = parsed("""{"s":"a\"b\\c\/d","n":-12.5,"big":1787309331371,"t":true,"f":false,"z":null}""")
            as DshJson.Node.Obj
        assertEquals("a\"b\\c/d", (root.members["s"] as DshJson.Node.Str).value)
        assertEquals(-12.5, (root.members["n"] as DshJson.Node.Num).toDoubleOrNull()!!, 1e-9)
        assertEquals(1787309331371L, (root.members["big"] as DshJson.Node.Num).toLongOrNull())
        assertEquals(true, (root.members["t"] as DshJson.Node.Bool).value)
        assertEquals(false, (root.members["f"] as DshJson.Node.Bool).value)
        assertTrue(root.members["z"] is DshJson.Node.Null)
    }

    @Test
    fun invalidJsonYieldsNull() {
        assertNull(DshJson.parse("{"))
        assertNull(DshJson.parse("{\"a\":}"))
        assertNull(DshJson.parse("[1,2"))
        assertNull(DshJson.parse(""))
        assertNull(DshJson.parse("not json at all"))
    }

    @Test
    fun namedSubObjectsFindsCredentialViews() {
        val body = """
            {"type":"server-response","rpcId":"r",
             "result":{"ok":true,"value":{"credentials":{
               "DEEPSEEK_API_KEY":{"configured":true,"source":"env","writable":false},
               "OTHER_KEY":{"configured":false,"writable":true}}}}}
        """.trimIndent()
        val apiKey = DshJson.namedSubObjects(parsed(body), "DEEPSEEK_API_KEY").firstOrNull()
        assertNotNull(apiKey)
        assertEquals(true, (apiKey!!.second.members["configured"] as DshJson.Node.Bool).value)
        assertTrue(DshJson.namedSubObjects(parsed(body), "MISSING").isEmpty())
    }

    @Test
    fun walkingHandlesEmptyContainers() {
        val root = parsed("{\"a\":[],\"b\":{}}")
        var count = 0
        DshJson.walk(root) { count++ }
        assertEquals(3, count) // root + empty array + empty object
        assertFalse(DshJson.findStrings(root, "x").isNotEmpty())
        assertTrue(DshJson.findObjects(root, "x").isEmpty())
    }
}

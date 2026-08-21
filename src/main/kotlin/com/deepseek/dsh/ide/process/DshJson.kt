package com.deepseek.dsh.ide.process

import java.util.LinkedHashMap

/**
 * Minimal JSON parser and tree walkers for the DSH `/api` wire protocol.
 *
 * Why not regexes over the raw text: DSH evolves quickly and the response
 * envelopes (and the nesting depth of fields like `session.list` items) change
 * between versions — e.g. the `workspace.create` value gained a `workspace`
 * wrapper, and `session.list` items gained a nested `projections` object.
 * Regexes that tolerate one shape break on the next one. A real parser returns
 * a value tree, and the walkers below find fields ANYWHERE in that tree, so
 * envelope/nesting changes keep working without plugin updates.
 *
 * Pure JDK on purpose (unit-testable outside the IDE, no external deps).
 */
internal object DshJson {

    /** One JSON value. */
    sealed class Node {
        class Obj(val members: LinkedHashMap<String, Node>) : Node()
        class Arr(val items: List<Node>) : Node()
        class Str(val value: String) : Node()
        class Num(val raw: String) : Node() {
            fun toLongOrNull(): Long? = raw.toLongOrNull()
            fun toDoubleOrNull(): Double? = raw.toDoubleOrNull()
        }
        class Bool(val value: Boolean) : Node()
        object Null : Node()

        fun asString(): String? = (this as? Str)?.value
    }

    /** Parses [text] into a value tree, or null when it is not valid JSON. */
    fun parse(text: String): Node? = runCatching { Parser(text).parseDocument() }.getOrNull()

    /**
     * Every object in the tree (depth-first, including the root) that has a
     * member named [member]. Field names are matched anywhere in the nesting,
     * so both `value.items[0]` and a future `value.workspace` wrapper are found.
     */
    fun findObjects(root: Node, member: String): List<Node.Obj> {
        val result = mutableListOf<Node.Obj>()
        collectObjects(root, member, result)
        return result
    }

    /** All string values held under a member named [member], anywhere in the tree. */
    fun findStrings(root: Node, member: String): List<String> {
        val result = mutableListOf<String>()
        collectStrings(root, member, result)
        return result
    }

    /** First string value under a member named [member], anywhere in the tree. */
    fun findString(root: Node, member: String): String? = findStrings(root, member).firstOrNull()

    /** First boolean under a member named [member], anywhere in the tree. */
    fun findBoolean(root: Node, member: String): Boolean? {
        var found: Boolean? = null
        walk(root) { node ->
            if (found != null) return@walk
            if (node is Node.Obj) {
                val value = node.members[member] as? Node.Bool
                if (value != null) found = value.value
            }
        }
        return found
    }

    /**
     * For every object in the tree whose member [refName] holds ANOTHER object,
     * yields the pair (holder object, ref value object). This is how
     * `credentials.describe` answers: `value.credentials.<ref> = {configured...}`.
     */
    fun namedSubObjects(root: Node, refName: String): List<Pair<Node.Obj, Node.Obj>> {
        val result = mutableListOf<Pair<Node.Obj, Node.Obj>>()
        walk(root) { node ->
            if (node is Node.Obj) {
                val sub = node.members[refName]
                if (sub is Node.Obj) result += node to sub
            }
        }
        return result
    }

    /** Depth-first visit of every node, including [root]. */
    fun walk(root: Node, visitor: (Node) -> Unit) {
        visitor(root)
        when (root) {
            is Node.Obj -> root.members.values.forEach { walk(it, visitor) }
            is Node.Arr -> root.items.forEach { walk(it, visitor) }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Collectors
    // ---------------------------------------------------------------------------------------------

    private fun collectObjects(node: Node, member: String, into: MutableList<Node.Obj>) {
        if (node is Node.Obj) {
            if (node.members.containsKey(member)) into += node
            node.members.values.forEach { collectObjects(it, member, into) }
        } else if (node is Node.Arr) {
            node.items.forEach { collectObjects(it, member, into) }
        }
    }

    private fun collectStrings(node: Node, member: String, into: MutableList<String>) {
        if (node is Node.Obj) {
            (node.members[member] as? Node.Str)?.let { into += it.value }
            node.members.values.forEach { collectStrings(it, member, into) }
        } else if (node is Node.Arr) {
            node.items.forEach { collectStrings(it, member, into) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Parser
    // ---------------------------------------------------------------------------------------------

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseDocument(): Node {
            val value = parseValue()
            skipWhitespace()
            if (pos < text.length) error("trailing content")
            return value
        }

        private fun parseValue(): Node {
            skipWhitespace()
            if (pos >= text.length) error("unexpected end")
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Node.Str(parseString())
                't' -> parseLiteral("true", Node.Bool(true))
                'f' -> parseLiteral("false", Node.Bool(false))
                'n' -> parseLiteral("null", Node.Null)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else error("unexpected '$c'")
            }
        }

        private fun parseObject(): Node.Obj {
            pos++ // '{'
            val members = LinkedHashMap<String, Node>()
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return Node.Obj(members)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                members[key] = value
                skipWhitespace()
                when {
                    pos >= text.length -> error("unterminated object")
                    text[pos] == ',' -> pos++
                    text[pos] == '}' -> {
                        pos++
                        return Node.Obj(members)
                    }
                    else -> error("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): Node.Arr {
            pos++ // '['
            val items = mutableListOf<Node>()
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return Node.Arr(items)
            }
            while (true) {
                items += parseValue()
                skipWhitespace()
                when {
                    pos >= text.length -> error("unterminated array")
                    text[pos] == ',' -> pos++
                    text[pos] == ']' -> {
                        pos++
                        return Node.Arr(items)
                    }
                    else -> error("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) error("unterminated string")
                val c = text[pos]
                when {
                    c == '"' -> {
                        pos++
                        return sb.toString()
                    }
                    c == '\\' -> {
                        pos++
                        if (pos >= text.length) error("unterminated escape")
                        when (val esc = text[pos]) {
                            '"' -> { sb.append('"'); pos++ }
                            '\\' -> { sb.append('\\'); pos++ }
                            '/' -> { sb.append('/'); pos++ }
                            'b' -> { sb.append('\b'); pos++ }
                            'f' -> { sb.append('\u000C'); pos++ }
                            'n' -> { sb.append('\n'); pos++ }
                            'r' -> { sb.append('\r'); pos++ }
                            't' -> { sb.append('\t'); pos++ }
                            'u' -> {
                                pos++ // skip 'u'
                                val code = parseHex4()
                                if (code in 0xD800..0xDBFF && pos + 1 < text.length
                                    && text[pos] == '\\' && text[pos + 1] == 'u'
                                ) {
                                    pos += 2 // skip '\' 'u' of the low surrogate
                                    val low = parseHex4()
                                    if (low in 0xDC00..0xDFFF) {
                                        sb.append(Character.toChars(0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)))
                                    } else {
                                        sb.append('\uFFFD') // invalid pair, keep the well-formed part
                                    }
                                } else {
                                    sb.append(code.toChar())
                                }
                            }
                            else -> error("bad escape '\\$esc'")
                        }
                    }
                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
            }
        }

        /** Reads the 4 hex digits at [pos] and advances past them. */
        private fun parseHex4(): Int {
            if (pos + 4 > text.length) error("truncated \\u escape")
            val hex = text.substring(pos, pos + 4)
            val code = hex.toIntOrNull(16) ?: error("bad \\u escape")
            pos += 4
            return code
        }

        private fun parseNumber(): Node.Num {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (pos < text.length && text[pos] == '.') {
                pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            return Node.Num(text.substring(start, pos))
        }

        private fun parseLiteral(literal: String, node: Node): Node {
            if (text.startsWith(literal, pos)) {
                pos += literal.length
                return node
            }
            error("bad literal")
        }

        private fun expect(c: Char) {
            skipWhitespace()
            if (pos >= text.length || text[pos] != c) error("expected '$c'")
            pos++
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}

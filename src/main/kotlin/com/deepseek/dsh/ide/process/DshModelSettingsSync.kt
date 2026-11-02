package com.deepseek.dsh.ide.process

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

/** Copies built-in provider/model configuration only; never copies loader names or disabled flags. */
object DshModelSettingsSync {
    private val ids = setOf("llm-pi-ai", "llm-deepseek", "llm-deepseek-account", "agent-default-model")
    private const val PATCH = "profiles/web/cordis.patch.yml"

    fun sync(main: Path, home: Path, overwrite: Boolean, log: (String) -> Unit): Boolean {
        if (main.toAbsolutePath().normalize() == home.toAbsolutePath().normalize()) return false
        val source = main.resolve(PATCH)
        if (!Files.isRegularFile(source)) return false
        val target = home.resolve(PATCH)
        val current = if (Files.exists(target)) Files.readString(target) else "[]\n"
        val merged = merge(Files.readString(source), current, overwrite)
        if (merged == current) return false
        Files.createDirectories(target.parent)
        Files.writeString(target, merged)
        log("DSH provider/model settings ${if (overwrite) "synced" else "seeded"}: $target <- $source")
        return true
    }

    /** Parse nodes without constructing objects: Cordis custom tags remain inert and intact. */
    internal fun merge(source: String, target: String, overwrite: Boolean): String {
        val yaml = Yaml(LoaderOptions().apply {
            // Only compose/serialize nodes; the expression is never evaluated here.
            tagInspector = org.yaml.snakeyaml.inspector.TagInspector { it.value == "tag:yaml.org,2002:js" }
        }, DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK })
        fun rows(text: String): List<MappingNode> {
            val root = yaml.compose(text.reader()) ?: return emptyList()
            require(root is SequenceNode) { "Cordis profile patch must be a YAML sequence" }
            val result = mutableListOf<MappingNode>()
            fun visit(sequence: SequenceNode) {
                for (node in sequence.value) {
                    require(node is MappingNode) { "Cordis patch rows must be mappings" }
                    result += node
                    (field(node, "insert")?.valueNode as? SequenceNode)?.let(::visit)
                }
            }
            visit(root)
            return result
        }
        val sourceRows = rows(source).filter { id(it) in ids && field(it, "config") != null }
            .associateBy { id(it)!! }
        val targetRows = rows(target)
        data class Edit(val start: Int, val end: Int, val value: String)
        val edits = mutableListOf<Edit>()
        val additions = mutableListOf<Node>()
        for ((id, row) in sourceRows) {
            val config = field(row, "config")!!
            val existing = targetRows.lastOrNull { id(it) == id && field(it, "config") != null }
            if (existing != null) {
                if (!overwrite) continue
                val old = field(existing, "config")!!
                val rendered = StringWriter().also {
                    yaml.serialize(MappingNode(Tag.MAP, listOf(config), DumperOptions.FlowStyle.BLOCK), it)
                }.toString().trimEnd()
                val oldRendered = StringWriter().also {
                    yaml.serialize(MappingNode(Tag.MAP, listOf(old), DumperOptions.FlowStyle.BLOCK), it)
                }.toString().trimEnd()
                if (oldRendered == rendered) continue
                if (existing.flowStyle == DumperOptions.FlowStyle.FLOW) {
                    val mapping = MappingNode(Tag.MAP,
                        existing.value.map { if (it === old) config else it }, DumperOptions.FlowStyle.BLOCK)
                    val block = StringWriter().also { yaml.serialize(mapping, it) }.toString().trimEnd()
                    val padding = " ".repeat(existing.startMark.column)
                    edits += Edit(existing.startMark.index, existing.endMark.index,
                        block.lines().joinToString("\n$padding"))
                    continue
                }
                val indent = " ".repeat(old.keyNode.startMark.column)
                val replacement = rendered.lines().joinToString("\n$indent") + "\n"
                edits += Edit(old.keyNode.startMark.index, old.valueNode.endMark.index, replacement)
            } else {
                additions += MappingNode(Tag.MAP, listOf(field(row, "id")!!, config), DumperOptions.FlowStyle.BLOCK)
            }
        }
        if (edits.isEmpty() && additions.isEmpty()) return target
        var merged = target
        // SnakeYAML marks count Unicode code points; JVM substring uses UTF-16 indices.
        fun offset(index: Int) = target.offsetByCodePoints(0, index)
        for (edit in edits.sortedByDescending { it.start }) {
            merged = merged.replaceRange(offset(edit.start), offset(edit.end), edit.value)
        }
        if (additions.isNotEmpty()) {
            val block = StringWriter().also {
                yaml.serialize(SequenceNode(Tag.SEQ, additions, DumperOptions.FlowStyle.BLOCK), it)
            }.toString()
            // Replace only a parsed empty document; never strip [] inside a config.
            val root = yaml.compose(merged.reader()) as? SequenceNode
            merged = if (root != null && root.value.isEmpty()) {
                merged.replaceRange(merged.offsetByCodePoints(0, root.startMark.index),
                    merged.offsetByCodePoints(0, root.endMark.index), block.trimEnd())
            } else merged.trimEnd() + "\n" + block
        }
        // Validate the result before writing anything to disk.
        rows(merged)
        return merged
    }

    private fun field(row: MappingNode, key: String): NodeTuple? =
        row.value.lastOrNull { (it.keyNode as? ScalarNode)?.value == key }

    private fun id(row: MappingNode): String? = (field(row, "id")?.valueNode as? ScalarNode)?.value
}

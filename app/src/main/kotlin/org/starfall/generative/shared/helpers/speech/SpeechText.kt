package org.starfall.generative.shared.helpers.speech

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val speechMarkdownParser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()

/** Read visible content, not Markdown syntax, link destinations or HTML tags. */
fun speechText(markdown: String, includeCodeBlocks: Boolean = false): String {
    val output = StringBuilder()
    fun visit(node: Node) {
        when (node) {
            is Text -> output.append(node.literal)
            is Code -> output.append(node.literal)
            is FencedCodeBlock -> if (includeCodeBlocks) output.append(node.literal).append('\n') else output.append('\n')
            is IndentedCodeBlock -> if (includeCodeBlocks) output.append(node.literal).append('\n') else output.append('\n')
            is SoftLineBreak, is HardLineBreak -> output.append('\n')
            is HtmlInline, is HtmlBlock, is ThematicBreak -> Unit
            else -> {
                var child = node.firstChild
                while (child != null) {
                    visit(child)
                    child = child.next
                }
                if (node is Paragraph || node is Heading || node is ListItem ||
                    node.javaClass.simpleName == "TableRow") output.append('\n')
                if (node.javaClass.simpleName == "TableCell") output.append("; ")
            }
        }
    }
    visit(speechMarkdownParser.parse(markdown))
    val readable = output.toString().replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s.,!?;…’'。！？、，；]"), " ")
    if (readable.none { it.isLetterOrDigit() }) return ""
    return readable.replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\\n[ \\n]*"), "\n").trim()
}

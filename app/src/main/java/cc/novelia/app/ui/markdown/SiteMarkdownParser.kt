package cc.novelia.app.ui.markdown

import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.parser.block.*
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

internal class RatingNode(val value: Double) : CustomBlock()
internal class DetailsNode(val title: String, val fenceLength: Int) : CustomBlock() {
    var expanded = false
    var closed = false
}
internal class StrikeNode : CustomNode()

/**
 * 在 CommonMark 上注册站点扩展：评分块、可折叠 details 容器和双波浪线删除线。
 * 通过解析器节点表达结构，避免用全局字符串替换破坏嵌套内容或代码块中的字面语法。
 * details 的围栏长度随起始标记保存，闭合时由最内层尚未关闭的容器消费。
 */
internal fun configureSiteMarkdownParser(builder: Parser.Builder) {
    builder.customBlockParserFactory(object : AbstractBlockParserFactory() {
        override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
            if (state.indent >= 4) return BlockStart.none()
            val line = state.line.subSequence(state.nextNonSpaceIndex, state.line.length).toString()
            val match = Regex("^(:{3,})[ \\t]*(star|details)(?:[ \\t]+(.*))?$").matchEntire(line) ?: return BlockStart.none()
            val info = match.groupValues[3].trim()
            val parser = if (match.groupValues[2] == "details") {
                DetailsParser(DetailsNode(info.ifEmpty { "点击展开" }, match.groupValues[1].length))
            } else {
                val value = info.toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.0, 5.0) ?: 0.0
                RatingParser(RatingNode(value))
            }
            return BlockStart.of(parser).atIndex(state.line.length)
        }
    })
    builder.customDelimiterProcessor(object : DelimiterProcessor {
        override fun getOpeningCharacter() = '~'
        override fun getClosingCharacter() = '~'
        override fun getMinLength() = 2
        override fun getDelimiterUse(opener: DelimiterRun, closer: DelimiterRun) =
            if (opener.length() == 2 && closer.length() == 2) 2 else 0
        override fun process(opener: Text, closer: Text, delimiterUse: Int) {
            val strike = StrikeNode()
            var child = opener.next
            while (child != null && child !== closer) {
                val next = child.next
                strike.appendChild(child)
                child = next
            }
            opener.insertAfter(strike)
        }
    })
}

private class RatingParser(private val node: RatingNode) : AbstractBlockParser() {
    override fun getBlock() = node
    override fun tryContinue(state: ParserState): BlockContinue? {
        // The toolbar emits just one line; accept an optional closing fence as well.
        val line = state.line.subSequence(state.nextNonSpaceIndex, state.line.length).toString().trimEnd()
        return if (state.indent < 4 && line.matches(Regex(":{3,}"))) BlockContinue.finished() else BlockContinue.none()
    }
}

private class DetailsParser(private val node: DetailsNode) : AbstractBlockParser() {
    override fun getBlock() = node
    override fun isContainer() = true
    override fun canContain(childBlock: Block) = true
    override fun tryContinue(state: ParserState): BlockContinue? {
        if (node.closed) return BlockContinue.none()
        val line = state.line.subSequence(state.nextNonSpaceIndex, state.line.length).toString().trimEnd()
        if (state.activeBlockParser.block !is FencedCodeBlock && state.indent < 4 &&
            line.length >= node.fenceLength && line.all { it == ':' }) {
            // Let the innermost details container consume its own closing fence.
            var active: Node? = state.activeBlockParser.block
            while (active != null && (active !is DetailsNode || active.closed)) active = active.parent
            if (active === node) {
                // CommonMark 0.13's finished() only pops a leaf parser. Consume an empty
                // closing line first so its open paragraph closes, then end this container.
                node.closed = true
                return BlockContinue.atIndex(state.line.length)
            }
        }
        return BlockContinue.atIndex(state.index)
    }
}

/**
 * 在语法树上补齐裸链接并兼容站点旧折叠包装，不改写代码、引用定义和已有链接图片。
 * 遍历期间节点可能被移除或替换，需保存并验证 next 的父节点，避免漏处理或越过当前子树。
 */
internal fun prepareSiteMarkdown(root: Node) {
    fun visit(parent: Node) {
        if (parent is Link || parent is Image) return
        var child = parent.firstChild
        while (child != null) {
            val next = child.next
            if (child is DetailsNode) {
                // Also accept the original site's copied !! / details / !! block notation.
                fun Node?.isWrapper(): Boolean {
                    if (this !is Paragraph) return false
                    val value = StringBuilder()
                    var part = firstChild
                    while (part != null) {
                        if (part !is Text) return false
                        value.append(part.literal)
                        part = part.next
                    }
                    return value.toString().trim() == "!!"
                }
                if (child.previous.isWrapper() && child.next.isWrapper()) {
                    child.previous.unlink()
                    child.next.unlink()
                }
            }
            if (child is Text) linkify(child) else visit(child)
            child = if (next?.parent === parent) next else child.next
        }
    }
    visit(root)
}

private val bareUrl = Regex("(?:https?://|www\\.)[^\\s<>）（！？。，【】［］「」、《》★、]+", RegexOption.IGNORE_CASE)
private fun linkify(text: Text) {
    var end = 0
    for (match in bareUrl.findAll(text.literal)) {
        var url = match.value.trimEnd('.', ',', ':', ';', '!', '?', '\'', '"')
        while (url.endsWith(')') && url.count { it == ')' } > url.count { it == '(' }) url = url.dropLast(1)
        if (url.isEmpty()) continue
        if (match.range.first > end) text.insertBefore(Text(text.literal.substring(end, match.range.first)))
        val destination = if (url.startsWith("www.", true)) "https://$url" else url
        text.insertBefore(Link(destination, null).apply { appendChild(Text(url)) })
        end = match.range.first + url.length
    }
    if (end > 0) {
        if (end < text.literal.length) text.insertBefore(Text(text.literal.substring(end)))
        text.unlink()
    }
}

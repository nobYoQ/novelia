package cc.novelia.app.ui.markdown

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.CustomNode
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    private val parser = Parser.builder().also(::configureSpoilerParser).build()
    private fun spoilers(markdown: String): List<String> {
        val contents = mutableListOf<String>()
        parser.parse(markdown).accept(object : AbstractVisitor() {
            override fun visit(customNode: CustomNode) {
                if (customNode is SpoilerNode) {
                    val text = StringBuilder()
                    customNode.accept(object : AbstractVisitor() {
                        override fun visit(textNode: Text) { text.append(textNode.literal) }
                    })
                    contents += text.toString()
                } else super.visit(customNode)
            }
        })
        return contents
    }

    @Test fun chineseSpoilersSupportFormattingLinksAndMultipleRanges() {
        assertEquals(listOf("结局", "粗体和链接"), spoilers("正文!!结局!!，另一个 !!**粗体**和[链接](https://example.com)!!。"))
    }

    @Test fun codeEscapesAndUnclosedDelimitersStayLiteral() {
        assertTrue(spoilers("`!!代码!!`\n\n```\n!!代码块!!\n```\n\n\\!\\!转义\\!\\!\n\n!!未闭合").isEmpty())
        assertTrue(spoilers("![图片](https://example.com/image.png)！普通感叹号!").isEmpty())
    }

    @Test fun exhaustedSpoilerMarkersFallBackToLiteralMarkdown() {
        val input = "!!" + ('\u2000'..'\u2bff').filter {
            Character.getType(it) == Character.OTHER_PUNCTUATION.toInt()
        }.joinToString("")
        // 保留最初审查时的短文本复现用例，不用于大输入压力测试。
        assertEquals(50, input.length)
        assertTrue(spoilers(input).isEmpty())
        val plain = StringBuilder()
        parser.parse(input).accept(object : AbstractVisitor() {
            override fun visit(text: Text) { plain.append(text.literal) }
        })
        assertEquals(input, plain.toString())
        val entities = "!!" + input.drop(2).map { "&#${it.code};" }.joinToString("")
        assertTrue(spoilers(entities).isEmpty())
    }

}

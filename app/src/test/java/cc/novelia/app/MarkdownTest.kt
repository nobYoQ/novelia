package cc.novelia.app

import cc.novelia.app.data.LibraryState
import cc.novelia.app.data.SettingsBackup
import cc.novelia.app.data.appJson
import cc.novelia.app.ui.configureSpoilerParser
import cc.novelia.app.ui.SpoilerNode
import cc.novelia.app.ui.*

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

}

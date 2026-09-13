package cc.novelia.app

import cc.novelia.app.data.MarkdownLinks
import cc.novelia.app.ui.*
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class MarkdownAnchorsTest {
    private fun anchors(source: String) = MarkdownAnchors(Parser.builder().also(::configureSpoilerParser)
        .also(::configureSiteMarkdownParser).build().parse(source).also(::prepareSiteMarkdown))

    @Test fun headingsUseTheSitesChineseWhitespaceAndInlineFormattingRules() {
        val anchors = anchors("# **章节** `A+B` & [链接](https://example.com) ![忽略图片文字](a.png)\n\n## 如何生成机翻\n\n换行标题\n===\n\n# 　空格\u00a0分隔　")
        assertNotNull(anchors.find("章节-a+b-&-链接"))
        assertNotNull(anchors.find("如何生成机翻"))
        assertNotNull(anchors.find("%E5%A6%82%E4%BD%95%E7%94%9F%E6%88%90%E6%9C%BA%E7%BF%BB"))
        assertNotNull(anchors.find("换行标题"))
        assertNotNull(anchors.find("空格-分隔"))
        assertNull(anchors.find("不存在"))
    }

    @Test fun duplicatesRemainDistinctIncludingHeadingsInsideClosedDetails() {
        val anchors = anchors("## 说明\n\n::: details 隐藏\n## 说明\n:::\n\n## 说明-1\n\n## 说明")
        val first = anchors.find("说明")!!
        val second = anchors.find("说明-1")!!
        assertNotSame(first, second)
        assertTrue(second.parent is DetailsNode)
        assertFalse((second.parent as DetailsNode).expanded)
        assertNotNull(anchors.find("说明-1-1"))
        assertNotNull(anchors.find("说明-2"))
    }

    @Test fun sameArticleFragmentsPreserveEncodingAndDoNotCaptureOtherPages() {
        val page = "https://n.novelia.cc/forum/64f3d63f794cbb1321145c07"
        assertEquals("如何生成机翻", MarkdownLinks.localFragment("#%E5%A6%82%E4%BD%95%E7%94%9F%E6%88%90%E6%9C%BA%E7%BF%BB", page))
        assertEquals("标题", MarkdownLinks.localFragment("$page#标题", page))
        assertEquals("标题", MarkdownLinks.localFragment("/forum/64f3d63f794cbb1321145c07/#标题", page))
        assertEquals("100%-+", MarkdownLinks.localFragment("#100%25-%2B", page))
        assertNotNull(anchors("# `100% +`").find("100%-+"))
        assertEquals("", MarkdownLinks.localFragment("#", page))
        assertEquals("预览", MarkdownLinks.localFragment("#预览", null))
        assertNull(MarkdownLinks.localFragment("/forum/another#标题", page))
        assertNull(MarkdownLinks.localFragment("https://example.com/forum/64f3d63f794cbb1321145c07#标题", page))
        assertNull(MarkdownLinks.localFragment("$page?query=other#标题", page))
        assertNull(MarkdownLinks.localFragment(page, page))
    }
}

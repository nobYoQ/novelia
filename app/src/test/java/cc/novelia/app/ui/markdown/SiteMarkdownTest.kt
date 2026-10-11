package cc.novelia.app.ui.markdown

import cc.novelia.app.data.markdown.MarkdownLinks
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SettingsBackup
import cc.novelia.app.data.storage.appJson
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class SiteMarkdownTest {
    private val parser = Parser.builder().also(::configureSpoilerParser).also(::configureSiteMarkdownParser).build()
    private fun parse(source: String) = parser.parse(source).also(::prepareSiteMarkdown)
    private inline fun <reified T : Node> nodes(root: Node): List<T> = nodes(root, T::class.java)
    private fun <T : Node> nodes(root: Node, type: Class<T>): List<T> {
        val result = mutableListOf<T>()
        fun walk(node: Node) {
            if (type.isInstance(node)) result.add(type.cast(node)!!)
            var child = node.firstChild
            while (child != null) { walk(child); child = child.next }
        }
        walk(root)
        return result
    }

    @Test fun ratingsAcceptHalfStarsAndDoNotSwallowFollowingParagraphs() {
        val root = parse("::: star 4.5\n评分后正文\n\n::: star 5\n:::\n结束")
        assertEquals(listOf(4.5, 5.0), nodes<RatingNode>(root).map { it.value })
        assertEquals(listOf("评分后正文", "结束"), nodes<Text>(root).map { it.literal })
        assertEquals(listOf(0.0, 5.0, 0.0, 0.0), nodes<RatingNode>(parse("::: star -1\n::: star 8\n::: star abc\n::: star NaN")).map { it.value })
    }

    @Test fun forumRatingsRoundToHalfStarsAndMainRatingsKeepTheirExistingPrecision() {
        val forum = Parser.builder().also { configureSiteMarkdownParser(it, forumRatings = true) }.build()
        val source = listOf("4.24", "4.25", "4.74", "4.75", "8", "-1", "+1", ".5", "1e0", "NaN")
            .joinToString("\n") { "::: star $it" }
        assertEquals(listOf(4.0, 4.5, 4.5, 5.0, 5.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            nodes<RatingNode>(forum.parse(source)).map { it.value })
        assertEquals(4.24, nodes<RatingNode>(parse("::: star 4.24")).single().value, 0.0)
    }

    @Test fun detailsSupportTheCopiedWrapperNestedBlocksAndReferences() {
        val root = parse("!!\r\n::: details 点击展开\r\n**正文**与[链接][ref]\r\n\r\n::: details 内层\r\n!!剧透!!\r\n:::\r\n\r\n::: star 4.5\r\n:::\r\n!!\r\n\r\n[ref]: /forum/abc123\r\n\r\n结尾")
        val details = nodes<DetailsNode>(root)
        assertEquals(listOf("点击展开", "内层"), details.map { it.title })
        assertEquals(details.first(), details.last().parent)
        assertFalse(details.first().expanded)
        assertEquals(1, nodes<SpoilerNode>(root).size)
        assertEquals("/forum/abc123", nodes<Link>(root).single().destination)
        assertFalse(nodes<Paragraph>(root).joinToString { p -> nodes<Text>(p).joinToString { it.literal } }, nodes<Text>(root).any { it.literal == "!!" })
        assertEquals("结尾", nodes<Text>(root).last().literal)
    }

    @Test fun codeRemainsLiteralAndUnclosedDetailsEndAtDocumentEnd() {
        val root = parse("```md\n::: star 5\n::: details 代码\n~~文字~~ https://example.com\n:::\n```\n\n    ::: star 3\n\n`::: star 4`\n\n::: details\n正文")
        assertTrue(nodes<RatingNode>(root).isEmpty())
        assertTrue(nodes<StrikeNode>(root).isEmpty())
        assertTrue(nodes<Link>(root).isEmpty())
        assertEquals("点击展开", nodes<DetailsNode>(root).single().title)
        assertEquals("正文", nodes<Text>(root).last().literal)
    }

    @Test fun fencesInsideDetailsDoNotCloseTheDetailsContainer() {
        val root = parse("::: details 示例\n```md\n::: star 5\n:::\n```\n\n结束\n:::\n\n外部")
        val details = nodes<DetailsNode>(root).single()
        assertEquals(details, nodes<FencedCodeBlock>(root).single().parent)
        assertTrue(nodes<RatingNode>(root).isEmpty())
        assertEquals(listOf("结束"), nodes<Text>(details).map { it.literal })
        assertEquals("外部", nodes<Text>(root).last().literal)
    }

    @Test fun linkificationHonorsChinesePunctuationCodeImagesAndExistingLinks() {
        val root = parse("网址https://example.com/a，另一个www.example.org。\n\n[显式链接](https://example.net)\n\n`https://code.test` ![https://alt.test](/image.png)\n\n~~删除线~~ !!https://secret.test!!")
        assertEquals(listOf("https://example.com/a", "https://www.example.org", "https://example.net", "https://secret.test"), nodes<Link>(root).map { it.destination })
        assertEquals(1, nodes<StrikeNode>(root).size)
        assertEquals(1, nodes<SpoilerNode>(root).size)
        // 切换 details 后重新渲染，不应使自动链接嵌套或重复。
        prepareSiteMarkdown(root)
        assertEquals(4, nodes<Link>(root).size)
    }

    @Test fun relativeInternalLinksStayInAppAndExternalUrlsStayExternal() {
        assertEquals("article/abc123", MarkdownLinks.nativeRoute(MarkdownLinks.resolve("/forum/abc123?x=1")!!))
        assertNull(MarkdownLinks.nativeRoute(MarkdownLinks.resolve("/forum/abc123?x=1#reply")!!))
        assertEquals("book/wenku/abc123", MarkdownLinks.nativeRoute(MarkdownLinks.resolve("/wenku/abc123")!!))
        assertEquals("reader/syosetu/n1234/5", MarkdownLinks.nativeRoute(MarkdownLinks.resolve("//n.novelia.cc/novel/syosetu/n1234/5")!!))
        assertEquals("community", MarkdownLinks.nativeRoute(MarkdownLinks.resolve("/forum")!!))
        assertTrue(MarkdownLinks.isInternal(MarkdownLinks.resolve("/workspace/sakura")!!))
        assertNull(MarkdownLinks.nativeRoute(MarkdownLinks.resolve("/workspace/sakura")!!))
        assertFalse(MarkdownLinks.isInternal("https://example.com/forum/abc123"))
        assertNull(MarkdownLinks.nativeRoute("https://example.com/forum/abc123"))
        assertNull(MarkdownLinks.resolve("javascript:alert(1)"))
        assertNull(MarkdownLinks.resolve("file:///sdcard/test"))
        assertNull(MarkdownLinks.resolve("https://n.novelia.cc@evil.example/"))
    }

    @Test fun pageAnchorsResolveAgainstTheirArticleAndKeepTheFullDestination() {
        val page = "https://n.novelia.cc/forum/64f3d63f794cbb1321145c07"
        val url = MarkdownLinks.resolve("#%E5%A6%82%E4%BD%95%E7%94%9F%E6%88%90%E6%9C%BA%E7%BF%BB", page)!!
        assertEquals("$page#%E5%A6%82%E4%BD%95%E7%94%9F%E6%88%90%E6%9C%BA%E7%BF%BB", url)
        assertNull(MarkdownLinks.nativeRoute(url))
        assertEquals("https://n.novelia.cc/workspace/gpt", MarkdownLinks.resolve("/workspace/gpt", page))
        assertEquals("https://n.novelia.cc/forum/another", MarkdownLinks.resolve("another", page))
        assertEquals("https://example.com/#heading", MarkdownLinks.resolve("https://example.com/#heading", page))
    }

    @Test fun oldLivePreviewSettingsAreIgnoredWithoutLosingDrafts() {
        val state = appJson.decodeFromString<LibraryState>("""{"postLivePreview":true,"drafts":{"article:new":"原有草稿"}}""")
        assertEquals("原有草稿", state.drafts["article:new"])
        assertEquals("dark", appJson.decodeFromString<SettingsBackup>("""{"postLivePreview":true,"theme":"dark"}""").theme)
    }
}

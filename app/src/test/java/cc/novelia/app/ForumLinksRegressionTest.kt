package cc.novelia.app

import cc.novelia.app.data.catalog.BookLinks
import cc.novelia.app.data.catalog.SiteLink
import cc.novelia.app.data.markdown.MarkdownLinks
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.ui.markdown.configureSiteMarkdownParser
import cc.novelia.app.ui.markdown.prepareSiteMarkdown
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class ForumLinksRegressionTest {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create()))
        .also(::configureSiteMarkdownParser).build()

    @Test fun reportedBareLinksExcludeMixedWidthParentheticalDescriptions() {
        // URL 和标点来自帖子 675590fcca0084226562ac36；正文不需要依赖线上内容。
        val source = """
            https://books.fishhawk.top/novel/syosetu/n1110gn（说明）
            https://books.fishhawk.top/novel/hameln/270019(大受好评）
            https://books.fishhawk.top/novel/kakuyomu/16818023212029367457(保持评价不变，说明）
            https://n.novelia.cc/novel/pixiv/13008302
        """.trimIndent()
        val root = parser.parse(source).also(::prepareSiteMarkdown)
        val destinations = mutableListOf<String>()
        val text = StringBuilder()
        root.accept(object : AbstractVisitor() {
            override fun visit(link: Link) { destinations += link.destination; super.visit(link) }
            override fun visit(node: Text) { text.append(node.literal) }
        })
        assertEquals(listOf("book/syosetu/n1110gn", "book/hameln/270019",
            "book/kakuyomu/16818023212029367457", "book/pixiv/13008302"), destinations.map(MarkdownLinks::nativeRoute))
        assertTrue(text.contains("(大受好评）"))
        assertTrue(text.contains("(保持评价不变，说明）"))
    }

    @Test fun reportedTableLinksAcceptBothOldAndCurrentHosts() {
        // 混合域名与链接位置来自帖子 67eead898f8151329eaeebf9。
        val source = """
            | 书名 | 推荐指数 | 书评 |
            | :- | :- | :- |
            | [旧链接](https://books.fishhawk.top/novel/syosetu/n2544jm) | ::: star 5 | 说明 |
            |[另一个旧链接](https://books.fishhawk.top/novel/pixiv/s20401120)|::: star 4|说明|
            | [新链接](https://n.novelia.cc/novel/kakuyomu/16818792440198448037) | ::: star 3 | [相关链接](https://n.novelia.cc/novel/syosetu/n5175in) |
        """.trimIndent()
        val destinations = mutableListOf<String>()
        parser.parse(source).also(::prepareSiteMarkdown).accept(object : AbstractVisitor() {
            override fun visit(link: Link) { destinations += link.destination }
        })
        assertEquals(listOf("book/syosetu/n2544jm", "book/pixiv/s20401120",
            "book/kakuyomu/16818792440198448037", "book/syosetu/n5175in"), destinations.map(MarkdownLinks::nativeRoute))
    }

    @Test fun sharedLinksAndMarkdownUseTheSameLegacyHostMapping() {
        assertEquals(SiteLink.Book(BookRef("syosetu", "n1110gn")), BookLinks.parse("分享 https://books.fishhawk.top/novel/syosetu/n1110gn"))
        assertEquals(SiteLink.Book(BookRef("syosetu", "n1110gn"), "5"), BookLinks.parse("HTTP://BOOKS.FISHHAWK.TOP:80/novel/syosetu/n1110gn/5"))
        assertEquals("reader/syosetu/n1110gn/5", MarkdownLinks.nativeRoute("//books.fishhawk.top/novel/syosetu/n1110gn/5"))
        assertEquals("book/wenku/abc123", MarkdownLinks.nativeRoute("https://books.fishhawk.top/wenku/abc123"))
        assertEquals("article/675590fcca0084226562ac36", MarkdownLinks.nativeRoute("https://books.fishhawk.top/forum/675590fcca0084226562ac36"))
    }

    @Test fun legacyWebFallbackPreservesEncodedQueriesAndAnchors() {
        val suffix = "/forum/675590fcca0084226562ac36?keyword=%E4%B8%AD%E6%96%87&next=a%2Fb#%E7%9B%AE%E5%BD%95"
        val old = "http://books.fishhawk.top$suffix"
        assertEquals("https://n.novelia.cc$suffix", MarkdownLinks.resolve(old))
        assertTrue(MarkdownLinks.isInternal(old))
        assertNull(MarkdownLinks.nativeRoute(old))
        assertEquals("https://n.novelia.cc/forum?page=2", MarkdownLinks.resolve("//books.fishhawk.top/forum?page=2"))
        assertNull(MarkdownLinks.nativeRoute("https://books.fishhawk.top/forum?page=2"))
        assertEquals("https://n.novelia.cc/workspace/sakura", MarkdownLinks.resolve("https://books.fishhawk.top/workspace/sakura"))
        assertNull(MarkdownLinks.nativeRoute("https://books.fishhawk.top/workspace/sakura"))
    }

    @Test fun oldAndCurrentArticleAnchorsReferToTheSameDocument() {
        val page = "https://n.novelia.cc/forum/675590fcca0084226562ac36"
        val oldPage = page.replace("n.novelia.cc", "books.fishhawk.top")
        assertEquals("目录", MarkdownLinks.localFragment("$oldPage#目录", page))
        assertEquals("$page#%E7%9B%AE%E5%BD%95", MarkdownLinks.resolve("#目录", oldPage))
        assertEquals("https://n.novelia.cc/novel/syosetu/n1110gn", MarkdownLinks.resolve("/novel/syosetu/n1110gn", oldPage))
    }

    @Test fun parenthesesInRealUrlsAndExplicitLinksRemainIntact() {
        val destinations = mutableListOf<String>()
        val source = "https://example.org/wiki/A_(B)，https://example.org/a(b(c))。\n\n[链接](https://example.org/a(b))\n\n`https://books.fishhawk.top/novel/syosetu/n1110gn`"
        parser.parse(source).also(::prepareSiteMarkdown).accept(object : AbstractVisitor() {
            override fun visit(link: Link) { destinations += link.destination }
        })
        assertEquals(listOf("https://example.org/wiki/A_(B)", "https://example.org/a(b(c))", "https://example.org/a(b)"), destinations)
    }

    @Test fun lookalikesCredentialsAndNonstandardPortsAreNotSiteLinks() {
        listOf("https://books.fishhawk.top.attacker.test", "https://attacker.test", "https://books.fishhawk.top:8443",
            "https://n.novelia.cc:8443", "https://user@books.fishhawk.top", "ftp://books.fishhawk.top").forEach { origin ->
            val url = "$origin/novel/syosetu/n1110gn"
            assertFalse(url, MarkdownLinks.isInternal(url))
            assertNull(url, MarkdownLinks.nativeRoute(url))
            assertNull(url, BookLinks.parse(url))
        }
        val external = "https://example.org/novel/syosetu/n1110gn"
        assertEquals(external, MarkdownLinks.resolve(external))
    }
}

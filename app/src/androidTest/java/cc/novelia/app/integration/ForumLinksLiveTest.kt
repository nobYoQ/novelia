package cc.novelia.app.integration

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.markdown.MarkdownLinks
import cc.novelia.app.data.community.Article
import cc.novelia.app.data.network.NoveliaApi
import cc.novelia.app.ui.markdown.configureSiteMarkdownParser
import cc.novelia.app.ui.markdown.configureSpoilerParser
import cc.novelia.app.ui.markdown.prepareSiteMarkdown
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Link
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 显式 live=true 时，仅匿名读取用户报告的两篇公开文章，不请求书籍或执行云端写入。 */
class ForumLinksLiveTest {
    @Test fun reportedBareBookList() = checkArticle("675590fcca0084226562ac36")
    @Test fun reportedTableBookList() = checkArticle("67eead898f8151329eaeebf9")

    private fun checkArticle(id: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val article = runBlocking { NoveliaApi(null).get<Article>("article/$id") }
        val pattern = Regex("https?://(?:books\\.fishhawk\\.top|n\\.novelia\\.cc)/novel/([a-z]+)/([a-zA-Z0-9_-]+)")
        val expected = pattern.findAll(article.content).map { "book/${it.groupValues[1]}/${it.groupValues[2]}" }.toList()
        assertTrue("帖子应同时包含新旧域名", article.content.contains("books.fishhawk.top") && article.content.contains("n.novelia.cc"))
        assertTrue("帖子应包含多个书籍链接", expected.size > 10)
        val parser = Parser.builder().extensions(listOf(TablesExtension.create()))
            .also(::configureSpoilerParser).also(::configureSiteMarkdownParser).build()
        val links = mutableListOf<String>()
        parser.parse(article.content).also(::prepareSiteMarkdown).accept(object : AbstractVisitor() {
            override fun visit(link: Link) {
                if (pattern.containsMatchIn(link.destination)) links += link.destination
            }
        })
        assertEquals("每个正文链接都应得到对应原生书籍路由", expected, links.map { url ->
            val resolved = MarkdownLinks.resolve(url, "https://n.novelia.cc/forum/$id")
            assertNotNull("无法解析：$url", resolved)
            assertTrue("未转换为当前域名：$url", resolved!!.startsWith("https://n.novelia.cc/"))
            MarkdownLinks.nativeRoute(resolved)
        })
        val legacy = links.count { URI(it).host == "books.fishhawk.top" }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\n$id: ${links.size} links passed (legacy=$legacy, current=${links.size - legacy})\n")
        })
    }
}

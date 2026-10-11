package cc.novelia.app.reader

import cc.novelia.app.data.catalog.BookLinks
import cc.novelia.app.data.catalog.SearchExpression
import cc.novelia.app.data.catalog.SiteLink
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.storage.appJson
import org.junit.Assert.*
import org.junit.Test

class ReaderAndLinksTest {
    @Test fun bilingualFallbackKeepsOriginalIndices() {
        val chapter = Chapter(paragraphs = listOf("原文一", "原文二", "原文三"), sakuraParagraphs = listOf("译文一", "", "译文三"), gptParagraphs = listOf("另译一", "补全二"))
        val result = projectParagraphs(chapter, ReaderSettings(mode = "zh-jp"))
        assertEquals(listOf(0, 1, 2), result.map { it.index })
        assertEquals("补全二", result[1].parts.first().text)
        assertTrue(result[1].parts.last().secondary)
        assertFalse(result.any { it.fallback })
    }
    @Test fun missingTranslationIsNeverPresentedAsTranslated() {
        val result = projectParagraphs(Chapter(paragraphs = listOf("日本語")), ReaderSettings(mode = "zh"))
        assertTrue(result.single().fallback)
        assertEquals("原文 · 暂无译文", result.single().parts.single().source)
    }
    @Test fun parallelTranslationDoesNotShiftParagraphAlignment() {
        val result = projectParagraphs(Chapter(paragraphs = listOf("一", "二"), sakuraParagraphs = listOf("S一"), gptParagraphs = listOf("G一", "G二")), ReaderSettings(parallel = true))
        assertEquals(listOf("S一", "G一"), result[0].parts.map { it.text })
        assertEquals("G二", result[1].parts.single().text)
    }
    @Test fun sourceImageMarkerIsAnImageInEveryLanguageMode() {
        val result = projectParagraphs(Chapter(paragraphs = listOf("前文", "<图片>https://example.org/illustration.png", "后文")), ReaderSettings(mode = "zh"))
        assertEquals("https://example.org/illustration.png", result[1].imageUrl)
        assertTrue(result[1].parts.isEmpty())
        assertEquals(2, result[2].index)
    }
    @Test fun supportedLinksNormalizeToSiteIdentifiers() {
        val samples = mapOf(
            "https://kakuyomu.jp/works/12345" to BookRef("kakuyomu", "12345"),
            "https://ncode.syosetu.com/N6093EN/1/" to BookRef("syosetu", "n6093en"),
            "https://novelup.plus/story/123" to BookRef("novelup", "123"),
            "https://syosetu.org/novel/456/" to BookRef("hameln", "456"),
            "https://www.pixiv.net/novel/show.php?id=789" to BookRef("pixiv", "s789"),
            "https://www.alphapolis.co.jp/novel/12/34" to BookRef("alphapolis", "12-34")
        )
        samples.forEach { (url, expected) -> assertEquals(expected, (BookLinks.parse(url) as SiteLink.Book).ref) }
    }
    @Test fun rejectsLookalikeHostsAndPathInjection() {
        assertNull(BookLinks.parse("https://n.novelia.cc.attacker.test/novel/syosetu/123"))
        assertNull(BookLinks.parse("javascript:alert(1)"))
        assertNull(BookLinks.parse("https://n.novelia.cc/novel/syosetu/%2e%2e"))
    }
    @Test fun optionalServerFieldsDoNotBreakDecoding() {
        val detail = appJson.decodeFromString<WebDetail>("""{"titleJp":"原文","titleZh":null,"authors":[],"toc":[{"titleJp":"第一卷"},{"chapterId":"abc","titleJp":"一"}],"baidu":100}""")
        assertEquals("原文", detail.title)
        assertNull(detail.toc.first().chapterId)
        assertEquals("abc", detail.toc.last().chapterId)
    }
    @Test fun advancedSearchFollowsSiteQuerySyntax() {
        val expression = SearchExpression.build("旅人 少女", "幻想 日常", "森林 小屋", "悲剧", "冒険", "残酷描写", "20", "500")
        assertEquals("旅人 少女 (幻想 | 日常) \"森林 小屋\" -悲剧 冒険$ -残酷描写$ >20 <500", expression)
    }
}

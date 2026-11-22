package cc.novelia.app.sharing

import cc.novelia.app.data.catalog.BookLinks
import cc.novelia.app.data.catalog.SiteLink
import cc.novelia.app.data.model.BookRef
import org.junit.Assert.*
import org.junit.Test

class CardQrTest {
    private val web = BookRef("kakuyomu", "12345678")
    private val local = BookRef("local", "only-on-my-phone")

    @Test fun chapterAndVolumeLinksResolveBackToTheirPublicDestination() {
        val target = bookShareLink(web, chapter = "23456789")!!
        assertEquals(SiteLink.Book(web, "23456789"), BookLinks.parse(target))
        val wenku = BookRef("wenku", "testbook")
        assertEquals(wenku.url, bookShareLink(local, wenku.key, "internal-file-chapter"))
        assertNull(bookShareLink(local))
        assertNull(bookShareLink(local, "local-series/container"))
        assertEquals(web.url, bookShareLink(web, chapter = "bad/chapter"))
    }

    @Test fun screenshotMarginAndUnrelatedCodes() {
        val targets = listOf("https://forum.novelia.cc/p/51", web.url, "${web.url}/23456789", "https://example.org/")
        for (target in targets) {
            val width = 720
            val height = 1280
            val pixels = IntArray(width * height) { 0xffdce8d4.toInt() }
            val code = cardQr(target)
            val unit = 3
            for (y in 0 until code.height * unit) for (x in 0 until code.width * unit) {
                pixels[(850 + y) * width + 458 + x] = if (code[x / unit, y / unit]) 0xff000000.toInt() else -1
            }
            assertEquals(target.takeIf { BookLinks.parse(it) != null }, readCardLink(width, height, pixels))
        }
        assertNull(readCardLink(300, 300, IntArray(90_000) { -1 }))
    }
}

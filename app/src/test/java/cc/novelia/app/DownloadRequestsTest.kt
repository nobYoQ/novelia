package cc.novelia.app

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.network.NoveliaApi
import cc.novelia.app.ui.downloads.downloadEntries
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class DownloadRequestsTest {
    private val api = NoveliaApi(null)
    private val book = BookCard(BookRef("wenku", "series"), "测试文库")

    @Test fun batchCreatesOneTaskPerDistinctVolumeWithSharedOptionsAndSourceBook() {
        val volumes = listOf("第1卷 空格.epub", "第2卷.txt", "第1卷 空格.epub")
        val entries = downloadEntries(api, book, volumes, "zh-jp", "gpt", true, "epub")
        assertEquals(volumes.distinct(), entries.map { it.title })
        assertEquals(2, entries.map { it.id }.toSet().size)
        assertEquals(2, entries.map { it.fileName }.toSet().size)
        entries.forEach { entry ->
            val url = entry.url.toHttpUrl()
            assertEquals(listOf("api", "wenku", "series", "file", entry.title), url.pathSegments)
            assertEquals("zh-jp", url.queryParameter("mode"))
            assertEquals("parallel", url.queryParameter("translationsMode"))
            assertEquals(listOf("gpt", "sakura", "youdao"), url.queryParameterValues("translations"))
            assertEquals("${entry.id}-${url.queryParameter("filename")}", entry.fileName)
            assertEquals(book.ref, entry.sourceBook)
            assertEquals(book, entry.sourceCard)
        }
        assertTrue(entries[0].fileName.endsWith(".epub"))
        assertTrue(entries[1].fileName.endsWith(".txt"))
    }

    @Test fun webDownloadWithoutVolumesKeepsItsFormatAndSanitizesOnlyTheFilename() {
        val web = BookCard(BookRef("syosetu", "n1234"), "书名:一/二")
        val entry = downloadEntries(api, web, emptyList(), "jp", "sakura", false, "txt").single()
        val url = entry.url.toHttpUrl()
        assertEquals(listOf("api", "novel", "syosetu", "n1234", "file"), url.pathSegments)
        assertEquals("txt", url.queryParameter("type"))
        assertEquals("priority", url.queryParameter("translationsMode"))
        assertEquals("jp.书名_一_二.txt", url.queryParameter("filename"))
        assertEquals(web.title, entry.title)
    }

    @Test fun specialCharactersInVolumeIdsRemainIntactInTheRequestPath() {
        val volume = "第1卷 / #?.epub"
        val entry = downloadEntries(api, book, listOf(volume), "zh", "youdao", false, "epub").single()
        assertEquals(volume, entry.url.toHttpUrl().pathSegments.last())
        assertEquals("zh.第1卷 _ #_.epub", entry.url.toHttpUrl().queryParameter("filename"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun wenkuDownloadRequiresASelectedVolume() {
        downloadEntries(api, book, emptyList(), "zh", "sakura", false, "epub")
    }
}

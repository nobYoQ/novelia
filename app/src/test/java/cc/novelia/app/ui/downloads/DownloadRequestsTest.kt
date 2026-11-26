package cc.novelia.app.ui.downloads

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.network.NoveliaApi
import cc.novelia.app.files.downloads.withUsableFileName
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class DownloadRequestsTest {
    private val api = NoveliaApi(null)
    private val book = BookCard(BookRef("wenku", "series"), "测试文库", authors = listOf("作者甲"), artists = listOf("画师乙"))

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

    @Test fun newAndRetriedDownloadsKeepTheOriginalVolumeAndFitTheUtf8FilenameLimit() {
        val volume = "分卷📖".repeat(50) + ".epub"
        val entry = downloadEntries(api, book, listOf(volume), "zh-jp", "gpt", true, "epub").single()
        val oldLabel = "zh-jp.$volume"
        val oldUrl = entry.url.toHttpUrl().newBuilder().setQueryParameter("filename", oldLabel).build()
        val repaired = entry.copy(fileName = "${entry.id}-$oldLabel", url = oldUrl.toString(), status = "失败").withUsableFileName()
        assertEquals(entry.fileName, repaired.fileName)
        assertTrue(repaired.fileName.startsWith("${entry.id}-zh-jp."))
        assertTrue(repaired.fileName.endsWith(".epub"))
        assertTrue(repaired.fileName.toByteArray().size <= 255)
        assertEquals(repaired.fileName, repaired.fileName.toByteArray().decodeToString())
        assertEquals(volume, repaired.title)
        assertEquals(volume, repaired.url.toHttpUrl().pathSegments.last())
        assertEquals(entry.url.toHttpUrl().queryParameter("filename"), repaired.url.toHttpUrl().queryParameter("filename"))
        assertEquals(entry.url.toHttpUrl().queryParameterValues("translations"), repaired.url.toHttpUrl().queryParameterValues("translations"))
        assertEquals(repaired, repaired.withUsableFileName())
    }
}

package cc.novelia.app

import cc.novelia.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CloudFavoritesTest {
    @Test fun aggregateFolderAppearsOnlyForMultipleFolders() {
        val folders = listOf(Folder("default", "默认收藏"), Folder("reading", "在读"))
        assertEquals(listOf("all", "default", "reading"), cloudFolderChoices(folders).map { it.id })
        assertEquals(folders.take(1), cloudFolderChoices(folders.take(1)))
        assertTrue(cloudFolderChoices(emptyList()).isEmpty())
    }

    @Test fun allWebFavoritesCarryEveryServerFilterAndPage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":4,"items":[{"providerId":"syosetu","novelId":"n1","titleJp":"原题","favored":"reading"}]}"""))
            val result = NoveliaApi(null, server.url("/api/").toString()).cloudFavorites(false, "all", 2, "create", CloudWebFilter("  标题 作者  ", "syosetu,pixiv", 2, 2, 1))
            val request = server.takeRequest()
            val url = request.requestUrl!!
            assertEquals("/api/user/favored-web/all", url.encodedPath)
            mapOf("page" to "2", "pageSize" to "20", "sort" to "create", "query" to "标题 作者", "provider" to "syosetu,pixiv", "type" to "2", "level" to "2", "translate" to "1").forEach { (key, value) -> assertEquals(key, value, url.queryParameter(key)) }
            assertEquals(4, result.pageNumber)
            assertEquals("reading", result.items.single().favored)
        }
    }

    @Test fun emptySourceDoesNotSilentlySelectEveryProvider() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":0,"items":[]}"""))
            NoveliaApi(null, server.url("/api/").toString()).cloudFavorites(false, "default", 0, filter = CloudWebFilter(source = ""))
            assertEquals("", server.takeRequest().requestUrl!!.queryParameter("provider"))
        }
    }

    @Test fun wenkuDoesNotSendUnsupportedWebFilters() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":1,"items":[]}"""))
            NoveliaApi(null, server.url("/api/").toString()).cloudFavorites(true, "all", 0, filter = CloudWebFilter("keyword", type = 3))
            val url = server.takeRequest().requestUrl!!
            assertEquals("/api/user/favored-wenku/all", url.encodedPath)
            assertEquals(setOf("page", "pageSize", "sort"), url.queryParameterNames)
        }
    }

    @Test fun oldStateAndNewReadingAnchorsRoundTrip() {
        assertFalse(appJson.decodeFromString<ReaderSettings>("{}").eInkMode)
        val state = LibraryState(reader = ReaderSettings(eInkMode = true), autoCollapseCloudFilters = false,
            positions = mapOf("local/book" to Position("chapter", 2, textOffset = 120)))
        assertEquals(state, appJson.decodeFromString<LibraryState>(appJson.encodeToString(state)))
        assertEquals(0, appJson.decodeFromString<Position>("""{"chapterId":"chapter","index":2}""").textOffset)
    }
}

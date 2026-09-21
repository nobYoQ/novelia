package cc.novelia.app

import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.auth.AuthenticationSession
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.network.CloudWebFilter
import cc.novelia.app.data.network.NoveliaApi
import cc.novelia.app.data.network.cloudFavorites
import cc.novelia.app.data.network.cloudFolderChoices
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.library.CloudBookMetadataLoader
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.ui.components.bookRowStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CloudFavoritesTest {
    @Test fun realFavoriteResponseWithoutHistoryIsEnrichedFromTheChapterDetail() = runBlocking {
        val session = object : AuthenticationSession {
            override fun capture() = SessionBinding("alice", 1)
            override fun tokenFor(binding: SessionBinding): String? = null
            override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?) = false
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":1,"items":[{"providerId":"syosetu","novelId":"n1","titleJp":"测试作品","lastReadAt":null,"total":4}]}"""))
            server.enqueue(MockResponse().setBody("""{"lastReadChapterId":"c","toc":[{"titleJp":"卷标题"},{"chapterId":"a"},{"chapterId":"b"},{"chapterId":"c","createAt":1700000000},{"chapterId":"d","createAt":1700000100}]}"""))
            val api = NoveliaApi(session, server.url("/api/").toString())
            val outline = api.cloudFavorites(false, "all", 0).items.single()
            assertNull(bookRowStatus(outline, null, null, null, "alice").progress)
            val resolved = CloudBookMetadataLoader(session) { api.get<WebDetail>("novel/${it.key}") }.load(outline)
            assertEquals("/api/user/favored-web/all", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/api/novel/syosetu/n1", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(.75f, bookRowStatus(resolved, null, null, null, "alice").progress!!, .0001f)
            assertEquals("读到第 3 章", bookRowStatus(resolved, null, null, null, "alice").progressLabel)
            assertEquals(1_700_000_100L, resolved.updateAt)
            assertEquals(outline.title, resolved.title)
        }
    }
    @Test fun cloudFavoritesPreserveTheReadingMarkerForTheRequestAccount() = runBlocking {
        val session = object : AuthenticationSession {
            override fun capture() = SessionBinding("alice", 1)
            override fun tokenFor(binding: SessionBinding): String? = null
            override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?) = false
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":1,"items":[{"providerId":"syosetu","novelId":"n1","lastReadAt":1700000000}]}"""))
            val card = NoveliaApi(session, server.url("/api/").toString()).cloudFavorites(false, "all", 0).items.single()
            assertEquals("alice", card.cloudReading?.account)
            assertEquals(1_700_000_000L, card.cloudReading?.lastReadAt)
            assertTrue(card.cloudReading!!.hasHistory)
        }
    }
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

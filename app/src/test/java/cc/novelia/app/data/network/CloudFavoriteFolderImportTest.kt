package cc.novelia.app.data.network

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.library.withCloudFavoritesAddedByCloudFolders
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CloudFavoriteFolderImportTest {
    private val web = BookCard(BookRef("syosetu", "n1"), "网络小说")
    private val wenku = BookCard(BookRef("wenku", "w1"), "文库小说")

    @Test fun knownFolderIdsUseCurrentNamesAndKeepWebAndWenkuNamespacesSeparate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"favoredWeb":[{"id":"same","title":"网络新名称"}],"favoredWenku":[{"id":"same","title":"文库新名称"}]}"""))
            val api = NoveliaApi(null, server.url("/api/").toString())
            val result = api.resolveCloudFavoriteFolders(listOf(web.copy(favored = "same"), wenku.copy(favored = "same")))
            val local = LibraryState().withCloudFavoritesAddedByCloudFolders(result.books, result.folders)
            assertEquals(listOf("网络新名称", "文库新名称"), local.books.map { it.folder })
            assertEquals("/api/user/favored", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun missingMembershipIsResolvedAcrossFolderPagesWithoutImportingUnselectedBooks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"favoredWeb":[{"id":"web-folder","title":"网络分组"}],"favoredWenku":[{"id":"wenku-folder","title":"文库分组"}]}"""))
            server.enqueue(MockResponse().setBody("""{"pageNumber":2,"items":[{"providerId":"syosetu","novelId":"unselected"}]}"""))
            server.enqueue(MockResponse().setBody("""{"pageNumber":2,"items":[{"providerId":"syosetu","novelId":"n1","titleJp":"旧标题"}]}"""))
            server.enqueue(MockResponse().setBody("""{"pageNumber":1,"items":[{"id":"w1"}]}"""))
            val progress = mutableListOf<Int>()
            val result = NoveliaApi(null, server.url("/api/").toString())
                .resolveCloudFavoriteFolders(listOf(web, wenku.copy(favored = "all"), web), onProgress = progress::add)
            assertEquals(listOf(web.copy(favored = "web-folder"), wenku.copy(favored = "wenku-folder")), result.books)
            assertEquals(listOf(0, 0, 1, 2), progress)
            val urls = List(4) { server.takeRequest().requestUrl!! }
            assertEquals(listOf("/api/user/favored", "/api/user/favored-web/web-folder", "/api/user/favored-web/web-folder", "/api/user/favored-wenku/wenku-folder"), urls.map { it.encodedPath })
            assertEquals("1", urls[2].queryParameter("page"))
            assertEquals("", urls[1].queryParameter("query"))
            assertEquals("0", urls[1].queryParameter("level"))
        }
    }

    @Test fun fullImportCanPreserveCloudFoldersAcrossAllPages() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":2,"items":[{"providerId":"syosetu","novelId":"n1","favored":"default"}]}"""))
            server.enqueue(MockResponse().setBody("""{"pageNumber":2,"items":[{"providerId":"hameln","novelId":"n2","favored":"reading"}]}"""))
            server.enqueue(MockResponse().setBody("""{"favoredWeb":[{"id":"default","title":"默认收藏"},{"id":"reading","title":"正在阅读"}],"favoredWenku":[]}"""))
            val api = NoveliaApi(null, server.url("/api/").toString())
            val result = api.resolveCloudFavoriteFolders(api.allCloudFavorites(false))
            val local = LibraryState().withCloudFavoritesAddedByCloudFolders(result.books, result.folders)
            assertEquals(listOf("默认收藏", "正在阅读"), local.books.map { it.folder })
            assertEquals(listOf("默认收藏", "正在阅读"), local.folders)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun unresolvedMembershipFailsInsteadOfGuessingADefaultFolder() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"favoredWeb":[{"id":"default","title":"默认收藏"}]}"""))
            server.enqueue(MockResponse().setBody("""{"pageNumber":0,"items":[]}"""))
            try {
                NoveliaApi(null, server.url("/api/").toString()).resolveCloudFavoriteFolders(listOf(web.copy(favored = "deleted-folder")))
                fail("无法确认归属时不能把书籍放入猜测的收藏夹")
            } catch(expected: IllegalStateException) { assertTrue(expected.message!!.contains("无法确认 1 本")) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun folderLookupFailureDoesNotReturnPartialResults() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"favoredWeb":[{"id":"default","title":"默认收藏"}]}"""))
            server.enqueue(MockResponse().setResponseCode(403))
            try {
                NoveliaApi(null, server.url("/api/").toString()).resolveCloudFavoriteFolders(listOf(web))
                fail("归属查询失败必须中止整批导入")
            } catch(expected: ApiException) { assertEquals(403, expected.status) }
        }
    }

    @Test fun sessionChangeDuringFolderLookupRejectsTheResolvedBatch() = runBlocking {
        var current = true
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"favoredWenku":[{"id":"default","title":"默认收藏"}]}"""))
            server.enqueue(MockResponse().setBody("""{"pageNumber":1,"items":[{"id":"w1"}]}"""))
            try {
                NoveliaApi(null, server.url("/api/").toString()).resolveCloudFavoriteFolders(listOf(wenku),
                    validate = { if(!current) throw SessionChangedException() }) { if(it == 1) current = false }
                fail("归属确认后也必须校验会话")
            } catch(expected: SessionChangedException) { assertEquals(2, server.requestCount) }
        }
    }
}

package cc.novelia.app.data.network

import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.cache.*
import cc.novelia.app.data.community.Article
import cc.novelia.app.data.community.ForumPage
import cc.novelia.app.data.community.ForumPost
import cc.novelia.app.data.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MetadataMutationTest {
    @Test fun singleHistoryDeletionUsesTheBookEndpointAndInvalidatesHistoryAndItsDetail() = runBlocking {
        MockWebServer().use { server ->
            val store = UiResultStore()
            val binding = SessionBinding("reader", 1)
            val first = DetailUiKey("novel/syosetu/n1", binding, 0, "normal")
            val other = first.copy(path = "novel/syosetu/n2")
            store.history.put("page", Page(1, listOf(BookCard(BookRef("syosetu", "n1"), "小说"))))
            store.webDetails.put(first, WebDetail())
            store.webDetails.put(other, WebDetail())
            val api = NoveliaApi(null, server.url("/api/").toString(), onResourceMutation = store::novelChanged)
            server.enqueue(MockResponse().setResponseCode(204))
            api.request("DELETE", "user/read-history/syosetu/n1")
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/user/read-history/syosetu/n1", request.path)
            assertFalse(store.history.snapshot("page")!!.fresh)
            assertFalse(store.webDetails.snapshot(first)!!.fresh)
            assertTrue(store.webDetails.snapshot(other)!!.fresh)
        }
    }

    @Test fun readingHistoryInvalidatesOnlyTheReadBookWithoutExpiringCatalogMetadata() = runBlocking {
        MockWebServer().use { server ->
            val events = mutableListOf<MetadataMutation>()
            val api = NoveliaApi(null, server.url("/api/").toString(), onResourceMutation = events::add)
            server.enqueue(MockResponse().setBody("{}"))
            api.request("PUT", "user/read-history/syosetu/n1", "chapter")
            assertTrue(api.detailMutationAt("novel/syosetu/n1") > 0)
            assertEquals(0L, api.detailMutationAt("novel/syosetu/n2"))
            assertEquals(0L, api.catalogMutationAt)
            assertEquals(setOf("novel/syosetu/n1"), events.single().detailPaths)
            server.enqueue(MockResponse().setResponseCode(403))
            runCatching { api.request("PUT", "novel/syosetu/n2", "{}") }
            assertEquals(1, events.size)
            assertEquals(0L, api.detailMutationAt("novel/syosetu/n2"))
        }
    }

    @Test fun favoriteFolderAndContentMutationsHaveSeparateScopes() {
        fun event(method: String, path: String) = MetadataMutation(method, path, null, 100)
        assertEquals(setOf("wenku/book"), event("PUT", "user/favored-wenku/default/book").detailPaths)
        assertEquals(setOf("novel/syosetu/book"), event("DELETE", "user/favored-web/default/syosetu/book").detailPaths)
        assertEquals(emptySet<String>(), event("PUT", "user/favored-web/folder").detailPaths)
        assertEquals(emptySet<String>(), event("PUT", "user/read-history/paused").detailPaths)
        assertNull(event("DELETE", "user/favored-web/folder").detailPaths)
        assertNull(event("DELETE", "user/read-history").detailPaths)
        val content = event("PUT", "novel/syosetu/book/glossary")
        assertTrue(content.catalog)
        assertEquals(setOf("novel/syosetu/book"), content.detailPaths)
    }

    @Test fun readingKeepsOtherUiDetailsAndForumWritesInvalidateTheFeed() {
        val store = UiResultStore()
        val binding = SessionBinding("reader", 1)
        val first = DetailUiKey("novel/syosetu/n1", binding, 0, "normal")
        val other = first.copy(path = "novel/syosetu/n2")
        store.webDetails.put(first, WebDetail())
        store.webDetails.put(other, WebDetail())
        store.discoverPages.put("search", Page(1, emptyList()))
        store.novelChanged(MetadataMutation("PUT", "user/read-history/syosetu/n1", binding, 100))
        assertFalse(store.webDetails.snapshot(first)!!.fresh)
        assertNotNull(store.webDetails.snapshot(other))
        assertNotNull(store.discoverPages.snapshot("search"))
        store.articles.put("post", Article())
        store.forumPages.put("feed", ForumPage<ForumPost>(0, emptyList()))
        store.forumChanged()
        assertFalse(store.articles.snapshot("post")!!.fresh)
        assertFalse(store.forumPages.snapshot("feed")!!.fresh)
    }

    @Test fun detailsCannotCrossAccountsSourcesRolesOrClearGenerations() {
        val cache = UiResultStore().webDetails
        val key = DetailUiKey("novel/syosetu/n1", SessionBinding("alice", 1), 0, "admin")
        cache.put(key, WebDetail())
        assertNull(cache.snapshot(key.copy(binding = SessionBinding("bob", 1))))
        assertNull(cache.snapshot(key.copy(binding = key.binding.copy(source = "mirror"))))
        assertNull(cache.snapshot(key.copy(binding = key.binding.copy(generation = 2))))
        assertNull(cache.snapshot(key.copy(role = "normal")))
        assertNull(cache.snapshot(key.copy(generation = 1)))
    }
}

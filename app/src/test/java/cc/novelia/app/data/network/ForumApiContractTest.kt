package cc.novelia.app.data.network

import cc.novelia.app.data.auth.*
import cc.novelia.app.data.catalog.BookLinks
import cc.novelia.app.data.catalog.SiteLink
import cc.novelia.app.data.catalog.ForumLinks
import cc.novelia.app.data.markdown.MarkdownLinks
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ForumApiContractTest {
    private val post = """{"id":9007199254740993,"categoryId":7,"title":"测试帖子","authorId":42,"authorUsername":"读者","status":0,"viewsCount":3,"commentsCount":21,"commentsLocked":true,"pinOrder":0,"favorited":true,"createdAt":"2026-09-14T18:31:15.123456Z","updatedAt":"2026-09-15T02:31:15+08:00","activeAt":"2026-09-14T18:31:15Z","tags":[{"id":9,"name":"讨论","color":2}],"content":"# 正文"}"""
    private val comment = """{"id":12,"postId":9007199254740993,"rootId":8,"content":"回复","authorId":42,"authorUsername":"读者","status":0,"createdAt":"2026-09-14T18:31:15Z","updatedAt":"2026-09-14T18:31:15Z"}"""
    private fun api(server: MockWebServer, session: ApiSession? = null) = ForumApi(NoveliaApi(session, server.url("/api/v1/").toString()))

    @Test fun listUsesSlugOneBasedPagingAndServerSearch() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"total":21,"items":[$post]}"""))
            val result = api(server).posts(0, "novel", "中文 & 标签", "newest", listOf(9, 10))
            val request = server.takeRequest()
            assertEquals("/api/v1/post/", request.requestUrl!!.encodedPath)
            assertEquals("1", request.requestUrl!!.queryParameter("page"))
            assertEquals("20", request.requestUrl!!.queryParameter("page_size"))
            assertEquals("novel", request.requestUrl!!.queryParameter("category"))
            assertEquals("中文 & 标签", request.requestUrl!!.queryParameter("q"))
            assertEquals("9,10", request.requestUrl!!.queryParameter("tag"))
            assertEquals("newest", request.requestUrl!!.queryParameter("sort"))
            assertNull(request.getHeader("Authorization"))
            assertEquals(2, result.pageCount())
            val article = result.items.single().article(listOf(ForumCategory(7, "novel")))
            assertEquals("f-9007199254740993", article.id)
            assertEquals("小说讨论", article.category)
            assertEquals(Instant.parse("2026-09-14T18:31:15Z").epochSecond, article.createAt)
            assertEquals(article.createAt, article.updateAt)
            assertTrue(article.pinned && article.locked && article.forumFavorited)
            assertEquals(listOf(9L), article.forumTags.map { it.id })
        }
    }

    @Test fun dynamicCategoriesAndEmptyPagesDecode() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":7,"slug":"novel","tags":[{"id":9,"name":"标签","color":1,"sortOrder":2}]}]"""))
            server.enqueue(MockResponse().setBody("""{"total":0,"items":[]}"""))
            val client = api(server)
            assertEquals(7L, client.categories().single().id)
            assertEquals("/api/v1/category/", server.takeRequest().path)
            assertEquals(0, client.posts(1, "novel").pageCount())
            assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("page"))
            assertEquals(1, ForumPage<ForumPost>(20, emptyList()).pageCount())
            assertTrue(runCatching { appJson.decodeFromString<ForumPage<ForumPost>>("""{"pageNumber":1,"items":[]}""") }.isFailure)
        }
    }

    @Test fun postCreateAndPatchSendNumericIdsAndPreserveTags() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(201).setBody(post)) }
            val client = api(server)
            val input = ForumPostInput(7, "标题", "正文", listOf(9))
            val created = client.createPost(input)
            client.updatePost(created.id, input)
            val create = server.takeRequest(); val update = server.takeRequest()
            assertEquals("POST", create.method); assertEquals("/api/v1/post/", create.path)
            assertEquals("PATCH", update.method); assertEquals("/api/v1/post/9007199254740993/", update.path)
            for(request in listOf(create, update)) {
                val body = appJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(JsonPrimitive(7), body["categoryId"])
                assertEquals(JsonArray(listOf(JsonPrimitive(9))), body["tagIds"])
                assertFalse(body.containsKey("category"))
            }
        }
    }

    @Test fun commentsAreFlatAndReplyUsesRootId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"total":21,"items":[$comment]}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody(comment))
            server.enqueue(MockResponse().setBody(comment))
            val client = api(server)
            val list = client.comments(5, 1)
            val reply = list.items.single()
            client.createComment(5, ForumCommentInput("嵌套回复", reply.replyRoot))
            client.updateComment(12, "编辑")
            val read = server.takeRequest(); val write = server.takeRequest(); val edit = server.takeRequest()
            assertEquals("/api/v1/post/5/comment?page=2&page_size=20", read.path)
            assertNull(read.requestUrl!!.queryParameter("parentId"))
            assertEquals("/api/v1/post/5/comment", write.path)
            val body = appJson.parseToJsonElement(write.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive(8), body["rootId"])
            assertFalse(body.containsKey("site") || body.containsKey("parent"))
            assertEquals("PATCH", edit.method); assertEquals("/api/v1/comment/12", edit.path)
            assertEquals("""{"content":"编辑"}""", edit.body.readUtf8())
            val user = Profile("读者", "member", 0, Long.MAX_VALUE, 42)
            assertTrue(reply.canModify(user, reply.createdEpoch + 1199))
            assertFalse(reply.canModify(user, reply.createdEpoch + 1200))
            assertFalse(reply.canModify(user.copy(userId = 43), reply.createdEpoch))
        }
    }

    @Test fun favoritesAndDeletesAcceptEmpty204Responses() = runBlocking {
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setResponseCode(204)) }
            val client = api(server)
            client.favorite(5, true); client.favorite(5, false); client.deletePost(5); client.deleteComment(12)
            assertEquals("PUT" to "/api/v1/post/5/favorite", server.takeRequest().let { it.method to it.path })
            assertEquals("DELETE" to "/api/v1/post/5/favorite", server.takeRequest().let { it.method to it.path })
            assertEquals("DELETE" to "/api/v1/post/5/", server.takeRequest().let { it.method to it.path })
            assertEquals("DELETE" to "/api/v1/comment/12", server.takeRequest().let { it.method to it.path })
        }
    }

    @Test fun accountListsAndExternalCommentSubjectAreEncoded() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody("""{"total":0,"items":[]}""")) }
            val client = api(server)
            client.favorites(0); client.myPosts(1); client.externalComments("web-kakuyomu-中文 /id", 0)
            assertEquals("/api/v1/me/favorite?page=1&page_size=20", server.takeRequest().path)
            assertEquals("/api/v1/me/post?page=2&page_size=20", server.takeRequest().path)
            val path = server.takeRequest().requestUrl!!
            assertEquals("web-kakuyomu-中文 /id", path.pathSegments.last())
            assertFalse(path.encodedPath.contains("%25E"))
        }
    }

    private class FakeSession(override val target: AuthTarget) : ApiSession {
        private val profile = Profile("reader", "member", 0, Long.MAX_VALUE, 42)
        val state = SessionState("forum-test-session", profile)
        override val token: String? get() = state.token
        var refreshes = 0
        override fun capture() = state.capture()
        override fun tokenFor(binding: SessionBinding) = state.tokenFor(binding)
        override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?): Boolean {
            tokenFor(binding)
            refreshes++
            return state.commit(binding, "refreshed-test-session", profile, false)
        }
    }

    @Test fun forumDiscardsResponsesFromAnEarlierLoginWithoutRetrying() = runBlocking {
        for(status in listOf(200, 401)) MockWebServer().use { server ->
            val session = FakeSession(AuthTarget.FORUM)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    session.state.clear()
                    session.state.commit(session.capture(), "new-login", Profile("reader", "member", 0, Long.MAX_VALUE, 42), true)
                    return MockResponse().setResponseCode(status).setBody(post)
                }
            }
            assertTrue(runCatching { api(server, session).post(5) }.exceptionOrNull() is SessionChangedException)
            assertEquals(0, session.refreshes)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun forumRejectsMainSessionAndRefreshesOnlyForumTokenOnce() = runBlocking {
        MockWebServer().use { server ->
            assertTrue(runCatching { api(server, FakeSession(AuthTarget.NOVEL)) }.isFailure)
            val session = FakeSession(AuthTarget.FORUM)
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))
            val error = runCatching { api(server, session).post(5) }.exceptionOrNull()
            assertEquals(401, (error as ApiException).status)
            assertEquals(1, session.refreshes)
            assertEquals("Bearer forum-test-session", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer refreshed-test-session", server.takeRequest().getHeader("Authorization"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun failedWritesAreNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val error = runCatching { api(server).createPost(ForumPostInput(7, "标题", "正文")) }.exceptionOrNull()
            assertEquals(503, (error as ApiException).status)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun oldSavedArticlesAndNewLinksKeepSeparateIdentities() {
        val old = appJson.decodeFromString<Article>("""{"id":"abc123","category":"General","title":"旧收藏"}""")
        assertNull(old.forumCategoryId)
        assertEquals(old, appJson.decodeFromString<Article>(appJson.encodeToString(old)))
        assertEquals(SiteLink.Post("f-123"), BookLinks.parse("https://forum.novelia.cc/p/123"))
        assertEquals(SiteLink.Post("abc123"), BookLinks.parse("https://n.novelia.cc/forum/abc123"))
        assertNull(BookLinks.parse("https://forum.novelia.cc/p/123/edit"))
        assertNull(BookLinks.parse("https://forum.novelia.cc/p/0"))
        assertNull(BookLinks.parse("https://forum.novelia.cc.evil.test/p/123"))
        assertEquals("article/f-123", MarkdownLinks.nativeRoute("https://forum.novelia.cc/p/123"))
        assertNull(MarkdownLinks.nativeRoute("https://forum.novelia.cc/p/123#comment-8"))
        assertNull(MarkdownLinks.nativeRoute("https://forum.novelia.cc/c/guide?page=2"))
        assertEquals("https://forum.novelia.cc/p/123", MarkdownLinks.commentDocumentUrl("article-f-123"))
        assertEquals("https://forum.novelia.cc/p/456", MarkdownLinks.resolve("/p/456", "https://forum.novelia.cc/p/123"))
        assertEquals("https://n.novelia.cc/forum/abc123", ForumLinks.articleUrl(old.id))
    }
}

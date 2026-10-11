package cc.novelia.app.data.community

import cc.novelia.app.data.auth.ApiSession
import cc.novelia.app.data.auth.AuthTarget
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.auth.SessionState
import cc.novelia.app.data.model.Profile
import cc.novelia.app.data.storage.appJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import cc.novelia.app.data.network.ApiException
import cc.novelia.app.data.network.NoveliaApi

class NovelCommentApiTest {
    private val comment = """{"id":9007199254740993,"subjectKey":"web-kakuyomu-book","rootId":null,"content":"小说讨论","authorId":77,"authorUsername":"读者","status":0,"createdAt":"2026-10-06T00:00:00Z","updatedAt":"2026-10-06T00:00:00Z","replyCount":1,"replies":{"total":1,"items":[{"id":9007199254740994,"subjectKey":"web-kakuyomu-book","rootId":9007199254740993,"content":"首屏回复","authorId":78,"authorUsername":"回复者","status":0,"createdAt":"2026-10-06T00:00:01Z","updatedAt":"2026-10-06T00:00:01Z"}]}}"""
    private val emptyPage = """{"total":0,"items":[]}"""
    private fun api(server: MockWebServer, session: ApiSession? = null) =
        NovelCommentApi(NoveliaApi(session, server.url("/api/v1/").toString()))

    @Test fun webAndWenkuCommentsUseMigratedEndpointAndDecodeReplyPreviews() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"total":1,"items":[$comment]}"""))
            server.enqueue(MockResponse().setBody(emptyPage))
            val api = api(server)
            val result = api.comments("web-kakuyomu-book", 0)
            assertEquals(9007199254740993L, result.items.single().id)
            assertEquals(1L, result.items.single().replyCount)
            assertEquals("首屏回复", result.items.single().replies!!.items.single().content)
            assertEquals("/api/v1/external/comment/novel/web-kakuyomu-book?page=1&page_size=20", server.takeRequest().path)
            api.comments("wenku-中文 /分卷", 2)
            val request = server.takeRequest().requestUrl!!
            assertEquals("wenku-中文 /分卷", request.pathSegments.last())
            assertEquals("3", request.queryParameter("page"))
            assertFalse(request.encodedPath.contains("%25E"))
        }
    }

    @Test fun repliesHaveIndependentPagesAndFallbackCountsUseOneItem() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(emptyPage))
            server.enqueue(MockResponse().setBody("""{"total":25,"items":[]}"""))
            val api = api(server)
            api.replies("wenku-book", 9007199254740993L, 1)
            assertEquals("/api/v1/external/comment/novel/wenku-book/9007199254740993/reply?page=2&page_size=20", server.takeRequest().path)
            assertEquals(25L, api.replyCount("wenku-book", 9007199254740993L))
            assertEquals("/api/v1/external/comment/novel/wenku-book/9007199254740993/reply?page=1&page_size=1", server.takeRequest().path)
        }
    }

    @Test fun novelSessionIsUsedForPostingEditingAndDeleting() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody(comment)) }
            server.enqueue(MockResponse().setResponseCode(204))
            val session = FakeSession(AuthTarget.NOVEL)
            val api = api(server, session)
            api.createComment("web-provider-book", ForumCommentInput("新评论"))
            api.createComment("wenku-book", ForumCommentInput("回复", 9007199254740993L))
            api.updateComment(9007199254740994L, "修改后的评论")
            api.deleteComment(9007199254740994L)
            val requests = List(4) { server.takeRequest() }
            assertEquals(listOf("POST", "POST", "PATCH", "DELETE"), requests.map { it.method })
            assertEquals("/api/v1/external/comment/novel/web-provider-book", requests[0].path)
            assertEquals("/api/v1/external/comment/novel/wenku-book", requests[1].path)
            assertEquals("9007199254740993", appJson.parseToJsonElement(requests[1].body.readUtf8()).jsonObject.getValue("rootId").jsonPrimitive.content)
            assertEquals(setOf("content"), appJson.parseToJsonElement(requests[2].body.readUtf8()).jsonObject.keys)
            assertEquals("/api/v1/external/comment/novel/9007199254740994", requests[2].path)
            assertEquals(requests[2].path, requests[3].path)
            assertTrue(requests.all { it.getHeader("Authorization") == "Bearer synthetic-novel-session" })
        }
    }

    @Test fun forumSessionCannotBeSentToNovelComments() {
        MockWebServer().use { server ->
            assertThrows(IllegalArgumentException::class.java) { api(server, FakeSession(AuthTarget.FORUM)) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun sharedDiscussionViewKeepsNovelAndForumRoutesAndSessionsSeparate() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody(emptyPage)) }
            val novel = api(server, FakeSession(AuthTarget.NOVEL)).discussion("wenku-book")
            val forum = ForumApi(NoveliaApi(FakeSession(AuthTarget.FORUM), server.url("/api/v1/").toString())).discussion(5)
            novel.comments(0)
            forum.comments(0)
            assertNotEquals(novel.key, forum.key)
            val novelRequest = server.takeRequest()
            val forumRequest = server.takeRequest()
            assertEquals("/api/v1/external/comment/novel/wenku-book?page=1&page_size=20", novelRequest.path)
            assertEquals("/api/v1/post/5/comment?page=1&page_size=20", forumRequest.path)
            assertEquals("Bearer synthetic-novel-session", novelRequest.getHeader("Authorization"))
            assertEquals("Bearer synthetic-forum-session", forumRequest.getHeader("Authorization"))
        }
    }

    @Test fun permissionFailureKeepsItsReasonWithoutRepeatingPost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "text/plain")
                .setBody("评论只能在发布后 20 分钟内编辑或删除"))
            val error = runCatching { api(server).deleteComment(17) }.exceptionOrNull() as ApiException
            assertEquals(403, error.status)
            assertEquals("评论只能在发布后 20 分钟内编辑或删除", error.message)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun invalidRootAndPageAreRejectedBeforeAnyRequest() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server)
            assertTrue(runCatching { api.comments("wenku-book", Int.MAX_VALUE) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { api.replies("wenku-book", 0, 0) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { api.createComment("wenku-book", ForumCommentInput("回复", 0)) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, server.requestCount)
        }
    }

    private class FakeSession(override val target: AuthTarget) : ApiSession {
        private val state = SessionState(if(target == AuthTarget.NOVEL) "synthetic-novel-session" else "synthetic-forum-session",
            Profile("读者", "member", 0, Long.MAX_VALUE, 77))
        override val token get() = state.token
        override fun capture() = state.capture()
        override fun tokenFor(binding: SessionBinding) = state.tokenFor(binding)
        override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?) = false
    }
}

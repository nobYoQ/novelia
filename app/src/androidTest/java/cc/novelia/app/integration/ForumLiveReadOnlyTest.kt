package cc.novelia.app.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.network.ForumApi
import cc.novelia.app.data.network.NoveliaApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -e live true. Public GETs only; no account/session is attached. */
@RunWith(AndroidJUnit4::class)
class ForumLiveReadOnlyTest {
    @Test fun previewCategoriesPostsAndCommentThreadsDecode() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val api = ForumApi(NoveliaApi(null, ForumApi.BASE_URL))
        val categories = api.categories()
        assertTrue(categories.isNotEmpty())
        for(category in categories) {
            val page = api.posts(0, category.slug, pageSize = 1)
            assertTrue(page.total >= page.items.size)
            page.items.firstOrNull()?.let { outline ->
                val post = api.post(outline.id)
                assertEquals(outline.id, post.id)
                assertTrue(post.article(categories).id.startsWith("f-"))
                val comments = api.comments(post.id, 0)
                assertTrue(comments.total >= comments.items.size)
                comments.items.forEach {
                    assertEquals(post.id, it.postId); assertTrue(it.createdEpoch > 0)
                    assertNull(it.rootId)
                    val count = it.replyCount
                    assertNotNull("10 月 1 日起一级评论应始终返回 replyCount", count)
                    assertTrue(count != null && count >= 0)
                    if(count != null && count > 0 || it.id == comments.items.first().id) {
                        val replies = api.replies(post.id, it.id, 0)
                        assertTrue(replies.total >= replies.items.size)
                        replies.items.forEach { reply -> assertEquals(post.id, reply.postId); assertEquals(it.id, reply.rootId) }
                    }
                }
            }
        }
    }
}

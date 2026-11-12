package cc.novelia.app.ui.community

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.navigation.compose.rememberNavController
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.auth.AuthTarget
import cc.novelia.app.data.auth.DeviceCipher
import cc.novelia.app.data.auth.Session
import cc.novelia.app.data.community.*
import cc.novelia.app.data.network.NoveliaApi
import cc.novelia.app.ui.markdown.MarkdownCommentInput
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.LocalReducedMotion
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ForumCommentEditingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun savingRootWithFocusedInputRefreshesAndClosesEditor() = saveComment(reply = false, reduced = false)
    @Test fun savingReplyWithFocusedInputRefreshesAndClosesEditor() = saveComment(reply = true, reduced = false)
    @Test fun savingRootInStaticModeRefreshesAndClosesEditor() = saveComment(reply = false, reduced = true)
    @Test fun savingReplyInStaticModeRefreshesAndClosesEditor() = saveComment(reply = true, reduced = true)
    @Test fun failedReplySaveKeepsTheDraftAndAllowsRetry() = saveComment(reply = true, reduced = false, fail = true)
    @Test fun typingDuringReplySaveKeepsTheNewDraftAndEditor() = saveComment(reply = true, reduced = false, continueTyping = true)
    @Test fun leavingBeforeReplySaveCompletesPreservesTheNextPagesFocus() = saveComment(reply = true, reduced = false, leaveBeforeResponse = true)

    private fun saveComment(reply: Boolean, reduced: Boolean, fail: Boolean = false, continueTyping: Boolean = false,
        leaveBeforeResponse: Boolean = false) {
        val app = compose.activity.application as NoveliaApplication
        runBlocking { app.initialization.await() }
        val prefix = "comment-edit-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
        }
        val preferences = context.getSharedPreferences(AuthTarget.FORUM.preferencesName, Context.MODE_PRIVATE)
        val payload = """{"sub":"评论测试","role":"member","uid":42,"crat":0,"exp":4102444800}"""
        val encoded = Base64.encodeToString(payload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        preferences.edit().putString("value", DeviceCipher("novelia.forum.session").encrypt("e30.$encoded.synthetic")).commit()
        val session = Session(context, AuthTarget.FORUM)
        val createdAt = Instant.now().minusSeconds(30).toString()
        val child = ForumComment(102, postId = 1, rootId = 101, content = "原始回复", authorId = 42,
            authorUsername = "评论测试", status = 0, createdAt = createdAt, updatedAt = createdAt)
        val root = child.copy(id = 101, rootId = null, content = "原始评论", replyCount = 1,
            replies = ForumPage(1, listOf(child)))
        val response = CompletableDeferred<Unit>()
        val current = AtomicReference(root)
        val reads = AtomicInteger()
        val writes = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
        lateinit var controller: AppController
        val discussion = object : CommentDiscussion {
            override val key = prefix
            override val blacklist = ForumBlacklistApi(NoveliaApi(null))
            override suspend fun comments(page: Int): ForumPage<ForumComment> {
                reads.incrementAndGet()
                return ForumPage(1, listOf(current.get()))
            }
            override suspend fun replies(rootId: Long, page: Int) = current.get().replies!!
            override suspend fun replyCount(rootId: Long) = 1L
            override suspend fun create(input: ForumCommentInput): ForumComment = error("Unexpected create")
            override suspend fun delete(id: Long) = error("Unexpected delete")
            override suspend fun update(id: Long, content: String): ForumComment = withContext(Dispatchers.IO) {
                writes += id to content
                response.await()
                val updated = (if(reply) child else root).copy(content = content)
                current.set(if(reply) root.copy(replies = ForumPage(1, listOf(updated))) else updated)
                // 与真实论坛 PATCH 相同：请求成功先使页面缓存失效，再结束提交。
                controller.uiResults.forumChanged()
                updated
            }
        }
        val draftKey = "$prefix:42:edit-${if(reply) child.id else root.id}"
        var showing by mutableStateOf(true)
        try {
            compose.activity.setContent {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    NoveliaTheme("light") {
                        val nav = rememberNavController()
                        val scope = rememberCoroutineScope()
                        val c = remember { AppController(app, nav, scope, SnackbarHostState()) }
                        controller = c
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            if(!showing) MarkdownCommentInput("另一个页面", {}, "新的输入框") {}
                            else ArticleContentPager(1, {}, 2, rememberSaveableStateHolder()) { page ->
                                if(page == 1) DiscussionCommentsPanel(c, discussion, session, false, null) { it() }
                            }
                        }
                    }
                }
            }
            if(reply) compose.onNodeWithTag("forum-replies-toggle-101").performClick()
            compose.onAllNodesWithText("编辑")[if(reply) 1 else 0].performClick()
            val submitted = "修改后的评论 😀 纯文本"
            compose.onNodeWithTag("comment-body").performTextReplacement(submitted)
            compose.onNodeWithTag("comment-body").assertIsFocused()
            compose.onNodeWithTag("forum-comment-submit").performClick()
            compose.waitUntil(5_000) { writes.isNotEmpty() }
            compose.onNodeWithTag("forum-comment-submit").assertIsNotEnabled()
            // PATCH 触发缓存失效之前，输入框已结束当前焦点/IME 会话。
            compose.onNodeWithTag("comment-body").assertIsNotFocused()
            val unsent = "请求期间继续修改，保留新草稿"
            if(continueTyping) compose.onNodeWithTag("comment-body").performTextReplacement(unsent)
            if(leaveBeforeResponse) {
                compose.runOnIdle { showing = false }
                compose.onNodeWithTag("comment-body").performClick().assertIsFocused()
            }
            compose.runOnIdle {
                assertEquals(listOf((if(reply) child.id else root.id) to submitted), writes)
                if(fail) response.completeExceptionally(IOException("模拟保存失败")) else response.complete(Unit)
            }
            if(leaveBeforeResponse) {
                compose.waitUntil(5_000) { !app.store.state.value.drafts.containsKey(draftKey) }
                compose.onNodeWithTag("comment-body").assertIsFocused().assertTextContains("另一个页面")
                return
            }
            if(fail || continueTyping) {
                val expected = if(continueTyping) unsent else submitted
                compose.onNodeWithTag("comment-editor").assertExists()
                compose.onNodeWithTag("comment-body").assertTextContains(expected)
                compose.onNodeWithTag("forum-comment-submit").assertIsEnabled()
                compose.runOnIdle { assertEquals(expected, app.store.state.value.drafts[draftKey]) }
                return
            }
            compose.onNodeWithTag("comment-editor").assertDoesNotExist()
            compose.onNodeWithContentDescription("写评论").assertExists().performClick()
            compose.onNodeWithTag("comment-body").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.waitUntil(5_000) { reads.get() >= 2 }
            compose.runOnIdle {
                assertFalse(app.store.state.value.drafts.containsKey(draftKey))
            }
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            app.store.update { it.copy(drafts = it.drafts.filterKeys { key -> !key.startsWith(prefix) }) }
            preferences.edit().clear().commit()
        }
    }
}

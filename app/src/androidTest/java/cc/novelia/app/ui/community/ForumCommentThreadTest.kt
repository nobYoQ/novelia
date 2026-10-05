package cc.novelia.app.ui.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.data.model.ForumComment
import cc.novelia.app.data.model.ForumPage
import cc.novelia.app.data.model.Profile
import cc.novelia.app.data.network.loadForumReplyCounts
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForumCommentThreadTest {
    @get:Rule val compose = createComposeRule()
    private val root = ForumComment(8, postId = 5, content = "一级评论", authorId = 42,
        authorUsername = "读者", status = 0, createdAt = "2026-09-30T00:00:00Z",
        updatedAt = "2026-09-30T00:00:00Z", replyCount = 21)
    private fun reply(id: Long, content: String, author: String = "回复者") = root.copy(
        id = id, rootId = root.id, content = content, authorUsername = author, replyCount = 0)

    @Test fun repliesLoadOnlyWhenExpandedAndHaveIndependentPages() {
        val pages = mutableListOf<Int>()
        compose.setContent {
            NoveliaTheme("light") {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ForumCommentThread(5, root, null, 0, loadReplies = { page ->
                        pages += page
                        ForumPage(21, listOf(reply(12 + page.toLong(), "回复页 $page")))
                    }) { comment, _, actions -> Column { Text(comment.content); actions?.invoke() } }
                }
            }
        }
        compose.runOnIdle { assertTrue(pages.isEmpty()) }
        compose.onNodeWithText("查看 21 条回复").performClick()
        compose.onNodeWithText("回复页 0").assertExists()
        compose.onNodeWithContentDescription("下一页").performScrollTo().performClick()
        compose.onNodeWithText("回复页 1").assertExists()
        compose.runOnIdle { assertEquals(listOf(0, 1), pages) }
        compose.onNodeWithText("收起回复").performScrollTo().performClick()
        compose.onNodeWithText("回复页 1").assertDoesNotExist()
        compose.onNodeWithText("查看 21 条回复").performClick()
        compose.onNodeWithText("回复页 1").assertExists()
        compose.runOnIdle { assertEquals(listOf(0, 1), pages) }
    }

    @Test fun changingViewerRoleClearsPreviouslyLoadedAdminContent() {
        val viewer = mutableStateOf(Profile("读者", "admin", 0, Long.MAX_VALUE, 42))
        compose.setContent {
            NoveliaTheme("light") {
                ForumCommentThread(5, root, viewer.value, 0, loadReplies = {
                    ForumPage(1, listOf(reply(12, if(viewer.value.role == "admin") "管理员可见原文" else "公开回复")))
                }) { comment, _, actions -> Column { Text(comment.content); actions?.invoke() } }
            }
        }
        compose.onNodeWithText("查看 21 条回复").performClick()
        compose.onNodeWithText("管理员可见原文").assertExists()
        compose.runOnIdle { viewer.value = viewer.value.copy(role = "member") }
        compose.onNodeWithText("管理员可见原文").assertDoesNotExist()
        compose.onNodeWithText("查看 21 条回复").performClick()
        compose.onNodeWithText("公开回复").assertExists()
    }

    @Test fun hiddenRootKeepsExistingRepliesReadableAndDisallowsNewReplies() {
        compose.setContent {
            NoveliaTheme("light") {
                ForumCommentThread(5, root.copy(status = 1), null, 0, loadReplies = {
                    ForumPage(1, listOf(reply(12, "已有回复")))
                }) { comment, rootPublished, actions -> Column { Text("${comment.content}:$rootPublished"); actions?.invoke() } }
            }
        }
        compose.onNodeWithText("一级评论:false").assertExists()
        compose.onNodeWithText("查看 21 条回复").performClick()
        compose.onNodeWithText("已有回复:false").assertExists()
    }

    @Test fun blockingReplyAuthorsDoesNotChangeServerPagination() {
        compose.setContent {
            NoveliaTheme("light") {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ForumCommentThread(5, root, null, 0, blockedUsers = setOf("屏蔽者"), loadReplies = {
                        ForumPage(21, listOf(reply(12, "应隐藏", "屏蔽者"), reply(13, "应保留")))
                    }) { comment, _, actions -> Column { Text(comment.content); actions?.invoke() } }
                }
            }
        }
        compose.onNodeWithText("查看 21 条回复").performClick()
        compose.onNodeWithText("应隐藏").assertDoesNotExist()
        compose.onNodeWithText("应保留").assertExists()
        compose.onNodeWithContentDescription("下一页").performScrollTo().assertIsEnabled()
    }

    @Test fun failedReplyLoadCanBeRetriedWithoutLosingTheRoot() {
        var attempts = 0
        compose.setContent {
            NoveliaTheme("light") {
                ForumCommentThread(5, root, null, 0, loadReplies = {
                    if(attempts++ == 0) throw IOException("测试连接中断")
                    ForumPage(1, listOf(reply(12, "重试成功")))
                }) { comment, _, actions -> Column { Text(comment.content); actions?.invoke() } }
            }
        }
        compose.onNodeWithText("查看 21 条回复").performClick()
        compose.onNodeWithText("一级评论").assertExists()
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("重试成功").assertExists()
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test fun newlyCreatedReplyOpensItsThreadOnTheTargetPage() {
        val pages = mutableListOf<Int>()
        compose.setContent {
            NoveliaTheme("light") {
                ForumCommentThread(5, root, null, 0, focus = ForumReplyFocus(root.id, 1, 77), loadReplies = { page ->
                    pages += page
                    ForumPage(21, listOf(reply(77, "刚发布的回复")))
                }) { comment, _, actions -> Column { Text(comment.content); actions?.invoke() } }
            }
        }
        compose.onNodeWithText("收起回复").assertExists()
        compose.onNodeWithText("刚发布的回复").assertExists()
        compose.runOnIdle { assertEquals(listOf(1), pages) }
    }

    @Test fun omittedReplyCountStillAllowsReadingReplies() {
        compose.setContent {
            NoveliaTheme("light") {
                ForumCommentThread(5, root.copy(replyCount = null), null, 0, loadReplies = {
                    ForumPage(1, listOf(reply(12, "没有计数也能读取")))
                }) { comment, _, actions -> Column { Text(comment.content); actions?.invoke() } }
            }
        }
        compose.onNodeWithText("查看回复（数量暂不可用）").performClick()
        compose.onNodeWithText("没有计数也能读取").assertExists()
        compose.onNodeWithText("收起回复").performClick()
        compose.onNodeWithText("查看 1 条回复").assertExists()
    }

    @Test fun newlyCreatedReplyFindsItsLastPageWhenTheRootCountIsOmitted() {
        val pages = mutableListOf<Int>()
        compose.setContent {
            NoveliaTheme("light") {
                ForumCommentThread(5, root.copy(replyCount = null), null, 0,
                    focus = ForumReplyFocus(root.id, 0, 77), loadReplies = { page ->
                        pages += page
                        ForumPage(21, listOf(reply(if(page == 0) 12 else 77, if(page == 0) "旧回复" else "新回复末页")))
                    }) { comment, _, actions -> Column { Text(comment.content); actions?.invoke() } }
            }
        }
        compose.onNodeWithText("新回复末页").assertExists()
        compose.runOnIdle { assertEquals(listOf(0, 1), pages) }
    }

    @Test fun countsAppearBeforeOpeningAndSurviveLazyItemDisposal() = assertCountsAndCacheSurviveScrolling(serverCounts = false)

    @Test fun serverCountsNeedNoPrefetchAndSurviveLazyItemDisposal() = assertCountsAndCacheSurviveScrolling(serverCounts = true)

    private fun assertCountsAndCacheSurviveScrolling(serverCounts: Boolean) {
        var countReads = 0; var bodyReads = 0
        val roots = (8L..27L).map { root.copy(id = it,
            replyCount = if(serverCounts) { if(it == 8L) 3 else 0 } else null, content = "根评论 $it") }
        val expectedCountReads = if(serverCounts) 0 else 20
        compose.setContent { NoveliaTheme("light") {
            val counts = remember { mutableStateMapOf<Long, Long?>() }
            val replyPages = rememberForumReplyPages(5)
            LaunchedEffect(Unit) {
                loadForumReplyCounts(roots, { id -> countReads++; if(id == 8L) 3L else 0L }) { id, count -> counts[id] = count }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("lazy-forum-comments")) {
                items(roots, key = { it.id }) { comment ->
                    ForumCommentThread(5, comment, null, 0, knownReplyCount = counts[comment.id],
                        replyPages = replyPages,
                        countLoading = !counts.containsKey(comment.id), onReplyCount = { counts[comment.id] = it }, loadReplies = {
                            bodyReads++; ForumPage(3, listOf(reply(31, "线程回复")))
                        }) { item, _, actions -> Column { Text(item.content, Modifier.height(180.dp)); actions?.invoke() } }
                }
            }
        } }
        compose.onNodeWithText("查看 3 条回复").assertExists()
        compose.runOnIdle { assertEquals(0, bodyReads) }
        compose.onNodeWithText("查看 3 条回复").performClick()
        compose.onNodeWithText("线程回复").assertExists()
        compose.onNodeWithText("收起回复").performClick()
        compose.onNodeWithTag("lazy-forum-comments").performScrollToIndex(19)
        compose.onNodeWithText("根评论 8").assertDoesNotExist()
        compose.onNodeWithTag("lazy-forum-comments").performScrollToIndex(0)
        compose.onNodeWithText("查看 3 条回复").assertExists()
        compose.runOnIdle { assertEquals(expectedCountReads, countReads); assertEquals(1, bodyReads) }
        compose.onNodeWithText("查看 3 条回复").performClick()
        compose.onNodeWithText("线程回复").assertExists()
        compose.onNodeWithTag("lazy-forum-comments").performScrollToIndex(19)
        compose.onNodeWithText("根评论 8").assertDoesNotExist()
        compose.onNodeWithTag("lazy-forum-comments").performScrollToIndex(0)
        compose.onNodeWithText("收起回复").assertExists()
        compose.onNodeWithText("线程回复").assertExists()
        compose.onNodeWithText("正在加载…").assertDoesNotExist()
        compose.runOnIdle { assertEquals(expectedCountReads, countReads); assertEquals(1, bodyReads) }
    }

    @Test fun serverZeroCountIsVisibleWithoutPrefetchingOrOpeningTheThread() {
        var reads = 0
        compose.setContent { NoveliaTheme("light") {
            ForumCommentThread(5, root.copy(replyCount = 0), null, 0, countLoading = true, loadReplies = {
                reads++; ForumPage(0, emptyList())
            }) { comment, _, actions -> Column { Text(comment.content); actions?.invoke() } }
        } }
        compose.onNodeWithText("暂无回复").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, reads) }
    }

    @Test fun refreshedCountOpensAnEmptyThreadAndReplyPageTotalTakesPrecedence() {
        val comment = mutableStateOf(root.copy(replyCount = 0))
        compose.setContent { NoveliaTheme("light") {
            ForumCommentThread(5, comment.value, null, 0, loadReplies = {
                ForumPage(1, listOf(reply(31, "最新回复")))
            }) { item, _, actions -> Column { Text(item.content); actions?.invoke() } }
        } }
        compose.onNodeWithText("暂无回复").assertIsNotEnabled()
        compose.runOnIdle { comment.value = root.copy(replyCount = 2) }
        compose.onNodeWithText("查看 2 条回复").performClick()
        compose.onNodeWithText("最新回复").assertExists()
        compose.onNodeWithText("收起回复").performClick()
        compose.onNodeWithText("查看 1 条回复").assertExists()
    }

    @Test fun collapsingDuringLoadingAndReopeningRestoresTheBodyAndItsCount() {
        val response = CompletableDeferred<ForumPage<ForumComment>>()
        var reads = 0
        compose.setContent { NoveliaTheme("light") {
            val replyPages = rememberForumReplyPages(5)
            var count by remember { mutableStateOf<Long?>(1) }
            ForumCommentThread(5, root.copy(replyCount = 0), null, 0, knownReplyCount = count,
                replyPages = replyPages, onReplyCount = { count = it }, loadReplies = {
                    reads++; response.await()
                }) { comment, _, actions -> Column { Text(comment.content); actions?.invoke() } }
        } }
        compose.onNodeWithText("查看 1 条回复").performClick()
        compose.onNodeWithText("收起回复").performClick()
        compose.runOnIdle { response.complete(ForumPage(3, listOf(reply(31, "后台完成的回复")))) }
        compose.onNodeWithText("查看 1 条回复").performClick()
        compose.onNodeWithText("后台完成的回复").assertExists()
        compose.onNodeWithText("收起回复").performClick()
        compose.onNodeWithText("查看 3 条回复").assertExists()
        compose.runOnIdle { assertEquals(1, reads) }
    }
}

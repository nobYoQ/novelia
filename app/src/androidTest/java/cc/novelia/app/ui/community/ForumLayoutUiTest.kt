package cc.novelia.app.ui.community

import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.community.ForumCategory
import cc.novelia.app.data.community.ForumComment
import cc.novelia.app.data.community.ForumPage
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForumLayoutUiTest {
    @get:Rule val compose = createComposeRule()
    private val categories = listOf(ForumCategory(2, "announcements"), ForumCategory(1, "novel"), ForumCategory(3, "feedback"))

    @Test fun threeCategoriesFillTheWholeRowWithEqualWidths() {
        compose.setContent { RegularScale { Box(Modifier.width(375.dp)) { ForumCategoryTabs(categories, "novel") {} } } }
        val row = compose.onNodeWithTag("forum-category-tabs").getUnclippedBoundsInRoot()
        val tabs = categories.map { compose.onNodeWithTag("forum-category-${it.slug}").getUnclippedBoundsInRoot() }
        assertEquals((tabs[0].right - tabs[0].left).value, (tabs[1].right - tabs[1].left).value, 1f)
        assertEquals((tabs[1].right - tabs[1].left).value, (tabs[2].right - tabs[2].left).value, 1f)
        assertEquals(row.right.value, tabs.last().right.value, 1f)
    }

    @Test fun narrowCategoryRowCanStillSelectAdditionalCategories() {
        val selected = mutableStateOf("novel")
        val extra = categories + ForumCategory(9, "extra")
        compose.setContent { NoveliaTheme("light") { Box(Modifier.width(240.dp)) { ForumCategoryTabs(extra, selected.value) { selected.value = it } } } }
        compose.onNodeWithTag("forum-category-extra").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("extra", selected.value) }
    }

    @Test fun searchStartsCollapsedAndRetainsQueryWhenCollapsed() {
        val expanded = mutableStateOf(false); val query = mutableStateOf("")
        compose.setContent { NoveliaTheme("light") {
            ForumFeedControls("全部帖子", query.value, expanded.value, "搜索论坛帖子",
                { expanded.value = it }, { query.value = it }) {}
        } }
        compose.onNodeWithTag("forum-search-input").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开搜索").performClick()
        compose.onNodeWithTag("forum-search-input").performTextInput("测试帖子")
        compose.onNodeWithContentDescription("收起搜索").performClick()
        compose.onNodeWithTag("forum-search-input").assertDoesNotExist()
        compose.runOnIdle { assertEquals("测试帖子", query.value) }
        compose.onNodeWithContentDescription("展开搜索").performClick()
        compose.onNodeWithTag("forum-search-input").assertTextContains("测试帖子")
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.runOnIdle { assertEquals("", query.value) }
    }

    @Test fun dismissingRulesNoticeSurvivesStoreReload() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "forum-notice-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        val store = LocalStore(context)
        try {
            compose.setContent { NoveliaTheme("light") {
                val state by store.state.collectAsState()
                ForumRulesNotice(!state.forumRulesReminderDismissed, {}) { store.update { it.copy(forumRulesReminderDismissed = true) } }
            } }
            compose.onNodeWithTag("forum-rules-notice").assertExists()
            compose.onNodeWithContentDescription("不再显示社区守则提示").performClick()
            compose.onNodeWithTag("forum-rules-notice").assertDoesNotExist()
            runBlocking { store.flush() }
            assertTrue(LocalStore(context).state.value.forumRulesReminderDismissed)
        } finally { runBlocking { store.flush() }; directory.deleteRecursively() }
    }

    @Test fun nativeRulesPageShowsPolicyAndOffersStrikeRecords() {
        var strikes = 0
        compose.setContent { NoveliaTheme("light") { ForumRulesContent { strikes++ } } }
        compose.onNodeWithTag("forum-rules-page").assertExists()
        compose.onNodeWithText("一般违规行为会受到记分处罚", substring = true).assertExists()
        compose.onNodeWithText("查看处罚记录").performClick()
        compose.onNodeWithText("如果认为处罚或封禁有误", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, strikes) }
    }

    @Test fun replyAndBlockAndExpandActionsShareTheSameRow() {
        val root = ForumComment(8, postId = 5, content = "一级评论", authorId = 42, authorUsername = "作者",
            status = 0, createdAt = "2026-09-30T00:00:00Z", updatedAt = "2026-09-30T00:00:00Z")
        compose.setContent { RegularScale {
            Box(Modifier.width(375.dp)) {
                ForumCommentThread(5, root, null, 0, loadReplies = { ForumPage(0, emptyList()) }) { comment, published, actions ->
                    ForumCommentRow(comment, null, !published, {}, {}, {}, {}, actions) { Text(it) }
                }
            }
        } }
        val reply = compose.onNodeWithText("回复").getUnclippedBoundsInRoot()
        val block = compose.onNodeWithText("屏蔽用户").getUnclippedBoundsInRoot()
        val expand = compose.onNodeWithText("查看回复（数量暂不可用）").getUnclippedBoundsInRoot()
        assertEquals(reply.top.value, block.top.value, 1f)
        assertEquals(reply.top.value, expand.top.value, 1f)
    }

    @Composable private fun RegularScale(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) { NoveliaTheme("light", content = content) }
    }
}

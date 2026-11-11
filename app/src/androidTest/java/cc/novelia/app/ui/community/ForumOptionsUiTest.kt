package cc.novelia.app.ui.community

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.community.Article
import cc.novelia.app.data.community.ForumCategory
import cc.novelia.app.data.community.ForumPage
import cc.novelia.app.data.community.ForumPost
import cc.novelia.app.data.community.ForumSort
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ForumOptionsUiTest {
    @get:Rule val compose = createComposeRule()
    private val categories = listOf(ForumCategory(2, "announcements"), ForumCategory(100, "novel"), ForumCategory(3, "feedback"))

    @Test fun defaultAppliesOnFreshLoadAndSkipPinnedScrollsCachedPostsWithoutHidingThem() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        val theme = InstrumentationRegistry.getArguments().getString("forumOptionsTheme") ?: "light"
        val before = app.store.state.value
        val loadVersion = mutableIntStateOf(0)
        try {
            app.store.update { it.copy(forumDefaultCategory = "novel", forumSkipPinned = false,
                forumRulesReminderDismissed = true) }
            val binding = app.forumSession.capture()
            val cacheGeneration = app.store.cacheGeneration.value
            app.uiResults.categories.put(listOf("forum-categories", binding.source, binding.sourceRevision, cacheGeneration), categories)
            for(category in categories) {
                val posts = (1L..14L).map { post(category, it, if(it <= 4) (it - 1).toInt() else null) }
                val requestKey = listOf(category.slug, 0, "", 0, ForumSort.ACTIVE, null, binding,
                    app.forumSession.profile.value?.role, app.forumBlacklistRevision.value, cacheGeneration)
                app.uiResults.forumPages.put(requestKey, ForumPage(14, posts))
            }
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val controller = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
                NoveliaTheme(theme) { key(loadVersion.intValue) { CommunityScreen(controller) } }
            }
            compose.onNodeWithTag("forum-category-novel").assertIsSelected()
            compose.onNodeWithText("novel-置顶帖子-1").assertIsDisplayed()
            compose.onNodeWithTag("forum-options-toggle").performClick()
            screenshot("options-panel-$theme")
            compose.onNodeWithText("跳过置顶").performScrollTo().performClick()
            compose.onNodeWithContentDescription("关闭论坛选项").performClick()
            compose.onNodeWithText("novel-普通帖子-5").assertIsDisplayed()
            compose.onNodeWithTag("forum-article-list").performScrollToIndex(0)
            compose.onNodeWithText("novel-置顶帖子-1").assertIsDisplayed()
            compose.onNodeWithTag("forum-category-feedback").performClick().assertIsSelected()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("forum-category-feedback").assertIsSelected()
            compose.runOnIdle { loadVersion.intValue++ }
            compose.onNodeWithTag("forum-category-novel").assertIsSelected()
            compose.onNodeWithText("novel-普通帖子-5").assertIsDisplayed()
        } finally {
            app.store.update { it.copy(forumDefaultCategory = before.forumDefaultCategory, forumSkipPinned = before.forumSkipPinned,
                forumRulesReminderDismissed = before.forumRulesReminderDismissed) }
            runBlocking { app.store.flush() }
            app.uiResults.categories.clear()
            app.uiResults.forumPages.clear()
        }
    }

    private fun post(category: ForumCategory, id: Long, pinOrder: Int?) = ForumPost(id, category.id,
        "${category.slug}-${if(pinOrder == null) "普通帖子" else "置顶帖子"}-$id", 42, "选项测试作者", 0, 1, 0, false,
        pinOrder = pinOrder, createdAt = "2026-11-11T00:00:00Z", updatedAt = "2026-11-11T00:00:00Z", activeAt = "2026-11-11T00:00:00Z")

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "forum").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun staticJumpWaitsForActivePageAndPreservesManualReturnToPinsAfterRestore() {
        val active = mutableStateOf(false)
        val refresh = mutableIntStateOf(0)
        val articles = (0..11).map { Article("f-$it", "帖子 $it", pinned = it < 3) }
        lateinit var scroll: LazyListState
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppInteractionMode(eInk = true, reducedMotion = true) { NoveliaTheme("light") {
            scroll = rememberLazyListState()
            ForumPinnedPostScroll(articles, scroll, true, active.value, refresh.intValue)
            LazyColumn(Modifier.height(300.dp).testTag("static-post-list"), state = scroll) {
                items(articles, key = { it.id }) { Text(it.title, Modifier.fillMaxWidth().height(100.dp)) }
            }
        } } }
        compose.runOnIdle { assertEquals(0, scroll.firstVisibleItemIndex); active.value = true }
        compose.runOnIdle { assertEquals(3, scroll.firstVisibleItemIndex) }
        compose.onNodeWithTag("static-post-list").performScrollToIndex(0)
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(0, scroll.firstVisibleItemIndex); refresh.intValue++ }
        compose.runOnIdle { assertEquals(3, scroll.firstVisibleItemIndex) }
    }

    @Test fun pagesWithoutLeadingPinsKeepTheirPosition() {
        val articles = mutableStateOf((0..11).map { Article("f-$it", "帖子 $it", pinned = true) })
        val refresh = mutableIntStateOf(0)
        lateinit var scroll: LazyListState
        compose.setContent { AppInteractionMode(eInk = true, reducedMotion = true) { NoveliaTheme("light") {
            scroll = rememberLazyListState()
            ForumPinnedPostScroll(articles.value, scroll, true, true, refresh.intValue)
            LazyColumn(Modifier.height(300.dp).testTag("edge-post-list"), state = scroll) {
                items(articles.value, key = { it.id }) { Text(it.title, Modifier.fillMaxWidth().height(100.dp)) }
            }
        } } }
        compose.runOnIdle {
            assertEquals(0, scroll.firstVisibleItemIndex)
            articles.value = articles.value.map { it.copy(pinned = false) }; refresh.intValue++
        }
        compose.runOnIdle { assertEquals(0, scroll.firstVisibleItemIndex) }
        compose.onNodeWithTag("edge-post-list").performScrollToIndex(6)
        compose.runOnIdle {
            articles.value = articles.value.mapIndexed { index, article -> article.copy(pinned = index < 3) }
            refresh.intValue++
        }
        compose.runOnIdle { assertEquals(6, scroll.firstVisibleItemIndex) }
        compose.runOnIdle { articles.value = emptyList(); refresh.intValue++ }
        compose.runOnIdle { assertEquals(0, scroll.firstVisibleItemIndex) }
    }

    @Test fun categoryAndSkipPinnedCanBeChangedAndSurviveStoreReload() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "forum-options-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        val store = LocalStore(context)
        try {
            compose.setContent { NoveliaTheme("light") {
                val state by store.state.collectAsState()
                ForumOptions(categories, state.forumDefaultCategory, state.forumSkipPinned,
                    { value -> store.update { it.copy(forumDefaultCategory = value) } },
                    { value -> store.update { it.copy(forumSkipPinned = value) } }, {})
            } }
            compose.onNodeWithText("默认顺序").assertIsSelected()
            compose.onNodeWithText("小说讨论").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("跳过置顶").performScrollTo().performClick()
            runBlocking { store.flush() }
            val restored = LocalStore(context).state.value
            assertEquals("novel", restored.forumDefaultCategory)
            assertTrue(restored.forumSkipPinned)
            compose.onNodeWithText("默认顺序").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("跳过置顶").performScrollTo().performClick()
            runBlocking { store.flush() }
            assertNull(LocalStore(context).state.value.forumDefaultCategory)
            assertFalse(LocalStore(context).state.value.forumSkipPinned)
        } finally { runBlocking { store.flush() }; directory.deleteRecursively() }
    }
}

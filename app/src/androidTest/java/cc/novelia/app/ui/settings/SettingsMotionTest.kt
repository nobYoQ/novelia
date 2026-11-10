package cc.novelia.app.ui.settings

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsMotionTest {
    @get:Rule val compose = createComposeRule()
    private var eInk by mutableStateOf(false)
    private var reduced by mutableStateOf(false)
    private lateinit var back: OnBackPressedDispatcher

    @Test fun allSevenCategoriesAnimateForwardAndBackWithTheWholePage() {
        showSettings()
        val frame = compose.onNodeWithTag("settings-motion-frame").fetchSemanticsNode().boundsInRoot
        val categories = listOf("READING" to "阅读与朗读", "APPEARANCE" to "外观与操作",
            "LIBRARY" to "书架与下载", "NETWORK" to "网络与同步", "CONTENT" to "内容与屏蔽",
            "KEYWORDS" to "标签与词库", "STORAGE" to "存储与备份")
        for((id, title) in categories) {
            compose.mainClock.autoAdvance = true
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText(title).performClick()
            compose.mainClock.advanceTimeBy(80)
            val entering = page(id).fetchSemanticsNode().boundsInRoot
            assertTrue("$title 应从右侧进入", entering.left > frame.left)
            page("index").assertExists()
            assertTrue("分类首页应向左退出", page("index").fetchSemanticsNode().boundsInRoot.right < frame.right)
            // 退场首页保留绘制，但不再暴露入口或第二个返回按钮。
            compose.onNodeWithText("排版、配色与电子纸").assertDoesNotExist()
            compose.onAllNodesWithContentDescription("返回").assertCountEquals(1)
            compose.mainClock.advanceTimeBy(400)
            page("index").assertDoesNotExist()
            assertEquals(frame, page(id).fetchSemanticsNode().boundsInRoot)

            compose.onNodeWithContentDescription("返回").performClick()
            compose.mainClock.advanceTimeBy(80)
            assertTrue("返回时分类首页应从左侧进入", page("index").fetchSemanticsNode().boundsInRoot.right < frame.right)
            assertTrue("$title 应向右退出", page(id).fetchSemanticsNode().boundsInRoot.left > frame.left)
            compose.mainClock.advanceTimeBy(400)
            page(id).assertDoesNotExist()
            assertEquals(frame, page("index").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun eInkAndReducedMotionSwitchPagesWithoutAnAnimatedIntermediateFrame() {
        showSettings()
        val frame = page("index").fetchSemanticsNode().boundsInRoot
        for(ink in listOf(true, false)) {
            compose.runOnIdle { eInk = ink; reduced = !ink }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("阅读与朗读").performClick()
            // 只推进状态提交的帧数，不等待普通模式的 220 ms 转场。
            compose.mainClock.advanceTimeBy(48)
            page("index").assertDoesNotExist()
            assertEquals(frame, page("READING").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithContentDescription("返回").performClick()
            compose.mainClock.advanceTimeBy(48)
            page("READING").assertDoesNotExist()
            assertEquals(frame, page("index").fetchSemanticsNode().boundsInRoot)
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun enablingReducedMotionFinishesTransitionAndKeepsTheCurrentScrollPosition() {
        showSettings()
        val frame = page("index").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("外观与操作").performClick()
        compose.mainClock.advanceTimeBy(80)
        assertTrue(page("APPEARANCE").fetchSemanticsNode().boundsInRoot.left > frame.left)
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeBy(48)
        page("index").assertDoesNotExist()
        assertEquals(frame, page("APPEARANCE").fetchSemanticsNode().boundsInRoot)
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("剪贴板链接提示"))
        val position = compose.onNodeWithText("剪贴板链接提示").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { reduced = false }
        compose.mainClock.advanceTimeBy(48)
        assertEquals("重新开启动画不能重播当前页或重置滚动", frame, page("APPEARANCE").fetchSemanticsNode().boundsInRoot)
        assertEquals(position, compose.onNodeWithText("剪贴板链接提示").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun systemBackDuringEntryReversesTheTransitionWithoutLeavingAStalePage() {
        showSettings()
        compose.mainClock.autoAdvance = false
        repeat(3) {
            compose.onNodeWithText("阅读与朗读").performClick()
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { back.onBackPressed() }
            compose.mainClock.advanceTimeBy(400)
            page("READING").assertDoesNotExist()
            page("index").assertIsDisplayed()
            compose.onNodeWithText("外观与操作").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("返回").assertCountEquals(1)
        }
    }

    private fun showSettings() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(eInk, reduced) {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val controller = AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() })
            Box(Modifier.fillMaxSize().testTag("settings-motion-frame")) { SettingsScreen(controller) }
        } } }
    }

    private fun page(id: String) = compose.onNodeWithTag("settings-page-$id")
}

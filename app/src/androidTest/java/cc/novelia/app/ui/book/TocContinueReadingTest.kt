package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TocContinueReadingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchingOutTheCurrentChapterStillResumesItAndTheLastRowClearsTheButton() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val toc = List(50) { TocItem(titleJp = "目录章节 $it", chapterId = "$it") }
        var opened: String? = null
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val c = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
            Box(Modifier.requiredWidth(360.dp).height(580.dp)) {
                TocPanel(c, BookRef("local", "toc-continue-test"), toc, "7") { opened = it }
            }
        } } }
        compose.onNodeWithContentDescription("继续阅读").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("7", opened); opened = null }
        compose.onNodeWithText("搜索章节").performTextInput("不存在的章节")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("没有匹配的章节").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("继续阅读").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("7", opened) }
        compose.onNodeWithText("搜索章节").performTextClearance()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("目录章节 0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(49)
        val last = compose.onNodeWithText("目录章节 49").assertIsDisplayed().getUnclippedBoundsInRoot()
        val button = compose.onNodeWithTag("toc-continue-reading").getUnclippedBoundsInRoot()
        assertTrue(last.bottom <= button.top)
    }

    @Test fun anExplicitResumeActionUsesTheSameProgressChoiceAsTheSummary() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        var choseProgress = false
        var openedDirectly = false
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val c = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
            TocPanel(c, BookRef("local", "toc-continue-test"), listOf(TocItem(titleJp = "当前章", chapterId = "current")),
                "current", onContinue = { choseProgress = true }) { openedDirectly = true }
        } } }
        compose.onNodeWithContentDescription("继续阅读").performClick()
        compose.runOnIdle { assertTrue(choseProgress); assertFalse(openedDirectly) }
    }
}

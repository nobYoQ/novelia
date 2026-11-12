package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderEInkSpacingFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun horizontalDefaultSpacingControlsUpdateStoredSettingsAndReadingLayout() = verifySpacing(false, "auto")

    @Test fun horizontalPerBookSpacingControlsUpdateStoredSettingsAndReadingLayout() = verifySpacing(true, "auto")

    @Test fun scrollDefaultSpacingControlsUpdateStoredSettingsAndReadingLayout() = verifySpacing(false, "scroll")

    @Test fun scrollPerBookSpacingControlsUpdateStoredSettingsAndReadingLayout() = verifySpacing(true, "scroll")

    @Test fun rowDefaultSpacingControlsUpdateStoredSettingsAndReadingLayout() = verifySpacing(false, "row")

    @Test fun rowPerBookSpacingControlsUpdateStoredSettingsAndReadingLayout() = verifySpacing(true, "row")

    @Test fun horizontalMarginsStillProtectTheViewportInTheMiddleOfAChapter() = verifySpacing(false, "auto", true)

    @Test fun scrollMarginsStillProtectTheViewportInTheMiddleOfAChapter() = verifySpacing(false, "scroll", true)

    @Test fun rowMarginsStillProtectTheViewportInTheMiddleOfAChapter() = verifySpacing(false, "row", true)

    @Test fun normalHorizontalSpacingAndMarginsUpdateTheReadingLayout() = verifySpacing(false, "auto", true, false)

    @Test fun normalRowSpacingAndMarginsUpdateTheReadingLayout() = verifySpacing(false, "row", true, false)

    @Test fun normalScrollSpacingAndMarginsUpdateTheReadingLayout() = verifySpacing(false, "scroll", true, false)

    @Test fun horizontalPageButtonsDoNotCoverTextWithZeroPageMargins() = verifySpacing(false, "auto", true, initialMargin = 0f)

    @Test fun rowPageButtonsDoNotCoverTextWithZeroPageMargins() = verifySpacing(false, "row", true, initialMargin = 0f)

    private fun verifySpacing(perBook: Boolean, mode: String, midChapter: Boolean = false, eInkMode: Boolean = true,
        initialMargin: Float = 16f) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val previous = app.store.state.value
        val ref = BookRef("local", "eink-spacing-regression")
        val firstParagraph = if(midChapter) 51 else 1
        val initial = ReaderSettings(fontSize = 14f, lineHeight = 1f, paragraphSpacing = 8f,
            verticalMargin = initialMargin, prefetchChapters = 0, indent = false, hideStatusBar = true)
            .withEInkMode(eInkMode).withPaginationMode(mode).copy(showPageButtons = true)
        lateinit var controller: AppController
        var readerOpened = false
        fun current() = app.store.state.value.let { if(perBook) it.bookSettings.getValue(ref.key) else it.reader }
        fun waitForPage() {
            compose.waitForIdle()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("正在分页…").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText("正文第 ${firstParagraph + 1} 段", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun bounds(index: Int) = compose.onNodeWithText("正文第 ${firstParagraph + index - 1} 段", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun gap() = bounds(2).top - bounds(1).bottom
        fun assertViewportMargins(menuHidden: Boolean = false) {
            val viewport = compose.onNodeWithTag(if(mode == "scroll") "reader-scroll" else "reader-page").fetchSemanticsNode().boundsInRoot
            val buttons = compose.onNodeWithTag("reader-page-buttons").fetchSemanticsNode().boundsInRoot
            val status = compose.onNodeWithTag("reader-reading-status-bar").fetchSemanticsNode().boundsInRoot
            // 展开菜单仍是正文浮层；常驻按钮在收起菜单后才回到底部的预留区域。
            val lowerEdge = if(menuHidden) buttons.top else status.top - buttons.height
            val margin = current().verticalMargin * compose.density.density
            val visible = compose.onAllNodes(hasText("正文第", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }
            assertTrue("$mode 上页边距应保留 $margin px，正文顶部 ${visible.minOf { it.top }}，视口顶部 ${viewport.top}",
                visible.minOf { it.top } >= viewport.top + margin - 1f)
            assertTrue("$mode 下页边距应保留 $margin px，正文底部 ${visible.maxOf { it.bottom }}，常驻按钮边界 $lowerEdge",
                visible.maxOf { it.bottom } <= lowerEdge - margin + 1f)
        }
        fun adjust(label: String, tag: String, amount: Float) {
            if(eInkMode) compose.onNodeWithContentDescription(label, substring = true)
                .performScrollTo().performTouchInput { click() }
            else compose.onNodeWithTag(tag).performScrollTo()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(amount) }
        }
        fun closePreferences() {
            compose.onNodeWithText("关闭面板").performTouchInput { click() }
            waitForPage()
            if(midChapter) {
                val body = compose.onNodeWithTag(if(mode == "scroll") "reader-scroll" else "reader-page")
                body.performTouchInput { click(center) }
                assertViewportMargins(menuHidden = true)
                body.performTouchInput { click(center) }
            }
        }
        try {
            app.store.saveDocument(LocalDocument(ref.id, "电子纸间距测试", "txt", listOf(
                LocalChapter("first", "第一章", (1..100).map { "正文第 $it 段" }))))
            app.store.update { it.copy(reader = initial, reducedMotion = true, readerTapTutorialSeen = true,
                positions = (it.positions - ref.key) + if(midChapter) mapOf(ref.key to Position("first",
                    index = firstParagraph, sourceParagraph = firstParagraph - 1)) else emptyMap(),
                bookSettings = (it.bookSettings - ref.key) + if(perBook) mapOf(ref.key to initial) else emptyMap()) }
            compose.setContent { NoveliaTheme("light") { AppInteractionMode(eInkMode, true) {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val snackbar = remember { SnackbarHostState() }
                controller = remember(nav, scope, snackbar) { AppController(app, nav, scope, snackbar) }
                NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
                    composable("home") { Text("测试书架") }
                    composable("reader/{provider}/{book}/{chapter}") { ReaderScreen(controller, ref, "first") }
                }
            } } }
            compose.runOnIdle { controller.read(ref, "first"); readerOpened = true }
            waitForPage()
            val density = compose.density.density
            val originalGap = gap()
            compose.onNodeWithContentDescription("阅读设置").performTouchInput { click() }
            adjust("增大 段距", "reader-paragraph-spacing", 9f)
            compose.runOnIdle { assertEquals(9f, current().paragraphSpacing, 0f) }
            adjust("减小 段距", "reader-paragraph-spacing", 8f)
            compose.runOnIdle { assertEquals(8f, current().paragraphSpacing, 0f) }
            adjust("增大 段距", "reader-paragraph-spacing", 9f)
            closePreferences()
            assertEquals(originalGap + density, gap(), 1f)
            val originalTop = bounds(1).top
            compose.onNodeWithContentDescription("阅读设置").performTouchInput { click() }
            compose.onNodeWithText("更多").performTouchInput { click() }
            compose.onNodeWithText("进阶排版").performScrollTo().performTouchInput { click() }
            adjust("增大 上下页边距", "reader-vertical-margin", initialMargin + 1f)
            compose.runOnIdle { assertEquals(initialMargin + 1f, current().verticalMargin, 0f) }
            adjust("减小 上下页边距", "reader-vertical-margin", initialMargin)
            compose.runOnIdle { assertEquals(initialMargin, current().verticalMargin, 0f) }
            adjust("增大 上下页边距", "reader-vertical-margin", initialMargin + 1f)
            closePreferences()
            if(midChapter) assertViewportMargins() else assertEquals(originalTop + density, bounds(1).top, 1f)
            if(midChapter) {
                val body = compose.onNodeWithTag(if(mode == "scroll") "reader-scroll" else "reader-page")
                body.performTouchInput { click(center) }
                // 翻页按钮和阅读信息底栏常驻，以设置按钮判断工具栏是否收起。
                compose.onNodeWithContentDescription("阅读设置").assertDoesNotExist()
                assertViewportMargins(menuHidden = true)
                body.performTouchInput { click(Offset(centerX, current().verticalMargin * density / 2f)) }
                compose.onNodeWithContentDescription("阅读设置").assertIsDisplayed()
            }
            compose.runOnIdle { controller.back() }
            compose.runOnIdle { controller.read(ref, "first") }
            waitForPage()
            if(midChapter) assertViewportMargins() else assertEquals(originalTop + density, bounds(1).top, 1f)
            assertEquals(originalGap + density, gap(), 1f)
            if(perBook) compose.runOnIdle { assertEquals(initial, app.store.state.value.reader) }
        } finally {
            if(readerOpened) compose.runOnIdle { controller.back() }
            app.store.update { previous }
        }
    }
}

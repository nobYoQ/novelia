package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.TextPart
import cc.novelia.app.ui.saveTestScreenshot
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderDualPageUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun spreadsTurnTogetherAndResizeOrDisablePreservesTheRequestedCharacter() {
        val state = EInkPageState(null)
        val paragraphs = listOf(ReadingParagraph(0, listOf(TextPart("旅人沿着林间的小路前行，寻找远处灯火通明的小镇。".repeat(300), "local"))))
        var width by mutableStateOf(800.dp)
        var enabled by mutableStateOf(true)
        compose.setContent {
            NoveliaTheme("light") { AppInteractionMode(false, false) {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    val settings = ReaderSettings(paginationMode = "auto", horizontalPageTurn = true, horizontalDualPage = enabled)
                    EInkPage(paragraphs, settings, state, Modifier.requiredSize(width, 420.dp).testTag("spread-body"),
                        imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move,
                        chapterTitle = "第一章 林间", contentPadding = PaddingValues(horizontal = 24.dp),
                        pageColumns = if(readerUsesDualPage(settings, width.value)) 2 else 1)
                }
            } }
        }
        compose.waitUntil(15_000) { state.ready && state.visiblePageCount == 2 && state.pages.size > 6 }
        compose.onNodeWithTag("reader-left-page").assertIsDisplayed()
        compose.onNodeWithTag("reader-right-page").assertIsDisplayed()
        val left = compose.onNodeWithTag("reader-left-page").getUnclippedBoundsInRoot()
        val right = compose.onNodeWithTag("reader-right-page").getUnclippedBoundsInRoot()
        assertTrue(left.right < right.left)
        assertTrue(kotlin.math.abs((left.right - left.left).value - (right.right - right.left).value) <= 1f)
        compose.onNodeWithTag("spread-body").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(2, state.pageIndex) }
        compose.onNodeWithTag("spread-body").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(0, state.pageIndex) }
        val target = state.pages[3].lines.first()
        compose.runOnIdle { state.find(target.paragraph, target.start); assertEquals(2, state.pageIndex) }
        val anchor = state.anchor
        compose.waitForIdle()
        compose.onNodeWithTag("reader-page-chapter-title", useUnmergedTree = true).assertDoesNotExist()
        saveTestScreenshot("reader-dual-page.png")
        val widePages = state.pages
        compose.runOnIdle { width = 360.dp }
        compose.waitUntil(15_000) { state.ready && state.visiblePageCount == 1 && state.pages !== widePages }
        compose.onNodeWithTag("reader-single-page", useUnmergedTree = true).assertExists()
        compose.runOnIdle {
            assertEquals(anchor, state.anchor)
            assertTrue(state.current!!.lines.any { it.paragraph == anchor.paragraph && anchor.textOffset in it.start until it.end })
        }
        compose.runOnIdle { width = 800.dp }
        compose.waitUntil(15_000) { state.ready && state.visiblePageCount == 2 }
        compose.runOnIdle { assertEquals(anchor, state.anchor); enabled = false }
        compose.waitUntil(15_000) { state.ready && state.visiblePageCount == 1 }
        compose.runOnIdle { assertEquals(anchor, state.anchor) }
        compose.onNodeWithTag("reader-dual-page", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theSwitchOnlyAppearsUnderHorizontalPaginationAndKeepsItsValue() {
        var settings by mutableStateOf(ReaderSettings())
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            Surface(Modifier.requiredWidth(360.dp).fillMaxHeight()) {
                ReaderPreferences(settings, false) { settings = it }
            }
        } } }
        compose.onNodeWithText("翻页", substring = false).performClick()
        compose.onNodeWithTag("reader-dual-page-toggle").assertDoesNotExist()
        compose.onNodeWithText("横向分页", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("宽屏双页", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(settings.horizontalDualPage); assertEquals("auto", settings.paginationMode) }
        compose.onNodeWithText("行带分页", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("reader-dual-page-toggle").assertDoesNotExist()
        compose.onNodeWithText("连续滚动", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("reader-dual-page-toggle").assertDoesNotExist()
        compose.onNodeWithText("横向分页", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("宽屏双页", substring = false).performScrollTo().assertExists()
        compose.runOnIdle { assertTrue(settings.horizontalDualPage) }
    }

    @Test fun footerShowsBothPagesAndReachesOneHundredPercentOnTheLastSpread() {
        val settings = ReaderSettings(paginationMode = "auto", horizontalDualPage = true, readingStatusItems = listOf("pages", "progress"))
        val state = ReaderBottomToolbarState(false, false, true, false, false, false, false, false,
            1f, true, 8, 6, "章节", false, true, visiblePageCount = 2)
        compose.setContent { NoveliaTheme("light") {
            ReaderReadingStatusBar(state, settings, 2, Color.Black, Modifier.width(360.dp))
        } }
        compose.onNodeWithTag("reader-page-counter").assertTextEquals("7–8 / 8")
        compose.onNodeWithTag("reader-chapter-progress").assertTextEquals("本章 100%")
    }
}

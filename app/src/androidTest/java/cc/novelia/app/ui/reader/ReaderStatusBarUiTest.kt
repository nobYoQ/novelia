package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderStatusBarUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun footerItemsCanBeSelectedReorderedAndReset() {
        var items by mutableStateOf(ReaderStatusItems.defaults)
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            Column(Modifier.verticalScroll(rememberScrollState())) { ReaderStatusBarPreferences(items, ReaderStatusItems.defaults) { items = it } }
        } } }
        compose.onNodeWithTag("reader-status-option-battery").performScrollTo().performClick()
        repeat(4) { compose.onNodeWithContentDescription("电量 前移").performScrollTo().performClick() }
        compose.runOnIdle { assertEquals("battery", items.first()) }
        compose.onNodeWithTag("reader-status-option-time").performScrollTo().performClick()
        compose.runOnIdle { assertFalse("time" in items) }
        compose.onNodeWithText("恢复默认底栏内容").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ReaderStatusItems.defaults, items) }
    }
    @Test fun largeTextNarrowFooterKeepsAllItemsWithinItsBoundsAndHidesDisabledItems() {
        var settings by mutableStateOf(ReaderSettings(paginationMode = "auto", readingStatusItems = ReaderStatusItems.labels.keys.toList()))
        val state = ReaderBottomToolbarState(false, true, true, false, true, false, true, false, .5f, true, 20, 9, "长章节标题".repeat(20), false, true)
        compose.setContent { NoveliaTheme("light") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ReaderReadingStatusBar(state, settings, ReaderStatusItems.columns(320f, 2f), Color.Black, Modifier.width(320.dp))
            }
        } }
        val tags = listOf("reader-status-chapter-title", "reader-status-clock", "reader-status-battery", "reader-page-counter", "reader-chapter-progress")
        val bar = compose.onNodeWithTag("reader-reading-status-bar").fetchSemanticsNode().boundsInRoot
        val cells = tags.map { tag -> compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        cells.forEach { assertTrue(it.left >= bar.left && it.right <= bar.right && it.top >= bar.top && it.bottom <= bar.bottom) }
        assertTrue(cells.first().bottom <= cells[1].top)
        cells.drop(1).chunked(2).forEach { row ->
            assertEquals(row[0].top, row[1].top, 1f)
            assertTrue(row[0].right <= row[1].left)
        }
        assertTrue(cells[1].bottom <= cells[3].top)
        tags.drop(1).forEach { tag ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse(layouts.single().isLineEllipsized(0))
        }
        compose.runOnIdle { settings = settings.copy(readingStatusItems = listOf("battery", "progress")) }
        compose.onNodeWithTag("reader-status-clock").assertDoesNotExist()
        compose.onNodeWithTag("reader-status-chapter-title").assertDoesNotExist()
        compose.onNodeWithTag("reader-page-counter").assertDoesNotExist()
        compose.onNodeWithTag("reader-chapter-progress").assertTextEquals("本章 50%")
    }

    @Test fun allFourShortItemsFitOneRowAcrossPhoneWidthsAndProgressChanges() {
        val shortItems = listOf("time", "battery", "pages", "progress")
        var width by mutableStateOf(320.dp)
        var settings by mutableStateOf(ReaderSettings(paginationMode = "auto", readingStatusItems = listOf("chapter") + shortItems))
        var state by mutableStateOf(ReaderBottomToolbarState(false, true, true, false, true, false, true, false,
            1f, true, 9999, 9998, "长章节标题".repeat(20), false, true))
        compose.setContent { NoveliaTheme("light") {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ReaderReadingStatusBar(state, settings, ReaderStatusItems.columns(width.value, 1f), Color.Black, Modifier.width(width))
            }
        } }
        val tags = listOf("reader-status-clock", "reader-status-battery", "reader-page-counter", "reader-chapter-progress")
        for(size in listOf(320.dp, 360.dp, 390.dp)) {
            compose.runOnIdle { width = size }
            val bar = compose.onNodeWithTag("reader-reading-status-bar").fetchSemanticsNode().boundsInRoot
            val cells = tags.map { compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            cells.forEach { assertEquals(cells[0].top, it.top, 1f); assertTrue(it.left >= bar.left && it.right <= bar.right) }
            cells.zipWithNext().forEach { (a, b) -> assertTrue(a.right <= b.left) }
            assertTrue(compose.onNodeWithTag("reader-status-chapter-title").fetchSemanticsNode().boundsInRoot.bottom <= cells[0].top)
            val screenshot = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("screenshots"),
                "reader-footer-${size.value.toInt()}.png")
            compose.onNodeWithTag("reader-reading-status-bar").captureToImage().asAndroidBitmap().let { bitmap ->
                screenshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            tags.forEach { tag ->
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertFalse("$tag 在 $size 不应省略：${layout.layoutInput.text.text}，可用宽度 ${layout.size.width}px",
                    layout.isLineEllipsized(0))
            }
            compose.runOnIdle { state = state.copy(pageCount = 20, pageIndex = 9) }
            assertEquals(bar.height, compose.onNodeWithTag("reader-reading-status-bar").fetchSemanticsNode().boundsInRoot.height, 1f)
        }
        compose.runOnIdle { settings = settings.copy(readingStatusItems = shortItems) }
        compose.onNodeWithTag("reader-status-chapter-title").assertDoesNotExist()
        val tops = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        tops.forEach { assertEquals(tops[0], it, 1f) }
    }

    @Test fun crowdedFooterUsesAFullTitleRowAndOpensTheCompleteTitle() {
        val title = "第十二章　在漫长旅途尽头再次相逢".repeat(6)
        val state = ReaderBottomToolbarState(false, true, true, false, true, false, true, false, .5f, true, 20, 9, title, false, true)
        var showTitle by mutableStateOf(false)
        var tocOpened = false
        compose.setContent { NoveliaTheme("light") {
            ReaderReadingStatusBar(state, ReaderSettings(paginationMode = "auto"), ReaderStatusItems.columns(360f, 1f),
                Color.Black, Modifier.width(360.dp), onChapterTitle = { showTitle = true })
            if(showTitle) ReaderChapterTitleDialog(title, { showTitle = false }, { tocOpened = true; showTitle = false })
        } }
        val heading = compose.onNodeWithTag("reader-status-chapter-title").fetchSemanticsNode().boundsInRoot
        val clock = compose.onNodeWithTag("reader-status-clock").fetchSemanticsNode().boundsInRoot
        assertTrue(heading.bottom <= clock.top)
        assertTrue(heading.width > clock.width * 2)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = java.io.File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "reader-chapter-footer.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            screenshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithTag("reader-status-chapter-title").performClick()
        compose.onNodeWithTag("reader-full-chapter-title").assertTextEquals(title)
        compose.onNodeWithText("打开目录").performClick()
        compose.runOnIdle { assertTrue(tocOpened) }
        compose.onNodeWithTag("reader-full-chapter-title").assertDoesNotExist()
    }
}

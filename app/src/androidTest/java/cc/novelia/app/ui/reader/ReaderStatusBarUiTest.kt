package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
        val tags = listOf("reader-status-clock", "reader-status-battery", "reader-status-chapter-title", "reader-page-counter", "reader-chapter-progress")
        val bar = compose.onNodeWithTag("reader-reading-status-bar").fetchSemanticsNode().boundsInRoot
        val cells = tags.map { tag -> compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        cells.forEach { assertTrue(it.left >= bar.left && it.right <= bar.right && it.top >= bar.top && it.bottom <= bar.bottom) }
        cells.zipWithNext().forEach { (a, b) -> assertTrue(a.bottom <= b.top) }
        listOf("reader-status-clock", "reader-page-counter", "reader-chapter-progress").forEach { tag ->
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
}

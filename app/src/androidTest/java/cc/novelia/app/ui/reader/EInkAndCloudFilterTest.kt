package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.CollapsibleCloudFilters
import cc.novelia.app.ui.components.rememberCloudFilterCollapse
import cc.novelia.app.ui.reader.EInkPage
import cc.novelia.app.ui.reader.EInkPageState
import cc.novelia.app.ui.reader.ReaderPreferences
import cc.novelia.app.ui.reader.measureEInkChapter
import cc.novelia.app.ui.theme.LocalReducedMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EInkAndCloudFilterTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exactSearchMovesToLateLanguagePartWithoutRepaginatingForHighlight() {
        val settings = ReaderSettings(parallel = true, indent = true, paginationMode = "auto")
        val paragraphs = listOf(ReadingParagraph(12, listOf(
            TextPart("前面的正文。".repeat(180), "gpt"),
            TextPart("日文の本文。".repeat(160) + "定位目标", "日文", true))))
        val state = EInkPageState(null)
        var match by mutableStateOf<ReadingTextMatch?>(null)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalDensity provides Density(1f)) {
                    EInkPage(paragraphs, settings, state, Modifier.width(327.dp).height(420.dp),
                        imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move, activeMatch = match)
                }
            }
        }
        compose.waitUntil(10_000) { state.ready && state.pages.size > 2 }
        val pages = state.pages
        compose.runOnIdle {
            val found = findReadingTextMatches(paragraphs, "定位目标").single()
            match = found
            state.find(found.paragraph, found.textOffset(paragraphs[found.paragraph], settings))
        }
        compose.onNodeWithText("定位目标", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            assertSame(pages, state.pages)
            assertTrue(state.pageIndex > 1)
            match = null
        }
        compose.waitForIdle()
        compose.runOnIdle { assertSame(pages, state.pages) }
    }

    @Test fun replacingContentWithFewerParagraphsNeverUsesOldPageIndices() {
        val state = EInkPageState(null)
        val first = ReadingParagraph(0, listOf(TextPart("保留的正文", "日文")))
        var paragraphs by mutableStateOf(listOf(first, ReadingParagraph(1, listOf(TextPart("将被移除的译文".repeat(100), "gpt")))))
        compose.setContent {
            MaterialTheme {
                EInkPage(paragraphs, ReaderSettings(), state, Modifier.width(327.dp).height(420.dp),
                    imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move)
            }
        }
        compose.waitUntil(10_000) { state.pages.size > 1 }
        compose.runOnIdle { state.find(1) }
        compose.runOnIdle { paragraphs = listOf(first) }
        compose.waitUntil(10_000) { state.pages.size == 1 && state.paragraph == 0 }
        compose.onNodeWithText("保留的正文", substring = true).assertIsDisplayed()
    }

    @Test fun reflowChecksCancellationBeforeMeasuringEveryParagraph() {
        var checks = 0
        try {
            measureEInkChapter(List(30) { ReadingParagraph(it, listOf(TextPart("正文", "gpt"))) }, ReaderSettings(), 300, 400, 1f, 1f) {
                if(++checks == 5) throw kotlinx.coroutines.CancellationException()
            }
            fail("Expected cancellation")
        } catch(_: kotlinx.coroutines.CancellationException) { assertEquals(5, checks) }
    }

    @Test fun paginationModesContainGesturesAndKeepButtonPreference() {
        var settings by mutableStateOf(ReaderSettings())
        compose.setContent { MaterialTheme { ReaderPreferences(settings) { settings = it } } }
        compose.onNodeWithText("翻页").performClick()
        compose.onNodeWithText("连续滚动").assertIsSelected()
        compose.onNodeWithText("滚动翻页").assertDoesNotExist()
        compose.onNodeWithText("左右翻页").assertDoesNotExist()
        compose.onNodeWithText("显示翻页按钮").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(settings.showPageButtons); assertFalse(settings.staticPagination) }
        compose.onNodeWithText("自动分页").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("滚动翻页").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("左右翻页").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("显示翻页按钮").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(settings.showPageButtons); assertTrue(settings.staticPagination) }
        compose.onNodeWithText("连续滚动").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("滚动翻页").assertDoesNotExist()
        compose.onNodeWithText("左右翻页").assertDoesNotExist()
        compose.onNodeWithText("显示翻页按钮").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertFalse(settings.showPageButtons); assertFalse(settings.staticPagination) }
    }

    @Test fun filterAnimationResizesGraduallyAndReducedMotionFinishesImmediately() {
        var expanded by mutableStateOf(false)
        var reduced by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    CollapsibleCloudFilters(expanded, { expanded = !expanded }, "全部收藏", 220.dp, Modifier.width(375.dp).testTag("filters")) {
                        Box(Modifier.fillMaxWidth().height(160.dp)) { Text("筛选条件") }
                    }
                }
            }
        }
        fun height() = compose.onNodeWithTag("filters").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val collapsedHeight = height()
        compose.runOnIdle { expanded = true }
        compose.waitForIdle()
        val fullHeight = height()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { expanded = false }
        compose.mainClock.advanceTimeBy(80)
        assertTrue(height() < fullHeight && height() > collapsedHeight)
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(collapsedHeight, height())
        compose.runOnIdle { expanded = true }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(fullHeight, height())
        compose.runOnIdle { expanded = false }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(collapsedHeight, height())
        compose.runOnIdle { reduced = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { expanded = true }
        compose.mainClock.advanceTimeBy(80)
        assertTrue(height() > collapsedHeight && height() < fullHeight)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(fullHeight, height())
    }

    @Test fun gestureSwitchesWorkIndependentlyWithCustomColors() {
        val state = EInkPageState(null)
        val paragraphs = listOf(ReadingParagraph(0, listOf(TextPart("沿着林间小路前行。".repeat(300), "gpt"))))
        var settings by mutableStateOf(ReaderSettings(scrollPageTurn = true))
        val paper = Color(0xFFF4ECD8)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    EInkPage(paragraphs, settings, state, Modifier.width(327.dp).height(420.dp).testTag("page"),
                        imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move,
                        background = paper, foreground = Color(0xFF282E27))
                }
            }
        }
        compose.waitUntil(10_000) { state.pages.size > 3 }
        val pixels = compose.onNodeWithTag("page").captureToImage().toPixelMap()
        assertEquals(paper, pixels[pixels.width - 1, pixels.height - 1])
        compose.onNodeWithTag("page").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, state.pageIndex) }
        compose.onNodeWithTag("page").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(1, state.pageIndex); settings = settings.copy(scrollPageTurn = false, horizontalPageTurn = true) }
        compose.onNodeWithTag("page").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(1, state.pageIndex) }
        compose.onNodeWithTag("page").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(2, state.pageIndex); settings = settings.copy(eInkMode = true, horizontalPageTurn = false) }
        compose.onNodeWithTag("page").performTouchInput { swipeLeft(); swipeUp() }
        compose.runOnIdle { assertEquals(2, state.pageIndex) }
    }

    @Test fun readerPreferencesKeepThemesMutuallyExclusive() {
        var settings by mutableStateOf(ReaderSettings(theme = "paper"))
        compose.setContent { MaterialTheme { ReaderPreferences(settings) { settings = it } } }
        compose.onNodeWithText("翻页").performClick()
        compose.onNodeWithText("电子纸阅读模式").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("paper", settings.theme)
            assertFalse(settings.monochrome)
            assertTrue(settings.scrollPageTurn && settings.horizontalPageTurn)
        }
        compose.onNodeWithText("滚动翻页").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(settings.scrollPageTurn); assertTrue(settings.horizontalPageTurn) }
        compose.onNodeWithText("黑白色调").assertDoesNotExist()
        compose.onNodeWithText("常用").performClick()
        val themes = linkedMapOf("黑白" to "monochrome", "深色" to "dark", "浅色" to "light", "纸张" to "paper", "跟随应用" to "system")
        themes.forEach { (label, theme) ->
            compose.onNodeWithText(label).performScrollTo().performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(theme, settings.resolvedTheme); assertFalse(settings.monochrome) }
            themes.keys.filterNot { it == label }.forEach { other -> compose.onNodeWithText(other).assertIsNotSelected() }
        }
        compose.runOnIdle { settings = settings.copy(theme = "paper", monochrome = true) }
        compose.onNodeWithText("黑白").assertIsSelected()
        compose.onNodeWithText("深色").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("dark", settings.resolvedTheme); assertFalse(settings.monochrome) }
        compose.onNodeWithText("翻页").performClick()
        compose.onNodeWithText("电子纸阅读模式").performScrollTo().performClick()
        compose.onNodeWithText("连续滚动").assertIsSelected()
        compose.onNodeWithText("滚动翻页").assertDoesNotExist()
        compose.runOnIdle { assertFalse(settings.showPageButtons); assertFalse(settings.volumeKeys); assertEquals("dark", settings.resolvedTheme) }
        compose.onNodeWithText("电子纸阅读模式").performClick()
        compose.onNodeWithText("自动分页").assertIsSelected()
        compose.runOnIdle { assertFalse(settings.scrollPageTurn); assertTrue(settings.horizontalPageTurn); assertEquals("dark", settings.resolvedTheme) }
    }

    @Test fun realTextLayoutCoversAllCharactersAtSmallAndLandscapeSizes() {
        val paragraphs = listOf(ReadingParagraph(0, listOf(TextPart("旅人沿着森林小路前行。日文と中文を読みます。😀".repeat(200), "gpt"))))
        for ((width, height, scale) in listOf(Triple(327, 440, 1f), Triple(690, 150, 1f), Triple(327, 300, 2f))) {
            val measured = measureEInkChapter(paragraphs, ReaderSettings(eInkMode = true), width, height, 1f, scale)
            val layout = measured.layouts.getValue(0)
            val lines = measured.pages.flatMap { it.lines }
            assertEquals(0, lines.first().start)
            assertEquals(layout.text.length, lines.last().end)
            lines.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
            assertTrue(measured.pages.all { it.lines.sumOf(PageLine::height) <= height })
            assertTrue(measured.pages.size > 1)
        }
    }

    @Test fun swipeCommitsOnePageAndReflowRetainsItsAnchor() {
        val state = EInkPageState(null)
        val paragraphs = listOf(ReadingParagraph(0, listOf(TextPart("旅人沿着森林小路前行，寻找远处的小镇。".repeat(150), "gpt"))))
        var width by mutableStateOf(327.dp)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    EInkPage(paragraphs, ReaderSettings(eInkMode = true), state, Modifier.width(width).height(420.dp).testTag("page"),
                        imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move)
                }
            }
        }
        compose.waitUntil(10_000) { state.pages.size > 2 }
        compose.onNodeWithTag("page").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, state.pageIndex) }
        val offset = state.textOffset
        compose.runOnIdle { width = 280.dp }
        compose.waitForIdle()
        compose.waitUntil(10_000) { state.pages.first().lines.last().end < offset }
        compose.runOnIdle { assertTrue(state.current!!.lines.any { offset in it.start until it.end }) }
        compose.onNodeWithTag("page").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(0, state.pageIndex) }
    }

    @Test fun filtersCollapseOnListScrollExpandOnTapAndRespectSwitch() {
        var expanded by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(375.dp).fillMaxHeight()) {
                    CollapsibleCloudFilters(expanded, { expanded = !expanded }, "全部收藏 · 更新时间", 220.dp) {
                        Text("完整筛选条件")
                        Switch(enabled, { enabled = it })
                    }
                    val collapse = rememberCloudFilterCollapse(enabled, expanded) { expanded = false }
                    LazyColumn(Modifier.weight(1f).testTag("results").nestedScroll(collapse)) {
                        items(40) { Text("小说 $it", Modifier.height(72.dp)) }
                    }
                }
            }
        }
        compose.onNodeWithTag("results").performTouchInput { swipeUp() }
        compose.onNodeWithText("展开筛选").assertIsDisplayed()
        compose.onNodeWithText("完整筛选条件").assertDoesNotExist()
        compose.onNodeWithText("展开筛选").performClick()
        compose.onNodeWithText("完整筛选条件").assertIsDisplayed()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithTag("results").performTouchInput { swipeUp() }
        compose.onNodeWithText("完整筛选条件").assertIsDisplayed()
    }
}

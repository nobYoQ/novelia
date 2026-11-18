package cc.novelia.app.ui.shelf

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.saveTestScreenshot
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadingStatisticsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun bookFilterPauseAndClearWorkWithoutChangingOtherLibraryData() {
        var stats by mutableStateOf(ReadingStatistics(days = mapOf(LocalDate.now().toString() to mapOf(
            "local/a" to ReadingTimeEntry("书 A", 60_000), "local/b" to ReadingTimeEntry("书 B", 120_000)))))
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            ReadingStatisticsView(stats, false, "local/a", {}, { stats = stats.withEnabled(it) }, { stats = stats.cleared() })
        } } }
        compose.onNodeWithTag("reading-statistics-today").assertContentDescriptionEquals("今日 · 1 分钟")
        compose.onNodeWithText("全部书籍").performClick()
        compose.onNodeWithTag("reading-statistics-today").assertContentDescriptionEquals("今日 · 3 分钟")
        compose.onNodeWithText("记录阅读时长").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(stats.enabled); assertEquals(180_000L, stats.totalMillis) }
        compose.onNodeWithContentDescription("清空阅读统计").performClick()
        compose.onNodeWithText("清空统计").performClick()
        compose.runOnIdle { assertTrue(stats.days.isEmpty()); assertEquals(2L, stats.revision) }
    }

    @Test fun trendsAndEmptyStateAdaptToDarkThemeAndLargeText() {
        val today = LocalDate.now()
        var stats by mutableStateOf(ReadingStatistics(days = (0L..6).associate { offset ->
            today.minusDays(offset).toString() to mapOf(
                "local/a" to ReadingTimeEntry("山间的旅人", (7 - offset) * 420_000),
                "local/b" to ReadingTimeEntry("星空下的图书馆", (offset + 1) * 120_000))
        }))
        var theme by mutableStateOf("light")
        var fontScale by mutableFloatStateOf(1f)
        var screenWidth by mutableStateOf(390.dp)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NoveliaTheme(theme) { AppInteractionMode(false, true) {
                    Box(Modifier.width(screenWidth).fillMaxHeight()) {
                        ReadingStatisticsView(stats, false, null, {}, {}, {})
                    }
                } }
            }
        }
        compose.onNodeWithTag("reading-statistics-today").assertContentDescriptionEquals("今日 · 51 分钟")
        saveTestScreenshot("reading-statistics-overview.png")
        compose.onNodeWithTag("reading-statistics-list").performScrollToNode(hasTestTag("reading-statistics-week"))
        saveTestScreenshot("reading-statistics-trend.png")
        compose.runOnIdle { theme = "dark" }
        compose.waitForIdle()
        saveTestScreenshot("reading-statistics-dark.png")
        compose.runOnIdle {
            fontScale = 2f
            screenWidth = 320.dp
        }
        compose.waitForIdle()
        compose.onNodeWithTag("reading-statistics-list").performScrollToNode(hasTestTag("reading-statistics-week"))
        compose.onNodeWithText("柱状图").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("按月").assertIsDisplayed()
        for (label in listOf("柱状图", "折线图", "日历")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(label, 1, layouts.single().lineCount)
        }
        saveTestScreenshot("reading-statistics-large-text.png")
        compose.runOnIdle { stats = ReadingStatistics(enabled = false); fontScale = 1f; screenWidth = 390.dp }
        compose.onNodeWithTag("reading-statistics-list").performScrollToNode(hasText("还没有阅读时长"))
        compose.onNodeWithText("还没有阅读时长").assertIsDisplayed()
        saveTestScreenshot("reading-statistics-empty.png")
    }

    @Test fun chartChoicesShareThePeriodAndRemainingBooksExpandSeparately() {
        val today = LocalDate.now()
        val previousMonth = today.withDayOfMonth(1).minusDays(1)
        val stats = ReadingStatistics(days = mapOf(today.toString() to (1..7).associate { "local/$it" to ReadingTimeEntry("作品 $it", it * 60_000L) },
            previousMonth.toString() to mapOf("local/7" to ReadingTimeEntry("作品 7", 3_600_000))))
        compose.setContent { NoveliaTheme("light") { ReadingStatisticsView(stats, false, null, {}, {}, {}) } }
        val list = compose.onNodeWithTag("reading-statistics-list")
        fun choose(label: String) { list.performScrollToNode(hasText(label)); compose.onNodeWithText(label).performClick() }
        choose("按月"); choose("折线图")
        compose.onNodeWithText("按月").assertIsSelected()
        compose.onNodeWithText("折线图").assertIsSelected()
        list.performScrollToNode(hasTestTag("reading-trend-line"))
        list.performScrollToNode(hasContentDescription("上个月")); compose.onNodeWithContentDescription("上个月").performClick()
        choose("日历")
        list.performScrollToNode(hasTestTag("reading-trend-calendar"))
        val lastDay = "${previousMonth.monthValue} 月 ${previousMonth.dayOfMonth} 日，1 小时 0 分钟"
        compose.onNodeWithContentDescription(lastDay).performScrollTo().performClick()
        list.performScrollToNode(hasText("展开其他 2 本书")); compose.onNodeWithText("作品 1").assertDoesNotExist()
        choose("展开其他 2 本书"); list.performScrollToNode(hasText("作品 1"))
        choose("收起其他书籍"); choose("饼图")
        compose.onNodeWithText("饼图").assertIsSelected()
        list.performScrollToNode(hasTestTag("reading-book-pie"))
        saveTestScreenshot("reading-statistics-books.png")
        list.performScrollToNode(hasText("其他书籍"))
    }
}

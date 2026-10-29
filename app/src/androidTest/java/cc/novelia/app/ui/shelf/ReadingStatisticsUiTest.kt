package cc.novelia.app.ui.shelf

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
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
        compose.onNodeWithTag("reading-statistics-today").assertTextContains("今日 · 1 分钟")
        compose.onNodeWithText("全部书籍").performClick()
        compose.onNodeWithTag("reading-statistics-today").assertTextContains("今日 · 3 分钟")
        compose.onNodeWithText("记录阅读时长").performClick()
        compose.runOnIdle { assertFalse(stats.enabled); assertEquals(180_000L, stats.totalMillis) }
        compose.onNodeWithContentDescription("清空阅读统计").performClick()
        compose.onNodeWithText("清空统计").performClick()
        compose.runOnIdle { assertTrue(stats.days.isEmpty()); assertEquals(2L, stats.revision) }
    }
}

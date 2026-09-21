package cc.novelia.app.ui.book

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.ui.components.bookUpdateDateTime
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class BookUpdateSummaryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longChapterTitlesGrowWithoutClippingAtNarrowWidthsAndLargeFonts() {
        var fontScale by mutableStateOf(1f)
        val title = "第101话　关于春天是邂逅与离别的季节这件事，以及我们尚未说完的话"
        val detail = WebDetail(toc = (1..101).map { TocItem(chapterId = "$it", titleZh = if(it == 101) title else "章节 $it", createAt = 1704067200 + it.toLong()) })
        compose.setContent {
            NoveliaTheme("dark") {
                CompositionLocalProvider(LocalDensity provides Density(1.5f, fontScale)) {
                    Surface(Modifier.requiredWidth(340.dp)) { BookUpdateSummary(detail) {} }
                }
            }
        }
        for(scale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            val node = compose.onNodeWithText("第 101 章 · $title", useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue(layout.lineCount >= 2)
            assertTrue(!layout.didOverflowHeight && !layout.didOverflowWidth)
            val text = node.fetchSemanticsNode().boundsInRoot
            val button = compose.onNodeWithTag("book-update-chapter").fetchSemanticsNode().boundsInRoot
            val arrow = compose.onNodeWithContentDescription("阅读此章节", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(text.left > button.left && text.bottom < button.bottom && text.top > button.top)
            assertTrue(text.right < arrow.left)
        }
        val bitmap = compose.onNodeWithTag("book-update-summary").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "book-update-multiline.png")
            .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun summaryShowsTimeAndMatchingChapterAndOpensThatChapter() {
        var opened: String? = null
        val detail = WebDetail(toc = listOf(TocItem(titleJp = "卷标题", createAt = 1900000000),
            TocItem(chapterId = "a", titleJp = "风の中", titleZh = "风中的约定", createAt = 1704067200),
            TocItem(chapterId = "b", titleZh = "春日的来信", createAt = 1704153600)))
        compose.setContent { NoveliaTheme("light") { Surface(Modifier.requiredWidth(360.dp)) { BookUpdateSummary(detail) { opened = it } } } }
        compose.onNodeWithText("最后更新时间").assertIsDisplayed()
        compose.onNodeWithText(bookUpdateDateTime(1704153600)!!).assertIsDisplayed()
        compose.onNodeWithText("第 2 章 · 春日的来信").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("b", opened) }
        val bitmap = compose.onNodeWithTag("book-update-summary").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "book-update-summary.png")
            .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun undatedDetailsStillShowTheLatestChapterWithoutInventingATime() {
        val detail = WebDetail(syncAt = 1704153600, toc = listOf(TocItem(chapterId = "a", titleZh = "未标注日期的章节")))
        compose.setContent { NoveliaTheme("light") { BookUpdateSummary(detail) {} } }
        compose.onNodeWithText("暂无时间记录").assertIsDisplayed()
        compose.onNodeWithText("第 1 章 · 未标注日期的章节").assertIsDisplayed()
        compose.onNodeWithText(bookUpdateDateTime(detail.syncAt)!!).assertDoesNotExist()
    }
}

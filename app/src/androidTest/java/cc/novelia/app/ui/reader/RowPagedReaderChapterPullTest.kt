package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RowPagedReaderChapterPullTest {
    @get:Rule val compose = createComposeRule()
    private val state = RowPagingState(null)
    private var turns = 0
    private var enabled by mutableStateOf(true)
    private var loading by mutableStateOf(false)
    private var hasNext by mutableStateOf(true)

    private fun reader(eInk: Boolean = false, long: Boolean = false) {
        val paragraphs = listOf(ReadingParagraph(7, listOf(TextPart("行带阅读正文。".repeat(if(long) 200 else 1), "local"))))
        compose.setContent { NoveliaTheme("light") {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val pull = rememberReaderChapterOverscrollGesture()
                RowPagedReader(paragraphs, ReaderSettings(paginationMode = "row", eInkMode = eInk), state,
                    Modifier.requiredSize(300.dp, 300.dp).testTag("row-page"),
                    imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move,
                    interactionEnabled = enabled && !loading, chapterPull = pull,
                    onNextChapter = if(hasNext) ({ turns++; loading = true }) else null,
                    chapterEnd = {
                        Column {
                            Text("本章完")
                            if(hasNext) ReaderChapterPullHint(pull.progress, pull.active, pull.ready, loading, Color.Black)
                        }
                    })
            }
        } }
        compose.waitUntil(10_000) { state.ready && state.endContentHeight > 0 }
    }

    @Test fun rowPullUsesTheSameHintMotionAndInlineLoadingAndOnlyTurnsOnRelease() {
        reader()
        val page = compose.onNodeWithTag("row-page")
        compose.onNodeWithText("上拉加载下一章").assertIsDisplayed()
        val resting = compose.onNodeWithText("本章完", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        page.performTouchInput { down(center); moveBy(Offset(0f, -40f), 200) }
        compose.onNodeWithText("继续上拉加载下一章").assertIsDisplayed()
        compose.onAllNodesWithTag("reader-chapter-pull-hint", useUnmergedTree = true).assertCountEquals(1)
        page.performTouchInput { moveBy(Offset(0f, -48f), 200) }
        compose.onNodeWithText("松手加载下一章").assertIsDisplayed()
        val pulled = compose.onNodeWithText("本章完", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        assertTrue("正文应随上拉移动：$resting -> $pulled", resting - pulled > 30f)
        compose.runOnIdle { assertEquals(0, turns) }
        page.performTouchInput { up() }
        compose.onNodeWithText("正在加载下一章…").assertIsDisplayed()
        page.performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(1, turns) }
    }

    @Test fun shortReverseCancelledAndMultiplePointerPullsDoNotTurnChapters() {
        reader(eInk = true)
        val page = compose.onNodeWithTag("row-page")
        page.performTouchInput { down(center); moveBy(Offset(0f, -40f), 200); up() }
        page.performTouchInput { down(center); moveBy(Offset(0f, 100f), 200); up() }
        page.performTouchInput { down(center); moveBy(Offset(0f, -100f), 200); cancel() }
        page.performTouchInput {
            down(center); moveBy(Offset(0f, -100f), 200); moveBy(Offset(0f, 70f), 200); up()
        }
        page.performTouchInput {
            down(center); moveBy(Offset(0f, -100f), 200)
            down(1, center + Offset(40f, 0f)); up(1); up()
        }
        compose.runOnIdle { assertEquals(0, turns) }
        val resting = compose.onNodeWithText("本章完", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        page.performTouchInput { down(center); moveBy(Offset(0f, -100f), 200) }
        compose.onNodeWithText("松手加载下一章").assertIsDisplayed()
        assertEquals(resting, compose.onNodeWithText("本章完", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y, .5f)
        page.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, turns) }
    }

    @Test fun footerMustBeFullyScrolledBeforeExtraDragCanTurnAndProgrammaticScrollingCannotTurn() {
        reader(long = true)
        val page = compose.onNodeWithTag("row-page")
        page.performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertEquals(0, turns)
            state.beginScroll()
            state.scrollBy(Float.MAX_VALUE)
            state.finishScroll()
            assertFalse(state.canScrollForward)
            assertEquals(0, turns)
        }
        compose.onNodeWithText("上拉加载下一章").assertIsDisplayed()
        page.performTouchInput { down(center); moveBy(Offset(0f, -100f), 200); up() }
        compose.runOnIdle { assertEquals(1, turns) }
    }

    @Test fun disablingInteractionMidPullAndTheLastChapterCannotTurn() {
        reader()
        val page = compose.onNodeWithTag("row-page")
        page.performTouchInput { down(center); moveBy(Offset(0f, -100f), 200) }
        compose.onNodeWithText("松手加载下一章").assertIsDisplayed()
        compose.runOnIdle { enabled = false }
        page.performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, turns); enabled = true; hasNext = false }
        page.performTouchInput { swipeUp() }
        compose.onNodeWithTag("reader-chapter-pull-hint", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, turns) }
    }
}

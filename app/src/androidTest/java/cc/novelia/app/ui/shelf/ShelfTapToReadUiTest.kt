package cc.novelia.app.ui.shelf

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ShelfTapToReadUiTest {
    @get:Rule val compose = createComposeRule()
    private val book = BookCard(BookRef("kakuyomu", "tap-read"), "续读测试")
    private var state by mutableStateOf(LibraryState(positions = mapOf(book.ref.key to Position("chapter-3"))))
    private var managing by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var grid by mutableStateOf(false)
    private var selection by mutableStateOf(emptyMap<String, BookCard>())
    private var opened = 0
    private val reads = mutableListOf<Pair<BookRef, String>>()

    private fun show(eInk: Boolean = false) {
        compose.setContent { AppInteractionMode(eInk, eInk) { NoveliaTheme("light") { Surface {
            CloudShelfBooks(listOf(book), state, "test", rememberLazyListState(), 0, 1, {},
                managing, { managing = it }, selection, { selection = it }, busy, 0, true, 0, null,
                onOpenBook = { opened++ }, onLocal = {}, onRemove = {}, onRetry = {},
                onReadBook = { ref, chapter -> reads += ref to chapter }, grid = grid)
        } } } }
    }
    private fun row() = compose.onNodeWithTag("cloud-book-${book.ref.key}")
    private fun cover() = compose.onNodeWithContentDescription("${book.title} 默认封面", useUnmergedTree = true)

    @Test fun toggleChangesTouchAndAccessibilityActionsAndCoverStillOpensDetails() {
        show()
        row().performClick()
        row().performTouchInput { click(centerRight - androidx.compose.ui.geometry.Offset(16f, 0f)) }
        compose.runOnIdle { assertEquals(2, opened); assertTrue(reads.isEmpty()); state = state.copy(shelfTapToRead = true) }
        cover().performTouchInput { click() }
        compose.runOnIdle { assertEquals(3, opened); assertTrue(reads.isEmpty()) }
        row().performClick()
        row().performTouchInput { click(centerRight - androidx.compose.ui.geometry.Offset(16f, 0f)) }
        compose.runOnIdle { assertEquals(List(2) { book.ref to "chapter-3" }, reads); assertEquals(3, opened) }
        compose.runOnIdle { state = state.copy(shelfTapToRead = false) }
        row().performClick()
        compose.runOnIdle { assertEquals(4, opened); assertEquals(2, reads.size) }
    }

    @Test fun noProgressAndGridKeepOpeningDetailsInEInkMode() {
        state = state.copy(shelfTapToRead = true, positions = emptyMap())
        show(eInk = true)
        row().performClick()
        compose.runOnIdle {
            assertEquals(1, opened); assertTrue(reads.isEmpty())
            state = state.copy(positions = mapOf(book.ref.key to Position("chapter-3")))
            grid = true
        }
        row().performClick()
        row().performTouchInput { click() }
        compose.runOnIdle { assertEquals(3, opened); assertTrue(reads.isEmpty()) }
    }

    @Test fun longPressSelectionAndBusyOperationsNeverStartReading() {
        state = state.copy(shelfTapToRead = true)
        show()
        row().performTouchInput { longClick() }
        compose.runOnIdle { assertTrue(managing); assertEquals(setOf(book.ref.key), selection.keys); assertTrue(reads.isEmpty()); assertEquals(0, opened) }
        row().performClick()
        compose.runOnIdle { assertTrue(selection.isEmpty()); assertTrue(reads.isEmpty()); busy = true }
        row().performClick()
        compose.runOnIdle { assertTrue(selection.isEmpty()); managing = false }
        row().performClick()
        cover().performTouchInput { click() }
        compose.runOnIdle { assertTrue(reads.isEmpty()); assertEquals(0, opened) }
    }
}

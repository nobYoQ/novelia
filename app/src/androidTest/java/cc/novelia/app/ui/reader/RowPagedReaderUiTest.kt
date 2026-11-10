package cc.novelia.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.InlineContent
import cc.novelia.app.data.model.InlineKind
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RowPagedReaderUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings = ReaderSettings(paginationMode = "row", eInkMode = true, indent = false,
        horizontalPageTurn = true, fontSize = 20f, lineHeight = 1.4f)
    private val paragraphs = listOf(ReadingParagraph(7, listOf(TextPart((1..300).joinToString(" ") { "第${it}行的阅读内容。" }, "local"))))

    @Test fun changingLetterSpacingRebuildsTheRowsAndRetainsTheCharacterAnchor() {
        val state = RowPagingState(null)
        var preferences by mutableStateOf(settings)
        compose.setContent { NoveliaTheme("light") {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RowPagedReader(paragraphs, preferences, state, Modifier.requiredSize(300.dp, 300.dp),
                    imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move)
            }
        } }
        compose.waitUntil(10_000) { state.ready }
        val before = state.layout!!
        val anchor = state.anchor
        compose.runOnIdle { preferences = preferences.copy(letterSpacing = .15f) }
        compose.waitUntil(10_000) { state.ready && state.layout !== before }
        compose.runOnIdle {
            assertTrue(state.layout!!.lines.size > before.lines.size)
            assertEquals(anchor.sourceIndex, state.anchor.sourceIndex)
            assertEquals(anchor.textOffset, state.anchor.textOffset)
        }
    }

    @Test fun animatedTurnSettlesToTheSamePixelsAndReturnsToTheOriginalScreen() {
        val state = RowPagingState(null)
        compose.setContent { NoveliaTheme("light") {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RowPagedReader(paragraphs, settings.copy(eInkMode = false), state,
                    Modifier.requiredSize(300.dp, 300.dp).testTag("row-page"),
                    imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move)
            }
        } }
        compose.waitUntil(10_000) { state.ready }
        val page = compose.onNodeWithTag("row-page")
        fun pixels(): IntArray {
            val bitmap = page.captureToImage().asAndroidBitmap()
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        }
        val original = pixels()
        page.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.row > 0) }
        val settled = pixels()
        compose.mainClock.advanceTimeBy(400)
        assertArrayEquals(settled, pixels())
        page.performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, state.row) }
        assertArrayEquals(original, pixels())
    }

    @Test fun verticalPartialScrollThenHorizontalRoundTripKeepsTheActualAnchorWithoutAnimations() {
        val state = RowPagingState(null)
        var saved = -1
        compose.setContent { NoveliaTheme("light") {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RowPagedReader(paragraphs, settings, state, Modifier.requiredSize(300.dp, 300.dp).testTag("row-page"),
                    imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move,
                    onSavePosition = { saved = state.anchor.textOffset })
            }
        } }
        compose.waitUntil(10_000) { state.ready }
        val page = compose.onNodeWithTag("row-page")
        page.performTouchInput { swipe(Offset(150f, 230f), Offset(150f, 150f), 1000) }
        var before = 0
        compose.runOnIdle {
            assertTrue(state.row > 0)
            assertTrue(state.row < state.layout!!.pageStarts[1])
            assertEquals(state.anchor.textOffset, saved)
            before = state.row
        }
        page.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertTrue(state.row > before) }
        page.performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(before, state.row) }
    }

    @Test fun traditionalPaginationIgnoresVerticalGesturesEvenForOldSettings() {
        val state = EInkPageState(null)
        compose.setContent { NoveliaTheme("light") {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                EInkPage(paragraphs, settings.copy(paginationMode = "auto", scrollPageTurn = true), state,
                    Modifier.requiredSize(300.dp, 300.dp).testTag("fixed-page"),
                    imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move)
            }
        } }
        compose.waitUntil(10_000) { state.ready }
        compose.onNodeWithTag("fixed-page").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, state.pageIndex) }
        compose.onNodeWithTag("fixed-page").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, state.pageIndex) }
    }

    @Test fun externalSeekAndFindChangeTheDisplayedTextAndThemeUpdatesImmediately() {
        val state = RowPagingState(null)
        var dark by mutableStateOf(false)
        compose.setContent { NoveliaTheme("light") {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RowPagedReader(paragraphs, settings, state, Modifier.requiredSize(300.dp, 300.dp).testTag("row-page"),
                    imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move,
                    background = if(dark) Color.Black else Color.White, foreground = if(dark) Color.White else Color.Black)
            }
        } }
        compose.waitUntil(10_000) { state.ready }
        compose.runOnIdle { state.seekPage(3) }
        fun verifyVisible() {
            val line = state.layout!!.lines[state.row]
            val expected = paragraphs[0].parts[0].text.substring(line.start, line.end)
            compose.onNodeWithText(expected, substring = true, useUnmergedTree = true).assertIsDisplayed()
        }
        verifyVisible()
        compose.runOnIdle { state.find(0, 1200, 7) }
        verifyVisible()
        compose.runOnIdle { state.move(1); dark = true }
        val pixels = compose.onNodeWithTag("row-page").captureToImage().toPixelMap()
        assertEquals(Color.Black, pixels[pixels.width - 1, pixels.height - 1])
    }

    @Test fun turningToAnIllustrationShowsTheActualImageAndSupportsLongPress() {
        val state = RowPagingState(null)
        val image = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val content = paragraphs + ReadingParagraph(8, emptyList(), localImageId = "fixture")
        compose.setContent { NoveliaTheme("light") {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RowPagedReader(content, settings, state, Modifier.requiredSize(300.dp, 300.dp).testTag("row-page"),
                    imageModel = { image }, onToggleMenu = {}, onSelect = {}, onPage = state::move)
            }
        } }
        compose.waitUntil(10_000) { state.ready }
        compose.runOnIdle { state.seekPage(state.pages.lastIndex - 1); state.move(1) }
        val illustration = compose.onNodeWithContentDescription("小说插图", useUnmergedTree = true)
        compose.waitUntil(10_000) {
            runCatching { illustration.captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] == Color.Blue } }.getOrDefault(false)
        }
        illustration.assertIsDisplayed()
        illustration.performTouchInput { longClick() }
        compose.onNodeWithContentDescription("关闭插图").assertExists()
    }

    @Test fun rowModeKeepsFootnotesLongPressAndChapterHeading() {
        val state = RowPagingState(null)
        val body = "东京的夜色很美。"
        val note = "这里是脚注内容"
        val content = listOf(ReadingParagraph(0, listOf(TextPart(body, "local", inline = listOf(InlineContent(0, 2, InlineKind.Note, note))))))
        var selected = 0
        var opened: String? = null
        compose.setContent { NoveliaTheme("light") {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RowPagedReader(content, settings, state, Modifier.requiredSize(300.dp, 300.dp),
                    imageModel = { null }, onToggleMenu = {}, onSelect = { selected++ }, onPage = state::move,
                    onNote = { opened = it }, chapterTitle = "第一章 测试标题")
            }
        } }
        compose.waitUntil(10_000) { state.ready }
        compose.onNodeWithTag("reader-page-chapter-title").assertIsDisplayed()
        val layout = measureEInkChapter(content, settings, 300, 300, 1f, 1f).layouts.getValue(0)
        val point = Offset((layout.getPrimaryHorizontal(0) + layout.getPrimaryHorizontal(2)) / 2f,
            (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f)
        val text = compose.onNodeWithText(body, useUnmergedTree = true)
        text.performTouchInput { click(point) }
        compose.runOnIdle { assertEquals(note, opened) }
        text.performTouchInput { longClick(point) }
        compose.runOnIdle { assertEquals(1, selected) }
    }

    @Test fun rowGestureRespectsInteractionLock() {
        val state = RowPagingState(null)
        compose.setContent { NoveliaTheme("light") {
            RowPagedReader(paragraphs, settings, state, Modifier.requiredSize(300.dp, 300.dp).testTag("row-page"),
                imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move, interactionEnabled = false)
        } }
        compose.waitUntil(10_000) { state.ready }
        compose.onNodeWithTag("row-page").performTouchInput { swipeUp(); swipeLeft() }
        compose.runOnIdle { assertEquals(0, state.row) }
    }
}

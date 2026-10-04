package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
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
import cc.novelia.app.R
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.TextPart
import cc.novelia.app.ui.theme.LocalReducedMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderTapNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val paragraphs = listOf(ReadingParagraph(0, listOf(TextPart("沿着林间小路前行。".repeat(400), "gpt"))))
    private val state = EInkPageState(null)
    private var settings by mutableStateOf(ReaderSettings(paginationMode = "auto", tapPageTurn = true,
        scrollPageTurn = true, horizontalPageTurn = true))
    private var active by mutableStateOf(true)
    private var menus = 0
    private var selections = 0
    private val turns = mutableListOf<Int>()

    private fun paged(content: List<ReadingParagraph> = paragraphs) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides true) {
                    EInkPage(content, settings, state, Modifier.size(327.dp, 420.dp).testTag("page"),
                        imageModel = { R.drawable.midori_reading }, onToggleMenu = { menus++ }, onSelect = { selections++ },
                        onPage = { turns += it; state.move(it) }, interactionEnabled = active,
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp))
                }
            }
        }
        compose.waitUntil(10_000) { state.ready }
    }

    @Test fun zonesIncludeMarginsAndKeepSwipesAndSemanticMenuActionsIndependent() {
        paged()
        val page = compose.onNodeWithTag("page")
        page.performTouchInput { click(Offset(width - 4f, centerY)) }
        compose.runOnIdle { assertEquals(1, state.pageIndex); assertEquals(listOf(1), turns); assertEquals(0, menus) }
        page.performTouchInput { click(Offset(4f, centerY)) }
        compose.runOnIdle { assertEquals(0, state.pageIndex); assertEquals(listOf(1, -1), turns) }
        page.performTouchInput { click(center) }
        page.performClick()
        compose.runOnIdle { assertEquals(2, menus); settings = settings.copy(tapPageTurn = false) }
        page.performTouchInput { click(Offset(width * .85f, centerY)) }
        compose.runOnIdle { assertEquals(3, menus); assertEquals(0, state.pageIndex) }
        page.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, state.pageIndex); settings = settings.copy(tapPageTurn = true, eInkMode = true) }
        page.performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(2, state.pageIndex) }
        page.performTouchInput { click(Offset(width * .85f, centerY)) }
        compose.runOnIdle { assertEquals(3, state.pageIndex); assertEquals(3, menus); assertEquals(5, turns.size) }
        page.performTouchInput { swipe(center, Offset(width + 30f, centerY)) }
        compose.runOnIdle { assertEquals(2, state.pageIndex); assertEquals(-1, turns.last()); assertEquals(3, menus) }
    }

    @Test fun longPressCancelledMultiPointerAndDisabledDirectionDragsNeverBecomeTaps() {
        paged()
        val page = compose.onNodeWithTag("page")
        page.performTouchInput { longClick(Offset(width * .85f, 50f)) }
        compose.runOnIdle { assertEquals(1, selections); assertTrue(turns.isEmpty()); assertEquals(0, menus) }
        page.performTouchInput { down(center); moveBy(Offset(-160f, 0f)); cancel() }
        page.performTouchInput { down(center); moveBy(Offset(-100f, 0f)); moveTo(center); up() }
        page.performTouchInput {
            down(Offset(width * .85f, centerY))
            down(pointerId = 1, position = center)
            up()
            up(pointerId = 1)
        }
        page.performTouchInput {
            down(center)
            moveBy(Offset(0f, -100f))
            down(pointerId = 1, position = center + Offset(40f, 0f))
            up(pointerId = 1)
            up()
        }
        compose.runOnIdle { settings = settings.copy(horizontalPageTurn = false, scrollPageTurn = false) }
        page.performTouchInput { swipeLeft(); swipeUp() }
        compose.runOnIdle { assertTrue(turns.isEmpty()); assertEquals(0, menus) }
        page.performTouchInput { click(Offset(width * .85f, centerY)) }
        compose.runOnIdle { assertEquals(listOf(1), turns) }
    }

    @Test fun preferenceOrInteractionChangesCancelAnInFlightTap() {
        paged()
        val page = compose.onNodeWithTag("page")
        page.performTouchInput { down(Offset(width * .85f, centerY)) }
        compose.runOnIdle { settings = settings.copy(tapPageTurn = false) }
        page.performTouchInput { up() }
        page.performTouchInput { down(center) }
        compose.runOnIdle { active = false }
        compose.runOnIdle { active = true }
        page.performTouchInput { up() }
        compose.runOnIdle { assertTrue(turns.isEmpty()); assertEquals(0, menus) }
        page.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, menus) }
        compose.runOnIdle { active = false; settings = settings.copy(tapPageTurn = true) }
        page.performTouchInput { click(Offset(width * .85f, centerY)); swipeLeft() }
        compose.runOnIdle { assertEquals(2, menus); assertTrue(turns.isEmpty()) }
    }

    @Test fun illustrationTapAndLongPressRetainDifferentActions() {
        paged(listOf(ReadingParagraph(0, emptyList(), imageUrl = "https://example.com/unused.png")))
        val page = compose.onNodeWithTag("page")
        page.performTouchInput { click(Offset(width * .85f, centerY)) }
        compose.runOnIdle { assertEquals(listOf(1), turns); assertEquals(0, menus) }
        page.performTouchInput { longClick(Offset(width * .85f, centerY)) }
        compose.onNodeWithContentDescription("关闭插图").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(1), turns); assertEquals(0, menus) }
    }

    @Test fun scrollingRetainsLongPressAndChildControlsWithoutDuplicateBodyClicks() {
        var firstItem = { 0 }
        var buttons = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    val scroll = rememberLazyListState()
                    firstItem = { scroll.firstVisibleItemIndex }
                    val navigation = rememberReaderTapNavigation(true, onToggleMenu = { menus++ }, onPage = { turns += it })
                    LazyColumn(state = scroll, modifier = Modifier.size(327.dp, 420.dp).testTag("scroll").readerTapNavigation(navigation),
                        contentPadding = PaddingValues(24.dp)) {
                        item { Button(onClick = { buttons++ }, modifier = Modifier.fillMaxWidth().testTag("button")) { Text("阅读下一章") } }
                        items(30) { index ->
                            ReaderTextParagraph(ReadingParagraph(index, listOf(TextPart("第 $index 段正文。".repeat(3), "gpt"))), settings, Unit,
                                Color.Black, navigation::click, { selections++ }, null) { _, _ -> }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("button").performTouchInput { click(Offset(width * .85f, centerY)) }
        compose.runOnIdle { assertEquals(1, buttons); assertTrue(turns.isEmpty()); assertEquals(0, menus) }
        val scroll = compose.onNodeWithTag("scroll")
        scroll.performTouchInput { click(Offset(width * .85f, centerY)) }
        compose.runOnIdle { assertEquals(listOf(1), turns) }
        scroll.performTouchInput { longClick(Offset(width * .85f, centerY)) }
        compose.runOnIdle { assertEquals(1, selections) }
        scroll.performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(firstItem() > 0); assertEquals(listOf(1), turns); assertEquals(0, menus) }
    }
}

package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ShelfControlsCollapseTest {
    @get:Rule val compose = createComposeRule()

    @Test fun arrowFoldsBothRowsWithoutReservingSpaceAndRestoresLastChoice() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        withShelf { restoration ->
            val toggle = compose.onNodeWithTag("shelf-controls-toggle")
            val viewport = compose.onNodeWithTag("shelf-books")
            val partition = compose.onNodeWithTag("shelf-page-0")
            fun tabBounds() = (0..2).map { compose.onNodeWithTag("shelf-tab-$it").getUnclippedBoundsInRoot() }
            val tabs = tabBounds()
            toggle.assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
            val expandedTop = viewport.getUnclippedBoundsInRoot().top
            assertEquals(compose.onNodeWithTag("local-results-header").getUnclippedBoundsInRoot().bottom, expandedTop)
            assertFloatingInset(viewport, toggle)

            val target = toggle.fetchSemanticsNode().boundsInRoot
            viewport.performTouchInput {
                click(Offset(center.x, target.height * 1.35f))
                click(Offset(center.x + target.width * .64f, target.height * .35f))
            }
            toggle.assertContentDescriptionEquals("收起书架工具栏")

            toggle.performTouchInput { click(center) }
            compose.onNodeWithTag("local-toolbar").assertDoesNotExist()
            compose.onNodeWithTag("local-results-header").assertDoesNotExist()
            assertEquals(tabs, tabBounds())
            val foldedTop = viewport.getUnclippedBoundsInRoot().top
            assertEquals(partition.getUnclippedBoundsInRoot().top, foldedTop)
            assertFloatingInset(viewport, toggle)
            runBlocking { app.store.flush() }
            assertFalse(LocalStore(app).state.value.shelfControlsExpanded)

            compose.onNodeWithTag("shelf-tab-1").performClick()
            compose.onNodeWithTag("local-toolbar").assertDoesNotExist()
            restoration.emulateSavedInstanceStateRestore()
            toggle.assertContentDescriptionEquals("展开书架工具栏").performClick()
            compose.onNodeWithTag("local-toolbar").assertIsDisplayed()
            compose.onNodeWithTag("shelf-tab-0").performClick()
            assertEquals(expandedTop, viewport.getUnclippedBoundsInRoot().top)

            compose.onNodeWithTag("local-search-toggle").performClick()
            compose.onNodeWithTag("local-search-field").performTextInput("森林来信")
            toggle.performClick()
            compose.onNodeWithTag("local-search-field").assertDoesNotExist()
            toggle.performClick()
            compose.onNodeWithTag("local-clear-query").assertTextContains("森林来信", substring = true)
            compose.onNodeWithTag("local-clear-query").performClick()
            compose.onNodeWithTag("local-layout-toggle").performClick()
            val grid = compose.onNodeWithTag("shelf-books-grid")
            assertFloatingInset(grid, toggle)
            toggle.performTouchInput { click(center) }
            assertEquals(partition.getUnclippedBoundsInRoot().top, grid.getUnclippedBoundsInRoot().top)
            assertEquals(tabs, tabBounds())
            toggle.performClick()
            runBlocking { app.store.flush() }
            assertTrue(LocalStore(app).state.value.shelfControlsExpanded)
        }
    }

    @Test fun arrowFollowsTheBookViewportThroughBothHeightTransitions() {
        withShelf(reducedMotion = false) {
            val toggle = compose.onNodeWithTag("shelf-controls-toggle")
            val viewport = compose.onNodeWithTag("shelf-books")
            fun currentTop() = viewport.getUnclippedBoundsInRoot().top
            repeat(2) {
                val before = currentTop()
                compose.mainClock.autoAdvance = false
                try {
                    toggle.performClick()
                    compose.mainClock.advanceTimeBy(96)
                    val during = currentTop()
                    assertFloatingInset(viewport, toggle)
                    compose.mainClock.advanceTimeBy(400)
                    val after = currentTop()
                    assertTrue("书目在过渡中应经过起点和终点之间", during > minOf(before, after) && during < maxOf(before, after))
                    assertFloatingInset(viewport, toggle)
                } finally { compose.mainClock.autoAdvance = true }
            }
        }
    }

    @Test fun floatingButtonSlidesToHalfOnForwardScrollAndReturnsOnReverse() {
        withShelf(reducedMotion = false) {
            val toggle = compose.onNodeWithTag("shelf-controls-toggle")
            fun visibleHeight() = toggle.getUnclippedBoundsInRoot().let { it.bottom - it.top }
            for(tag in listOf("shelf-books", "shelf-books-grid")) {
                val viewport = compose.onNodeWithTag(tag)
                val top = viewport.getUnclippedBoundsInRoot().top
                toggle.assertHeightIsEqualTo(44.dp)
                assertFloatingInset(viewport, toggle)

                fun startDrag(start: Float, end: Float) = viewport.performTouchInput {
                    val x = width * .2f
                    down(Offset(x, height * start))
                    repeat(4) { step ->
                        moveTo(Offset(x, height * (start + (end - start) * (step + 1) / 4f)), delayMillis = 16)
                    }
                }
                fun releaseDrag() = viewport.performTouchInput { advanceEventTime(160); up() }

                compose.mainClock.autoAdvance = false
                try {
                    startDrag(.85f, .25f)
                    compose.mainClock.advanceTimeBy(96)
                    val tucking = visibleHeight()
                    assertTrue("$tag 缩回的中间高度为 $tucking", tucking > 22.dp && tucking < 44.dp)
                    compose.mainClock.advanceTimeBy(400)
                    toggle.assertHeightIsEqualTo(22.dp)
                    assertEquals(top, toggle.getUnclippedBoundsInRoot().top)
                    assertEquals(top, viewport.getUnclippedBoundsInRoot().top)
                    releaseDrag()

                    startDrag(.35f, .65f)
                    compose.mainClock.advanceTimeBy(96)
                    val returning = visibleHeight()
                    assertTrue("$tag 滑出的中间高度为 $returning", returning > 22.dp && returning < 44.dp)
                    compose.mainClock.advanceTimeBy(400)
                    toggle.assertHeightIsEqualTo(44.dp)
                    assertFloatingInset(viewport, toggle)
                    assertEquals(top, viewport.getUnclippedBoundsInRoot().top)
                    releaseDrag()
                } finally { compose.mainClock.autoAdvance = true }

                startDrag(.85f, .25f)
                releaseDrag()
                toggle.assertHeightIsEqualTo(22.dp).performTouchInput { click(center) }
                compose.onNodeWithTag("local-toolbar").assertDoesNotExist()
                toggle.performTouchInput { click(center) }
                compose.onNodeWithTag("local-toolbar").assertIsDisplayed()
                if(tag == "shelf-books") compose.onNodeWithTag("local-layout-toggle").performClick()
            }
        }
    }

    @Test fun eInkPageTurnsTuckAndRevealTheButtonImmediately() {
        withShelf(eInk = true) {
            val toggle = compose.onNodeWithTag("shelf-controls-toggle")
            toggle.assertHeightIsEqualTo(44.dp)
            compose.onNodeWithText("下一屏").performClick()
            toggle.assertHeightIsEqualTo(22.dp)
            compose.onNodeWithText("上一屏").performClick()
            toggle.assertHeightIsEqualTo(44.dp)
        }
    }

    private fun assertFloatingInset(viewport: SemanticsNodeInteraction, toggle: SemanticsNodeInteraction) {
        val viewportTop = viewport.getUnclippedBoundsInRoot().top
        assertEquals(6f, (toggle.getUnclippedBoundsInRoot().top - viewportTop).value, .5f)
    }

    @Test fun compactLocalControlsKeepCommittedFiltersAndScrollPositionAcrossSheetsAndRestoration() {
        withShelf { restoration ->
            compose.onNodeWithText("批量整理").assertDoesNotExist()
            compose.onNodeWithTag("local-filter-toggle").performClick()
            compose.onNodeWithText("本地小说").performClick()
            compose.onNodeWithTag("local-filter-apply").performClick()
            compose.onNodeWithTag("local-sort-picker").performClick()
            compose.onNodeWithText("书名排序").performClick()
            compose.onNodeWithTag("shelf-books").performScrollToIndex(20)
            fun top() = compose.onNodeWithTag("shelf-book-local/collapse-20").getUnclippedBoundsInRoot().top
            val before = top()
            compose.onNodeWithTag("local-filter-toggle").performClick()
            compose.onNodeWithTag("local-reading-status-想读").performClick()
            compose.onNodeWithText("关闭面板").performClick()
            assertEquals(before, top())
            compose.onNodeWithTag("local-clear-type").assertTextContains("本地小说")
            compose.onNodeWithTag("local-clear-status").assertDoesNotExist()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("local-sort-picker").assertTextContains("书名排序", substring = true)
            compose.onNodeWithTag("local-clear-type").assertIsDisplayed()
            compose.onNodeWithTag("shelf-tab-1").performClick()
            compose.onNodeWithTag("local-filter-toggle").performClick()
            compose.onNodeWithText("文库分卷").assertIsDisplayed()
            compose.onNodeWithText("关闭面板").performClick()
            compose.onNodeWithTag("shelf-tab-0").performClick()
            compose.onNodeWithTag("local-clear-type").assertIsDisplayed()
        }
    }

    @Test fun longPressSelectionKeepsAllChoicesDuringEInkPageTurnsAndFinishesCleanly() {
        withShelf(eInk = true) {
            compose.onNodeWithTag("shelf-book-local/collapse-0").performTouchInput { longClick() }
            compose.onNodeWithText("已选 1 本").assertIsDisplayed()
            compose.onNodeWithText("全选").performClick()
            compose.onNodeWithText("下一屏").performClick()
            compose.onNodeWithText("已选 60 本").assertIsDisplayed()
            compose.onNodeWithTag("local-selection-move").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("local-selection-done").performClick()
            compose.onNodeWithTag("local-selection-actions").assertDoesNotExist()
            compose.onNodeWithTag("local-filter-toggle").assertIsDisplayed()
            compose.onNodeWithText("批量整理").assertDoesNotExist()
        }
    }

    private fun withShelf(eInk: Boolean = false, reducedMotion: Boolean = true, block: (StateRestorationTester) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val before = app.store.state.value
        try {
            app.store.update { it.copy(books = List(60) { index -> SavedBook(
                BookCard(BookRef("local", "collapse-$index"), "森林来信 ${index.toString().padStart(2, '0')}", authors = listOf("山川遥")), addedAt = 1L)
            }, positions = emptyMap(), shelfGrid = false, localFilesGrid = false, shelfControlsExpanded = true,
                rememberLastShelfFolder = false) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                AppInteractionMode(eInk, reducedMotion) {
                    NoveliaTheme("light") {
                        val nav = rememberNavController()
                        val scope = rememberCoroutineScope()
                        val c = remember(app, nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
                        ShelfScreen(c, onOpenBook = {})
                    }
                }
            }
            block(restoration)
        } finally { app.store.update { before } }
    }

}

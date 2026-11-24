package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.ui.components.base.PageControls
import cc.novelia.app.ui.theme.AppInteractionMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DiscoverWenkuLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val books = List(40) { BookCard(BookRef("wenku", "book-$it"), "文库作品 $it", authors = listOf("测试作者")) }
    private var reduced by mutableStateOf(false)
    private var eInk by mutableStateOf(false)
    private var clicked: BookCard? = null
    private var page = 0

    @Composable private fun Results(items: List<BookCard> = books, fontScale: Float = 1f) {
        var grid by rememberSaveable { mutableStateOf(true) }
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            AppInteractionMode(eInk = eInk, reducedMotion = reduced) {
                MaterialTheme {
                    Surface(Modifier.statusBarsPadding()) {
                        Column(Modifier.width(360.dp).height(560.dp)) {
                            DiscoverToolbar(emptyList(), emptyList(), 0, "浏览全部", false,
                                {}, null, {}, {}, {}, {}, wenkuGrid = grid, onToggleLayout = { grid = !grid })
                            DiscoverWenkuResults(items, grid, { clicked = it }, Modifier.weight(1f),
                                emptyContent = { Text("没有找到匹配的作品") },
                                footer = { PageControls(0, 3) { page = it } })
                        }
                    }
                }
            }
        }
    }

    @Test fun gridHasTwoCoversPerRowAndBothLayoutsOpenTheSameBook() {
        compose.setContent { Results(fontScale = 1.5f) }
        val first = compose.onNodeWithTag("discover-wenku-cover-wenku/book-0")
        val second = compose.onNodeWithTag("discover-wenku-cover-wenku/book-1")
        val a = first.getUnclippedBoundsInRoot()
        val b = second.getUnclippedBoundsInRoot()
        assertEquals(a.top, b.top)
        assertTrue(a.right < b.left)
        assertEquals(1.5f, (a.bottom - a.top).value / (a.right - a.left).value, .02f)
        screenshot("wenku-grid-large-text")
        second.performClick()
        compose.runOnIdle { assertEquals(books[1], clicked) }
        compose.onNodeWithContentDescription("切换为列表布局").performClick()
        compose.onNodeWithTag("discover-wenku-grid").assertDoesNotExist()
        compose.onNodeWithText("文库作品 1").performClick()
        compose.runOnIdle { assertEquals(books[1], clicked) }
        compose.onAllNodesWithText("测试作者 · 文库小说").onFirst().assertIsDisplayed()
        screenshot("wenku-list-large-text")
    }

    @Test fun switchingLayoutsAndRestoringStateKeepsTheVisibleBooks() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Results() }
        compose.onNodeWithTag("discover-wenku-grid").performScrollToIndex(8)
        compose.onNodeWithContentDescription("切换为列表布局").performClick()
        compose.onNodeWithText("文库作品 16").assertIsDisplayed()
        compose.onNodeWithTag("discover-wenku-list").performScrollToIndex(19)
        compose.onNodeWithContentDescription("切换为网格布局").performClick()
        compose.onNodeWithTag("discover-wenku-cover-wenku/book-19").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("discover-wenku-cover-wenku/book-19").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换为列表布局").performClick()
        compose.onNodeWithText("文库作品 18").assertIsDisplayed()
    }

    @Test fun transitionHasIntermediateContentAndSupportsReversalAndReducedMotion() {
        compose.setContent { Results() }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("切换为列表布局").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("discover-wenku-grid").assertExists()
        compose.onNodeWithTag("discover-wenku-list").assertExists()
        screenshot("wenku-layout-transition")
        compose.onNodeWithContentDescription("切换为网格布局").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("discover-wenku-list").assertDoesNotExist()
        compose.onNodeWithContentDescription("切换为列表布局").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("discover-wenku-grid").assertDoesNotExist()
        compose.onNodeWithTag("discover-wenku-list").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
    }

    @Test fun eInkKeepsScreenPagingAndSwitchesWithoutOutgoingContent() {
        eInk = true
        compose.setContent { Results() }
        compose.onNodeWithText("下一屏").performClick()
        compose.onNodeWithTag("discover-wenku-cover-wenku/book-0").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("切换为列表布局").performClick()
        compose.onNodeWithTag("discover-wenku-grid").assertDoesNotExist()
        compose.onNodeWithTag("discover-wenku-list").assertIsDisplayed()
        compose.onNodeWithText("下一屏").assertIsDisplayed()
    }

    @Test fun emptyFilteredPageStillShowsPagingInBothLayouts() {
        compose.setContent { Results(items = emptyList()) }
        fun assertFullWidthPaging() {
            compose.onNodeWithTag("page-controls").assertWidthIsEqualTo(360.dp)
            val navigation = compose.onNodeWithTag("page-navigation").fetchSemanticsNode().boundsInRoot
            val toggle = compose.onNodeWithTag("page-jump-toggle").fetchSemanticsNode().boundsInRoot
            assertEquals(navigation.center.y, toggle.center.y, 1f)
        }
        compose.onNodeWithText("没有找到匹配的作品").assertIsDisplayed()
        assertFullWidthPaging()
        compose.onNodeWithContentDescription("下一页").performClick()
        compose.runOnIdle { assertEquals(1, page) }
        compose.onNodeWithContentDescription("切换为列表布局").performClick()
        compose.onNodeWithText("没有找到匹配的作品").assertIsDisplayed()
        assertFullWidthPaging()
        compose.onNodeWithContentDescription("第 3 页").performClick()
        compose.runOnIdle { assertEquals(2, page) }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

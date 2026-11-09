package cc.novelia.app.ui.shelf

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.RootDestinationLayout
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class ContinueReadingButtonTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: NoveliaApplication
    private lateinit var before: LibraryState
    private val books = List(18) { index ->
        SavedBook(BookCard(BookRef("syosetu", "resume-$index"), if(index == 7)
            "一部有着很长名称的小说：在星空下寻找下一段故事" else "书架作品 $index"), addedAt = (100 - index).toLong())
    }
    private val recent = books[7].book
    private val position = Position("chapter-12", title = "第十二章 在漫长旅途的终点再次相遇", updatedAt = 200L, chapterIndex = 11)
    private var navigationHeight = 0.dp
    private val opened = mutableListOf<BookRef>()

    @Before fun prepare() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        assertNull("Use an anonymous dedicated test emulator", app.session.profile.value)
        before = app.store.state.value
        app.store.update { it.copy(lastShelfLocation = cc.novelia.app.data.model.ShelfLocation(), books = books, shelfGrid = false, shelfTapToRead = false, positions = mapOf(
            recent.ref.key to position,
            books[2].book.ref.key to position.copy(updatedAt = 100L),
            "syosetu/not-in-shelf" to position.copy(updatedAt = 300L),
        )) }
    }

    @After fun restore() { app.store.update { before } }

    private fun showShelf(eInk: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                AppInteractionMode(eInk = eInk, reducedMotion = true) {
                    NoveliaTheme("light") {
                        val nav = rememberNavController()
                        val scope = rememberCoroutineScope()
                        val controller = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
                        Box(Modifier.fillMaxSize().testTag("resume-test-root")) {
                            NavHost(nav, startDestination = "shelf", enterTransition = { EnterTransition.None },
                                exitTransition = { ExitTransition.None }) {
                                composable("shelf") {
                                    RootDestinationLayout("shelf", { nav.navigate(it) },
                                        onNavigationBarHeightChanged = { navigationHeight = it }) {
                                        ShelfScreen(controller, onOpenBook = { opened += it })
                                    }
                                }
                                composable("reader/{provider}/{id}/{chapter}") { entry ->
                                    Text("${entry.arguments?.getString("provider")}/${entry.arguments?.getString("id")}/${entry.arguments?.getString("chapter")}",
                                        Modifier.testTag("resumed-destination"))
                                }
                                composable("discover") { Text("发现测试页") }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun shelfRowOptInKeepsCoverOpeningDetailsAndResumesThroughAccessibility() {
        showShelf()
        val book = books[2].book
        compose.onNodeWithTag("shelf-books").performScrollToIndex(2)
        val row = compose.onNodeWithTag("shelf-book-${book.ref.key}")
        row.performClick()
        compose.runOnIdle { assertEquals(listOf(book.ref), opened); app.store.update { it.copy(shelfTapToRead = true) } }
        compose.onNodeWithContentDescription("${book.title} 默认封面", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(book.ref, book.ref), opened) }
        row.performClick()
        compose.onNodeWithTag("resumed-destination").assertTextEquals("${book.ref.key}/${position.chapterId}")
    }

    @Test fun shelfRowTouchResumesAfterLeavingSelectionInEInkMode() {
        val book = books[0].book
        app.store.update { it.copy(books = listOf(books[0]), shelfTapToRead = true, positions = mapOf(book.ref.key to position)) }
        showShelf(eInk = true)
        val row = compose.onNodeWithTag("shelf-book-${book.ref.key}")
        row.performTouchInput { longClick() }
        compose.onNodeWithText("已选 1 本").assertIsDisplayed()
        compose.onNodeWithTag("resumed-destination").assertDoesNotExist()
        row.performClick()
        compose.onNodeWithText("已选 0 本").assertIsDisplayed()
        compose.onNodeWithTag("local-selection-done").performClick()
        row.performTouchInput { click(centerRight - androidx.compose.ui.geometry.Offset(16f, 0f)) }
        compose.onNodeWithTag("resumed-destination").assertTextEquals("${book.ref.key}/${position.chapterId}")
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
    }

    @Test fun buttonCollapsesWhileBrowsingAndResumesTheLatestSavedBookInOneTap() {
        showShelf()
        compose.onNodeWithText("继续上次的故事").assertDoesNotExist()
        compose.onNodeWithTag("shelf-continue-label", true).assertTextEquals("继续")
        compose.onNodeWithTag("shelf-continue-title", true).assertTextEquals(recent.title)
        compose.onNodeWithTag("shelf-continue-chapter", true).assertTextEquals(position.title)
        val button = compose.onNodeWithTag("shelf-continue-reading")
        val bounds = button.getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag("resume-test-root").getUnclippedBoundsInRoot()
        assertEquals((root.bottom - navigationHeight - 16.dp).value, bounds.bottom.value, 1f)
        assertEquals((root.right - 16.dp).value, bounds.right.value, 1f)
        assertTrue(bounds.right - bounds.left <= 184.dp)
        screenshot("continue-reading-shelf")
        compose.onNodeWithTag("shelf-books").performScrollToIndex(17)
        val compact = button.getUnclippedBoundsInRoot()
        assertEquals(bounds.bottom, compact.bottom)
        assertEquals(bounds.right, compact.right)
        assertEquals(48.dp, compact.right - compact.left)
        compose.onNodeWithTag("shelf-continue-title", true).assertDoesNotExist()
        val last = compose.onNodeWithTag("shelf-book-${books.last().book.ref.key}").getUnclippedBoundsInRoot()
        assertTrue("列表最后一本书不能被悬浮按钮挡住", last.bottom <= compact.top)
        screenshot("continue-reading-compact")
        compose.onNodeWithTag("shelf-books").performScrollToIndex(0)
        compose.onNodeWithTag("shelf-continue-title", true).assertIsDisplayed()
        compose.onNodeWithTag("shelf-books").performScrollToIndex(17)
        button.performClick()
        compose.onNodeWithTag("resumed-destination").assertTextEquals("${recent.ref.key}/${position.chapterId}")
    }

    @Test fun buttonIsLimitedToMyShelfAndHidesDuringBatchManagementOrWithoutProgress() {
        showShelf()
        compose.onNodeWithTag("local-filter-toggle").performClick()
        compose.onNodeWithText("本地小说").performClick()
        compose.onNodeWithTag("local-filter-apply").performClick()
        compose.onNodeWithText("没有符合条件的小说").assertIsDisplayed()
        compose.onNodeWithTag("shelf-continue-reading").assertIsDisplayed()
        compose.onNodeWithText("清除筛选").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("shelf-books").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shelf-books").performScrollToNode(hasTestTag("shelf-book-${recent.ref.key}"))
        compose.onNodeWithTag("shelf-book-${recent.ref.key}").performTouchInput { longClick() }
        compose.onNodeWithTag("shelf-continue-reading").assertDoesNotExist()
        compose.onNodeWithTag("local-selection-done").performClick()
        compose.onNodeWithTag("shelf-continue-reading").assertIsDisplayed()
        compose.onNodeWithTag("shelf-tab-1").performClick()
        compose.onNodeWithTag("shelf-continue-reading").assertDoesNotExist()
        compose.onNodeWithTag("shelf-tab-2").performClick()
        compose.onNodeWithTag("shelf-continue-reading").assertDoesNotExist()
        compose.onNodeWithTag("shelf-tab-0").performClick()
        compose.onNodeWithTag("shelf-continue-reading").assertIsDisplayed()
        compose.runOnIdle { app.store.update { it.copy(positions = emptyMap()) } }
        compose.onNodeWithTag("shelf-continue-reading").assertDoesNotExist()
        compose.runOnIdle { app.store.update { it.copy(positions = mapOf(recent.ref.key to position)) } }
        compose.onNodeWithTag("shelf-continue-reading").assertIsDisplayed()
        compose.onNodeWithContentDescription("发现", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("shelf-continue-reading").assertDoesNotExist()
    }

    @Test fun largeTextRemainsThreeLinesAndEInkPageButtonsStayAccessible() {
        showShelf(eInk = true, fontScale = 1.5f)
        val bounds = compose.onNodeWithTag("shelf-continue-reading").getUnclippedBoundsInRoot()
        val next = compose.onNodeWithText("下一屏").getUnclippedBoundsInRoot()
        assertTrue("续读按钮需要避开电子纸翻屏栏", bounds.bottom < next.top)
        assertTrue(bounds.right - bounds.left <= 184.dp)
        for(tag in listOf("shelf-continue-label", "shelf-continue-title", "shelf-continue-chapter")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
        }
        screenshot("continue-reading-eink-large-text")
        compose.onNodeWithText("下一屏").performClick()
        val compact = compose.onNodeWithTag("shelf-continue-reading").getUnclippedBoundsInRoot()
        assertEquals(bounds.bottom, compact.bottom)
        assertEquals(bounds.right, compact.right)
        assertEquals(48.dp, compact.right - compact.left)
    }

    @Test fun missingChapterTitleUsesKnownChapterNumberWithoutInventingOne() {
        var current by mutableStateOf(position.copy(title = ""))
        compose.setContent {
            AppInteractionMode(eInk = false, reducedMotion = true) {
                NoveliaTheme("light") { ContinueReadingButton(recent, current, {}) }
            }
        }
        compose.onNodeWithTag("shelf-continue-chapter", true).assertTextEquals("第 12 章")
        compose.runOnIdle { current = current.copy(chapterIndex = null) }
        compose.onNodeWithTag("shelf-continue-chapter", true).assertTextEquals("已记录阅读位置")
    }

    @Test fun backgroundAndHitAreaAnimateTogetherAndCanReverseBeforeSettling() {
        var expanded by mutableStateOf(true)
        var clicks = 0
        var background = Color.Unspecified
        var pixelDensity = 1f
        compose.setContent {
            AppInteractionMode(eInk = false, reducedMotion = false) {
                NoveliaTheme("light") {
                    background = MaterialTheme.colorScheme.secondaryContainer
                    pixelDensity = LocalDensity.current.density
                    Box(Modifier.size(260.dp, 200.dp).background(Color.White)) {
                        ContinueReadingButton(recent, position, { clicks++ },
                            Modifier.align(Alignment.BottomEnd), expanded = expanded)
                    }
                }
            }
        }
        val button = compose.onNodeWithTag("shelf-continue-reading")
        val full = button.getUnclippedBoundsInRoot()
        assertEquals(184f, (full.right - full.left).value, .001f)
        assertTrue(full.bottom - full.top > 48.dp)
        compose.mainClock.autoAdvance = false
        try {
            fun changeExpanded(value: Boolean) {
                compose.runOnIdle { expanded = value }
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeByFrame()
            }
            fun assertIntermediate(): DpRect {
                val bounds = button.getUnclippedBoundsInRoot()
                assertTrue("宽度应连续变化", bounds.right - bounds.left > 48.dp && bounds.right - bounds.left < full.right - full.left)
                assertTrue("高度应连续变化", bounds.bottom - bounds.top > 48.dp && bounds.bottom - bounds.top < full.bottom - full.top)
                assertEquals(full.right, bounds.right)
                assertEquals(full.bottom, bounds.bottom)
                // 取中间帧左侧背景，防止只有外层占位缩放，而实际背景已经跳成小方块。
                val pixels = button.captureToImage().toPixelMap()
                // 底部内边距没有正在淡出的文字，避免把字形像素误判成背景缺失。
                val color = pixels[(12 * pixelDensity).toInt(), pixels.height - (4 * pixelDensity).toInt()]
                assertEquals(background.red, color.red, .03f)
                assertEquals(background.green, color.green, .03f)
                assertEquals(background.blue, color.blue, .03f)
                return bounds
            }
            changeExpanded(false)
            compose.mainClock.advanceTimeBy(80)
            screenshot("continue-reading-collapse-mid")
            assertIntermediate()
            // 点击动画中的左侧区域也应立即续读，而不只剩右侧图标可以点击。
            button.performTouchInput { click(androidx.compose.ui.geometry.Offset(8 * pixelDensity, center.y)) }
            compose.runOnIdle { assertEquals(1, clicks) }
            val reversing = button.getUnclippedBoundsInRoot()
            changeExpanded(true)
            compose.mainClock.advanceTimeBy(96)
            val growing = button.getUnclippedBoundsInRoot()
            assertTrue("中途反向应从当前尺寸继续展开", growing.right - growing.left > reversing.right - reversing.left &&
                growing.right - growing.left < full.right - full.left)
            assertEquals(full.right, growing.right)
            assertEquals(full.bottom, growing.bottom)
            compose.mainClock.advanceTimeBy(400)
            assertEquals(full, button.getUnclippedBoundsInRoot())
            compose.onNodeWithTag("shelf-continue-label", true).assertTextEquals("继续")
            compose.onNodeWithTag("shelf-continue-title", true).assertTextEquals(recent.title)
            changeExpanded(false)
            compose.mainClock.advanceTimeBy(400)
            val compact = button.getUnclippedBoundsInRoot()
            assertEquals(48f, (compact.right - compact.left).value, .001f)
            assertEquals(48f, (compact.bottom - compact.top).value, .001f)
            assertEquals(full.right, compact.right)
            assertEquals(full.bottom, compact.bottom)
            compose.onNodeWithTag("shelf-continue-title", true).assertDoesNotExist()
            button.performClick()
            compose.runOnIdle { assertEquals(2, clicks) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(app.getExternalFilesDir("screenshots"), "$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

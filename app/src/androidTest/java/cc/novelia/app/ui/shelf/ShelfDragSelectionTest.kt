package cc.novelia.app.ui.shelf

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.TestShelfRootLayout
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class ShelfDragSelectionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: NoveliaApplication
    private lateinit var before: LibraryState
    private var opened = 0
    private val books = List(24) { i -> SavedBook(BookCard(BookRef("local", "select-$i"),
        "长按选择示例 ${i.toString().padStart(2, '0')}", subtitle = "本地小说"), addedAt = (100 - i).toLong()) }

    @Before fun prepare() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        assertNull("Use an anonymous dedicated test emulator", app.session.profile.value)
        before = app.store.state.value
        app.store.update { it.copy(books = books, positions = emptyMap()) }
    }
    @After fun restore() { app.store.update { before } }

    private fun show(reducedMotion: Boolean = true) {
        compose.setContent { AppInteractionMode(false, reducedMotion) { NoveliaTheme("dark") {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val c = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
            TestShelfRootLayout { ShelfScreen(c, onOpenBook = { opened++ }) }
        } } }
    }
    private fun book(index: Int) = compose.onNodeWithTag("shelf-book-${books[index].book.ref.key}")
    private fun count(): Int = compose.onNodeWithText("已选 ", substring = true).fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text.substringAfter("已选 ").substringBefore(" 本").toInt()

    @Test fun longPressSelectsOneAndSingleBookActionsStayAvailableWithoutABatchEntry() {
        show()
        compose.onNodeWithText("批量整理").assertDoesNotExist()
        compose.onNodeWithTag("shelf-books").performTouchInput { swipeUp() }
        compose.onNodeWithTag("local-selection-header").assertDoesNotExist()
        compose.onNodeWithTag("shelf-books").performScrollToIndex(0)
        screenshot("shelf-compact-live")
        book(0).performTouchInput { longClick() }
        compose.onNodeWithText("已选 1 本").assertIsDisplayed()
        compose.onNodeWithTag("local-selection-actions").assertIsDisplayed()
        listOf("移动", "状态", "下载", "更多").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("发现", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("local-selection-download").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, opened) }
        screenshot("shelf-selection-live")
        compose.onNodeWithTag("local-selection-more").performClick()
        compose.onNodeWithText("管理这本书").performClick()
        compose.onNodeWithText("重命名").assertIsDisplayed()
        compose.onNodeWithText("关闭面板").performClick()
        compose.onNodeWithTag("local-selection-status").performClick()
        compose.onNodeWithText("想读").performClick()
        compose.runOnIdle { assertEquals("想读", app.store.state.value.books.first { it.book.ref == books[0].book.ref }.status) }
        compose.onNodeWithTag("local-selection-actions").assertDoesNotExist()
        compose.onNodeWithContentDescription("发现", useUnmergedTree = true).assertIsDisplayed()
        book(0).performTouchInput { longClick() }
        book(0).performClick()
        compose.onNodeWithText("已选 0 本").assertIsDisplayed()
        compose.onNodeWithTag("local-selection-more").assertIsNotEnabled()
        compose.onNodeWithTag("local-selection-done").performClick()
        book(0).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun heldDragSelectsARangeBacktracksAndAutoScrollsWithoutOpeningBooks() {
        show()
        val list = compose.onNodeWithTag("shelf-books")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val start = book(0).fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        val second = book(1).fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        val third = book(2).fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput { down(start); moveTo(start + Offset(0f, 1f), delayMillis = 700) }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithText("已选 1 本").assertIsDisplayed()
            list.performTouchInput { moveTo(third, delayMillis = 120) }
            compose.mainClock.advanceTimeBy(32)
            assertEquals(3, count())
            list.performTouchInput { moveTo(second, delayMillis = 120) }
            compose.mainClock.advanceTimeBy(32)
            assertEquals(2, count())
            val current = list.fetchSemanticsNode().boundsInRoot
            list.performTouchInput { moveTo(Offset(start.x, current.height - 8), delayMillis = 120) }
            compose.mainClock.advanceTimeBy(1800)
            assertTrue("停留在列表边缘应自动滚动并继续选择", count() > 4)
            list.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(400)
        } finally { compose.mainClock.autoAdvance = true }
        compose.runOnIdle { assertEquals(0, opened) }
        val selected = count()
        list.performTouchInput { swipeDown() }
        assertEquals("松手后的普通滑动只滚动，不改变选择", selected, count())
        list.performScrollToIndex(0)
        val afterScroll = list.fetchSemanticsNode().boundsInRoot
        val firstAgain = book(0).fetchSemanticsNode().boundsInRoot.center - afterScroll.topLeft
        val secondAgain = book(1).fetchSemanticsNode().boundsInRoot.center - afterScroll.topLeft
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput { down(firstAgain); moveTo(firstAgain + Offset(0f, 1f), delayMillis = 700) }
            compose.mainClock.advanceTimeBy(32)
            list.performTouchInput { moveTo(secondAgain, delayMillis = 120); up() }
            compose.mainClock.advanceTimeBy(400)
        } finally { compose.mainClock.autoAdvance = true }
        assertEquals("选择模式内仍可长按拖动，从已选书籍开始连续取消", selected - 2, count())
        book(0).performClick()
        assertEquals("松手后仍能单点勾选", selected - 1, count())
        compose.onNodeWithTag("local-selection-done").performClick()
        compose.onNodeWithTag("local-filter-toggle").assertIsDisplayed()
    }

    @Test fun mountedVolumesCanBeLongPressedAndUpdatedIndividually() {
        val parent = SavedBook(BookCard(BookRef("wenku", "selection-parent"), "分卷选择测试"), volumesExpanded = true)
        app.store.update { it.copy(books = listOf(parent) + books.take(2).map { book -> book.copy(parentWenkuKey = parent.book.ref.key) }) }
        show()
        compose.onNodeWithTag("shelf-volume-${books[0].book.ref.key}").performTouchInput { longClick() }
        compose.onNodeWithText("已选 1 本").assertIsDisplayed()
        compose.onNodeWithTag("local-selection-status").performClick()
        compose.onNodeWithText("读完").performClick()
        compose.runOnIdle {
            val saved = app.store.state.value.books.associateBy { it.book.ref.key }
            assertEquals("读完", saved.getValue(books[0].book.ref.key).status)
            assertEquals(parent.status, saved.getValue(parent.book.ref.key).status)
            assertEquals(books[1].status, saved.getValue(books[1].book.ref.key).status)
            assertEquals(parent.book.ref.key, saved.getValue(books[0].book.ref.key).parentWenkuKey)
        }
    }

    @Test fun ordinaryMotionUsesABottomFilterPanelAndPreservesTheListOnDismissal() {
        show(reducedMotion = false)
        val top = book(0).getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("local-filter-toggle").performClick()
        compose.onNodeWithText("文库小说").performClick()
        screenshot("shelf-filter-live", "local-filter-sheet")
        compose.onNodeWithText("网络小说").performClick()
        screenshot("shelf-filter-web-v2", "local-filter-sheet")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("local-filter-sheet").fetchSemanticsNodes().isEmpty() }
        assertEquals(top, book(0).getUnclippedBoundsInRoot().top)
        compose.onNodeWithTag("local-clear-type").assertDoesNotExist()
    }

    private fun screenshot(name: String, tag: String? = null) {
        val bitmap = (if(tag == null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
        val file = File(app.getExternalFilesDir("screenshots"), "$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

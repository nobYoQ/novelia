package cc.novelia.app.ui.shelf

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.book.BookScreen
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

class ShelfGridUiTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private lateinit var app: NoveliaApplication
    private lateinit var before: LibraryState
    private val opened = mutableListOf<BookRef>()
    private val books = List(24) { i -> SavedBook(BookCard(BookRef("local", "grid-$i"), "封面示例 ${i.toString().padStart(2, '0')}"), addedAt = (100 - i).toLong()) }
    private val parent = SavedBook(BookCard(BookRef("wenku", "grid-parent"), "月色与纸飞机", publishedVolumeCount = 12), addedAt = 200)
    private val volumes = List(8) { i -> books[i].copy(book = books[i].book.copy(title = "第 ${i + 1} 卷 · ${listOf("春日来信", "夏日约定", "夜色中的车站", "回家的长路")[i % 4]}"), parentWenkuKey = parent.book.ref.key) }

    @Before fun prepare() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        assertNull("Use an anonymous dedicated test emulator", app.session.profile.value)
        before = app.store.state.value
        app.store.update { it.copy(books = books, positions = emptyMap(), shelfGrid = true, localFilesGrid = false, cloudShelfGrid = false,
            shelfGridDensity = ShelfGridDensity.Auto) }
    }
    @After fun restore() { app.store.update { before } }
    private fun show(reduced: Boolean = true, eInk: Boolean = false, fontScale: Float = 1f) {
        restoration.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
        AppInteractionMode(eInk, reduced) { NoveliaTheme("dark") {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val c = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
            var selected by remember { mutableStateOf<BookRef?>(null) }
            TestShelfRootLayout {
                val ref = selected
                if(ref == null) ShelfScreen(c, onOpenBook = { opened += it; selected = it.takeIf { book -> book.isWenku } })
                else BookScreen(c, ref, onBack = { selected = null }, startAtContents = true)
            }
        } } } }
    }
    private fun book(i: Int) = compose.onNodeWithTag("shelf-book-${books[i].book.ref.key}")
    private fun volume(i: Int) = compose.onNodeWithTag("shelf-volume-${volumes[i].book.ref.key}")
    private fun seedVolumes(withProgress: Boolean = false) {
        app.store.update { it.copy(books = listOf(parent) + volumes + books.drop(8), positions = if(withProgress)
            mapOf(volumes[2].book.ref.key to Position("chapter-4", title = "第 4 章 月台来信", chapterIndex = 3, chapterCount = 10, updatedAt = 300)) else emptyMap()) }
    }
    private fun openVolumes() {
        compose.onNodeWithTag("shelf-book-${parent.book.ref.key}").performClick()
        compose.onNodeWithTag("wenku-volume-list").assertExists()
    }

    @Test fun switchingLayoutsPreservesTheVisibleBookAndEachSectionPreference() {
        show(reduced = false)
        compose.onNodeWithTag("shelf-books-grid").performScrollToIndex(5)
        book(10).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        book(10).assertIsDisplayed()
        compose.onNodeWithTag("local-layout-toggle").performClick()
        compose.onNodeWithTag("shelf-books").assertIsDisplayed()
        book(10).assertIsDisplayed()
        compose.onNodeWithTag("local-layout-toggle").performClick()
        book(10).assertIsDisplayed()
        compose.onNodeWithText("本地文件").performClick()
        compose.onNodeWithTag("shelf-books").assertIsDisplayed()
        compose.onNodeWithTag("local-layout-toggle").performClick()
        compose.onNodeWithTag("shelf-books-grid").assertIsDisplayed()
        compose.runOnIdle { assertTrue(app.store.state.value.shelfGrid); assertTrue(app.store.state.value.localFilesGrid); assertFalse(app.store.state.value.cloudShelfGrid) }
    }

    @Test fun customColumnsReflowBothLocalGridSections() {
        app.store.update { it.copy(localFilesGrid = true, shelfGridDensity = ShelfGridDensity.Three) }
        show()
        fun assertThreeInTheFirstRow() {
            val first = book(0).getUnclippedBoundsInRoot().top
            assertEquals(first, book(2).getUnclippedBoundsInRoot().top)
            assertTrue(book(3).getUnclippedBoundsInRoot().top > first)
        }
        assertThreeInTheFirstRow()
        compose.onNodeWithText("本地文件").performClick()
        assertThreeInTheFirstRow()
    }

    @Test fun gridDraggingUsesBothColumnsBacktracksAndAutoScrolls() {
        show()
        val list = compose.onNodeWithTag("shelf-books-grid")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val start = book(1).fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        val third = book(2).fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput { down(start); moveTo(start + Offset(0f, 1f), delayMillis = 700) }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithText("已选 1 本").assertIsDisplayed()
            book(1).assertIsSelected(); book(0).assertIsNotSelected()
            list.performTouchInput { moveTo(third, delayMillis = 100) }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithText("已选 2 本").assertIsDisplayed()
            list.performTouchInput { moveTo(start, delayMillis = 100) }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithText("已选 1 本").assertIsDisplayed()
            val current = list.fetchSemanticsNode().boundsInRoot
            list.performTouchInput { moveTo(Offset(start.x, current.height - 4), delayMillis = 100) }
            compose.mainClock.advanceTimeBy(1800)
            list.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(400)
        } finally { compose.mainClock.autoAdvance = true }
        val count = compose.onNodeWithText("已选 ", substring = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        assertTrue(count.substringAfter("已选 ").substringBefore(" 本").toInt() > 3)
        list.performTouchInput { swipeDown() }
        compose.onNodeWithText(count).assertIsDisplayed()
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
    }

    @Test fun gridSelectsOnlyParentBooksAndOpensVolumesInDetails() {
        seedVolumes(withProgress = true); show()
        compose.onNodeWithTag("shelf-book-${parent.book.ref.key}").performTouchInput { longClick() }
        compose.onNodeWithTag("local-select-all").performClick()
        compose.onNodeWithText("已选 17 本").assertIsDisplayed()
        compose.onNodeWithTag("local-selection-done").performClick()
        volume(0).assertDoesNotExist()
        val parentCard = hasAnyAncestor(hasTestTag("shelf-book-${parent.book.ref.key}"))
        compose.onNode(parentCard and hasText("已读 40%"), true).assertIsDisplayed()
        compose.onNode(parentCard and hasText("第 4 章 月台来信", substring = true), true).assertDoesNotExist()
        compose.onNodeWithText("本地 8 卷").assertDoesNotExist()
        openVolumes()
        compose.onNodeWithText("其他挂载分卷").assertIsDisplayed()
        compose.onNodeWithTag("volume-continue-title", true).assertTextEquals(volumes[2].book.title)
        volume(0).assertIsDisplayed()
        compose.runOnIdle { assertEquals(parent.book.ref, opened.single()) }
    }

    @Test fun matchingVolumesOpenTheWholeCollectionFromTheSearchResult() {
        seedVolumes(); show()
        compose.onNodeWithTag("local-search-toggle").performClick()
        compose.onNodeWithTag("local-search-field").performTextInput("夜色中的车站")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 本 · 2 分卷", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("匹配 2 / 8 卷").assertDoesNotExist()
        volume(2).assertDoesNotExist()
        openVolumes()
        val list = compose.onNodeWithTag("wenku-volume-list")
        list.performScrollToNode(hasTestTag("shelf-volume-${volumes[0].book.ref.key}"))
        volume(0).assertIsDisplayed()
        list.performScrollToNode(hasTestTag("shelf-volume-${volumes[2].book.ref.key}"))
        volume(2).assertIsDisplayed()
    }

    @Test fun detailContinueFloatsAboveTheLastVolumeAndExpandsAgainAtTheTop() {
        seedVolumes(withProgress = true)
        show()
        openVolumes()
        val list = compose.onNodeWithTag("wenku-volume-list")
        val button = compose.onNodeWithTag("volume-continue-reading")
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val expanded = button.fetchSemanticsNode().boundsInRoot
        assertTrue(expanded.center.x > viewport.center.x && expanded.center.y > viewport.center.y)
        compose.onNodeWithTag("volume-continue-chapter", true).assertTextEquals("第 4 章 月台来信")
        screenshot("volume-detail-layout")
        list.performTouchInput { repeat(4) { swipeUp() } }
        val compact = button.fetchSemanticsNode().boundsInRoot
        assertTrue(compact.width < expanded.width)
        assertEquals(expanded.right, compact.right, 1f)
        assertEquals(expanded.bottom, compact.bottom, 1f)
        assertTrue(volume(volumes.lastIndex).fetchSemanticsNode().boundsInRoot.bottom <= compact.top)
        list.performScrollToIndex(0)
        compose.onNodeWithTag("volume-continue-title", true).assertTextEquals(volumes[2].book.title)
    }

    @Test fun emptyCollectionOffersMountingInDetails() {
        app.store.update { it.copy(books = listOf(parent)) }; show()
        openVolumes()
        compose.onNodeWithText("管理挂载").performClick()
        compose.onNodeWithTag("wenku-volume-picker").assertIsDisplayed()
        compose.onAllNodesWithText("关闭面板").onLast().performClick()
        compose.onNodeWithTag("wenku-volume-list").assertIsDisplayed()
    }

    @Test fun largeTextAndEInkKeepVolumeOrderingReachableInDetails() {
        seedVolumes(withProgress = true); show(eInk = true, fontScale = 2f)
        openVolumes()
        compose.onNodeWithTag("volume-down-${volumes[0].book.ref.key}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(volumes[1].book.ref.key, app.store.state.value.books.first { it.book.ref == parent.book.ref }.volumeOrder.first()) }
    }

    private fun screenshot(name: String, tag: String? = null) {
        val bitmap = (if(tag == null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir("screenshots"), "$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

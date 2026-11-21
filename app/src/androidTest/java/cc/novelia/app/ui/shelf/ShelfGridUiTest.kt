package cc.novelia.app.ui.shelf

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
            lastShelfLocation = cc.novelia.app.data.model.ShelfLocation(), shelfGridDensity = ShelfGridDensity.Auto, shelfGridStyle = ShelfGridStyle.Classic) }
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

    @Test fun novelAndComicShelvesClassifyLegacyEpubsAndKeepSeparateListsAndResumeTargets() {
        val marker = "novelia-image:" + "a".repeat(64)
        fun legacy(title: String, paragraphs: List<String>): SavedBook {
            val id = java.util.UUID.randomUUID().toString()
            app.store.saveDocument(LocalDocument(id, title, "epub", listOf(LocalChapter("c", "正文", paragraphs))))
            return SavedBook(BookCard(BookRef("local", id), title, subtitle = "EPUB · 1 章"))
        }
        val comic = legacy("图册", listOf(marker)).copy(parentWenkuKey = parent.book.ref.key)
        val novel = legacy("图文故事", listOf(marker, "正文"))
        try {
            app.store.update { it.copy(books = listOf(parent.copy(volumesExpanded = true), novel, comic),
                lastShelfLocation = ShelfLocation(), rememberLastShelfFolder = true, shelfGrid = false, comicShelfGrid = true,
                positions = mapOf(novel.book.ref.key to Position("c", updatedAt = 1), comic.book.ref.key to Position("c", updatedAt = 2))) }
            show()
            compose.waitUntil(15_000) { app.store.state.value.books.filter { it.book.ref.isLocal }.all { it.book.medium != null } }
            fun row(saved: SavedBook) = compose.onNodeWithTag("shelf-book-${saved.book.ref.key}")
            fun resume() = compose.onNodeWithTag("shelf-continue-title", true)
            val toggle = compose.onNodeWithTag("shelf-medium")
            toggle.assertTextEquals("小说")
            row(novel).assertExists()
            row(comic).assertDoesNotExist()
            compose.onNodeWithTag("shelf-volume-${comic.book.ref.key}").assertDoesNotExist()
            resume().assertTextEquals(novel.book.title)
            compose.onNodeWithTag("shelf-tab-1").performClick()
            row(novel).assertExists()
            row(comic).assertDoesNotExist()
            fun fileControl(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("shelf-page-1")))
            fileControl("local-search-toggle").performClick()
            fileControl("local-search-field").performTextInput("图文")
            fileControl("local-search-toggle").performClick()
            toggle.performClick().assertTextEquals("漫画")
            row(comic).assertExists()
            row(novel).assertDoesNotExist()
            row(parent).assertDoesNotExist()
            compose.onNodeWithText("云端收藏").assertDoesNotExist()
            compose.onNodeWithTag("shelf-books-grid").assertExists()
            resume().assertTextEquals(comic.book.title)
            restoration.emulateSavedInstanceStateRestore()
            toggle.assertTextEquals("漫画")
            row(comic).assertExists()
            toggle.performClick().assertTextEquals("小说")
            compose.onNodeWithTag("shelf-tab-1").assertIsSelected()
            row(novel).assertExists()
            fileControl("local-search-toggle").performClick()
            fileControl("local-search-field").assertTextContains("图文")
            assertEquals(parent.book.ref.key, app.store.state.value.books.single { it.book.ref == comic.book.ref }.parentWenkuKey)
        } finally {
            app.store.removeDocument(comic.book.ref.id)
            app.store.removeDocument(novel.book.ref.id)
        }
    }

    @Test fun compactTitleKeepsSwitchAndImportVisibleAtLargeFontSize() {
        var synced = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                Box(Modifier.width(320.dp)) {
                    ShelfTopBar(BookMedium.Novel, switchEnabled = false, onSwitch = {},
                        importEnabled = true, onImport = {}, onHistory = {}, onUpdates = {}, onFavoriteSync = { synced = true })
                }
            }
        }
        val switch = compose.onNodeWithTag("shelf-medium").assertIsDisplayed().assertIsNotEnabled().assertTextEquals("小说")
        fun SemanticsNodeInteraction.centerY() = getUnclippedBoundsInRoot().let { (it.top.value + it.bottom.value) / 2 }
        assertEquals(compose.onNodeWithText("书架").centerY(), switch.centerY(), 1f)
        compose.onNodeWithContentDescription("导入本地文件").assertIsDisplayed()
        compose.onNodeWithContentDescription("书架操作").performClick()
        compose.onNodeWithText("云端与本地收藏同步").performClick()
        compose.runOnIdle { assertTrue(synced) }
    }

    @Test fun importChoicesExplainFilesAndFolderStructureBeforeOpeningPicker() {
        show(reduced = false)
        val toolbarImport = compose.onNodeWithContentDescription("添加到书架")
        toolbarImport.assertIsDisplayed()
        cc.novelia.app.ui.saveTestScreenshot("shelf-title-switch.png")
        toolbarImport.performClick()
        compose.onNodeWithText("导入本地文件").performClick()
        compose.onNodeWithText("ZIP/CBZ、RAR/CBR、7z/CB7 和 TAR/CBT", substring = true).assertIsDisplayed()
        compose.onNodeWithText("第一卷/001.jpg", substring = true).assertIsDisplayed()
        compose.onNodeWithText("作品/第02话.cbz", substring = true).assertIsDisplayed()
        cc.novelia.app.ui.saveTestScreenshot("shelf-import-options.png")
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

    @Test fun fullCoverPlacesTheTitleAndProgressBelowTheImageAndBothOpenTheCollection() {
        seedVolumes(withProgress = true)
        show()
        val key = parent.book.ref.key
        val withinCard = hasAnyAncestor(hasTestTag("shelf-book-$key"))
        val cover = compose.onNodeWithTag("shelf-grid-cover-$key", true)
        val title = compose.onNode(withinCard and hasText(parent.book.title), true)
        assertTrue(title.fetchSemanticsNode().boundsInRoot.bottom <= cover.fetchSemanticsNode().boundsInRoot.bottom)
        screenshot("grid-style-classic")
        compose.runOnIdle { app.store.update { it.copy(shelfGridStyle = ShelfGridStyle.FullCover) } }
        assertTrue(title.fetchSemanticsNode().boundsInRoot.top >= cover.fetchSemanticsNode().boundsInRoot.bottom)
        val progress = compose.onNode(withinCard and hasText("40%"), true)
        progress.assertIsDisplayed()
        compose.onNode(withinCard and hasContentDescription("已读 40%"), true).assertIsDisplayed()
        screenshot("grid-style-full-cover")
        progress.performTouchInput { click() }
        compose.onNodeWithTag("wenku-volume-list").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        cover.performTouchInput { click() }
        compose.onNodeWithTag("wenku-volume-list").assertIsDisplayed()
    }

    @Test fun fullCoverShowsBothEdgesOfAWideImage() {
        val file = File.createTempFile("wide-grid-cover", ".png", app.cacheDir)
        try {
            val colors = IntArray(180 * 90) { i -> when(i % 180) {
                in 0..39 -> Color.RED
                in 140..179 -> Color.BLUE
                else -> Color.GREEN
            } }
            val bitmap = Bitmap.createBitmap(colors, 180, 90, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val first = books.first()
            app.store.update { it.copy(shelfGridStyle = ShelfGridStyle.FullCover,
                books = listOf(first.copy(book = first.book.copy(cover = Uri.fromFile(file).toString())))) }
            show()
            val cover = compose.onNodeWithTag("shelf-grid-cover-${first.book.ref.key}", true)
            compose.waitUntil(10_000) {
                val frame = cover.captureToImage().asAndroidBitmap()
                frame.getPixel(frame.width / 10, frame.height / 2) == Color.RED &&
                    frame.getPixel(frame.width * 9 / 10, frame.height / 2) == Color.BLUE &&
                    frame.getPixel(frame.width / 2, frame.height / 10) != Color.GREEN
            }
        } finally { file.delete() }
    }

    @Test fun customColumnsReflowBothLocalGridSections() {
        app.store.update { it.copy(localFilesGrid = true, shelfGridDensity = ShelfGridDensity.Three, shelfGridStyle = ShelfGridStyle.FullCover) }
        show()
        fun assertThreeInTheFirstRow() {
            val firstBook = books[0].book
            val cover = compose.onNodeWithTag("shelf-grid-cover-${firstBook.ref.key}", true).fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithText(firstBook.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(title.top >= cover.bottom)
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

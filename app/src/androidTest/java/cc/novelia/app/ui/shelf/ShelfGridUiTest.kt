package cc.novelia.app.ui.shelf

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
        app.store.update { it.copy(lastShelfLocation = cc.novelia.app.data.model.ShelfLocation(), books = books, positions = emptyMap(), shelfGrid = true, localFilesGrid = false, cloudShelfGrid = false) }
    }
    @After fun restore() { app.store.update { before } }
    private fun show(reduced: Boolean = true, eInk: Boolean = false, fontScale: Float = 1f) {
        restoration.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
        AppInteractionMode(eInk, reduced) { NoveliaTheme("dark") {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val c = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
            RootDestinationLayout("shelf", {}) { ShelfScreen(c, onOpenBook = { opened += it }) }
        } } } }
    }
    private fun book(i: Int) = compose.onNodeWithTag("shelf-book-${books[i].book.ref.key}")
    private fun volume(i: Int) = compose.onNodeWithTag("shelf-volume-${volumes[i].book.ref.key}")
    private fun seedVolumes(withProgress: Boolean = false) {
        app.store.update { it.copy(books = listOf(parent) + volumes + books.drop(8), positions = if(withProgress)
            mapOf(volumes[2].book.ref.key to Position("chapter-4", title = "第 4 章 月台来信", chapterIndex = 3, chapterCount = 10, updatedAt = 300)) else emptyMap()) }
    }
    private fun openVolumes() = compose.onNodeWithTag("shelf-grid-volumes-${parent.book.ref.key}").performClick()

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
        var checked = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                Box(Modifier.width(320.dp)) {
                    ShelfTopBar(BookMedium.Novel, switchEnabled = false, onSwitch = {},
                        importEnabled = true, onImport = {}, onHistory = {}, onUpdates = {}, onCheckUpdates = { checked = true })
                }
            }
        }
        compose.onNodeWithTag("shelf-medium").assertIsDisplayed().assertIsNotEnabled().assertTextEquals("小说")
        compose.onNodeWithContentDescription("导入本地文件").assertIsDisplayed()
        compose.onNodeWithContentDescription("书架操作").performClick()
        compose.onNodeWithText("检查更新").performClick()
        compose.runOnIdle { assertTrue(checked) }
    }

    @Test fun importChoicesExplainFilesAndFolderStructureBeforeOpeningPicker() {
        show(reduced = false)
        val toolbarImport = compose.onNodeWithContentDescription("导入本地文件")
        toolbarImport.assertIsDisplayed()
        cc.novelia.app.ui.saveTestScreenshot("shelf-title-switch.png")
        toolbarImport.performClick()
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

    @Test fun volumeSheetKeepsParentAndChildrenIndependentAndCanReorder() {
        seedVolumes(withProgress = true); show(reduced = false)
        screenshot("shelf-grid-live")
        compose.onNodeWithTag("shelf-book-${parent.book.ref.key}").performTouchInput { longClick() }
        compose.onNodeWithTag("local-select-all").performClick()
        compose.onNodeWithText("已选 17 本").assertIsDisplayed()
        compose.onNodeWithTag("local-selection-done").performClick()
        openVolumes()
        compose.onNodeWithText("本地 8 卷 · 出版 12 卷").assertIsDisplayed()
        compose.onNodeWithTag("volume-resume").assertIsDisplayed()
        screenshot("shelf-volumes-panel", "wenku-volumes-sheet")
        volume(0).performTouchInput { longClick() }
        compose.onNodeWithText("已选 1 本").assertIsDisplayed()
        compose.onNodeWithTag("local-selection-status").performClick()
        compose.onNodeWithText("读完").performClick()
        compose.runOnIdle {
            assertEquals("读完", app.store.state.value.books.first { it.book.ref == volumes[0].book.ref }.status)
            assertEquals(parent.status, app.store.state.value.books.first { it.book.ref == parent.book.ref }.status)
            assertEquals(volumes[1].status, app.store.state.value.books.first { it.book.ref == volumes[1].book.ref }.status)
        }
        compose.onNodeWithTag("volume-drag-${volumes[0].book.ref.key}").performClick()
        compose.onNodeWithText("下移一位").performClick()
        compose.runOnIdle { assertEquals(volumes[1].book.ref.key, app.store.state.value.books.first { it.book.ref == parent.book.ref }.volumeOrder.first()) }
        compose.onNodeWithTag("volume-sheet-close").performClick()
        compose.onNodeWithTag("wenku-volumes-sheet").assertDoesNotExist()
        compose.onNodeWithTag("shelf-books-grid").assertIsDisplayed()
        openVolumes(); volume(1).performClick()
        compose.runOnIdle { assertEquals(volumes[1].book.ref, opened.last()) }
    }

    @Test fun matchingVolumesStayAccessibleAndCannotSelectTheContextParent() {
        seedVolumes(); show()
        compose.onNodeWithTag("local-search-toggle").performClick()
        compose.onNodeWithTag("local-search-field").performTextInput("夜色中的车站")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("匹配 2 / 8 卷").fetchSemanticsNodes().isNotEmpty() }
        openVolumes()
        compose.onNodeWithText("匹配 2 卷").assertIsDisplayed()
        volume(2).assertIsDisplayed(); volume(0).assertDoesNotExist()
        compose.onNodeWithTag("volume-show-all").performClick()
        compose.onNodeWithTag("volume-show-all").assertDoesNotExist()
        screenshot("shelf-volumes-show-all", "wenku-volumes-sheet")
        volume(0).assertIsDisplayed()
        compose.onNodeWithTag("volume-sheet-close").performClick()
        compose.onNodeWithTag("local-clear-query").assertDoesNotExist()
        compose.onNodeWithTag("local-search-field").assertTextContains("夜色中的车站")
    }

    @Test fun middleVolumesReachBothEndsWithoutMovingTheSheetAndAnimateOnRelease() {
        seedVolumes(withProgress = true); show(reduced = false); openVolumes()
        val list = compose.onNodeWithTag("volume-books")
        for(toEnd in listOf(false, true)) {
            val index = if(toEnd) 4 else 2
            val key = volumes[index].book.ref.key
            val handle = compose.onNodeWithTag("volume-drag-$key")
            list.performScrollToNode(hasTestTag("volume-drag-$key"))
            val bounds = list.fetchSemanticsNode().boundsInRoot
            val sheetTop = compose.onNodeWithTag("wenku-volumes-sheet").fetchSemanticsNode().boundsInRoot.top
            val start = handle.fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
            val edge = if(toEnd) bounds.height - 2f else 2f
            val savedOrder = app.store.state.value.books.first { it.book.ref == parent.book.ref }.volumeOrder
            compose.mainClock.autoAdvance = false
            try {
                list.performTouchInput { down(start) }
                repeat(10) { step ->
                    list.performTouchInput { moveTo(Offset(start.x, start.y + (edge - start.y) * (step + 1) / 10), delayMillis = 16) }
                    compose.mainClock.advanceTimeBy(32)
                }
                compose.mainClock.advanceTimeBy(2400)
                val heldTop = volume(index).fetchSemanticsNode().boundsInRoot.top
                repeat(5) {
                    compose.mainClock.advanceTimeBy(64)
                    assertEquals("到达首尾后拖拽行不能反复跳动", heldTop, volume(index).fetchSemanticsNode().boundsInRoot.top, 1f)
                    assertEquals("排序手势不能拖动底部面板", sheetTop, compose.onNodeWithTag("wenku-volumes-sheet").fetchSemanticsNode().boundsInRoot.top, 1f)
                }
                assertEquals("松手前不应保存排序草稿", savedOrder, app.store.state.value.books.first { it.book.ref == parent.book.ref }.volumeOrder)
                // 从边缘稍微拖回，制造可观察的归位距离，但不跨过相邻分卷。
                list.performTouchInput { moveTo(Offset(start.x, edge + if(toEnd) -40f else 40f), delayMillis = 32) }
                compose.mainClock.advanceTimeBy(32)
                // 外层行的语义边界可能被列表裁切；测量图层内的手柄才能观察实际绘制位移。
                val beforeDrop = handle.fetchSemanticsNode().boundsInRoot.top
                val screenshotName = if(toEnd) "volume-drop-bottom" else "volume-drop-top"
                screenshot("$screenshotName-held", "wenku-volumes-sheet")
                list.performTouchInput { up() }
                compose.mainClock.advanceTimeBy(48)
                val duringDrop = handle.fetchSemanticsNode().boundsInRoot.top
                screenshot("$screenshotName-during", "wenku-volumes-sheet")
                compose.mainClock.advanceTimeBy(400)
                val afterDrop = handle.fetchSemanticsNode().boundsInRoot.top
                screenshot("$screenshotName-finished", "wenku-volumes-sheet")
                val order = app.store.state.value.books.first { it.book.ref == parent.book.ref }.volumeOrder
                assertTrue("应保存排序结果：$order", order.isNotEmpty())
                assertEquals(key, if(toEnd) order.last() else order.first())
                assertEquals(volumes.map { it.book.ref.key }.toSet(), order.toSet())
                assertTrue("松手应平滑归位：$beforeDrop → $duringDrop → $afterDrop",
                    duringDrop > minOf(beforeDrop, afterDrop) && duringDrop < maxOf(beforeDrop, afterDrop))
                val finalBounds = volume(index).fetchSemanticsNode().boundsInRoot
                assertTrue("首尾分卷应完整留在列表内", finalBounds.top >= bounds.top - 1 && finalBounds.bottom <= bounds.bottom + 1)
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test fun volumeNeighborsAnimateOutOfTheWayAndCancelKeepsSavedOrder() {
        seedVolumes(withProgress = true); show(reduced = false); openVolumes()
        val list = compose.onNodeWithTag("volume-books")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val neighbor = volume(1).fetchSemanticsNode().boundsInRoot
        val key = volumes[2].book.ref.key
        val start = compose.onNodeWithTag("volume-drag-$key").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput { down(start); moveTo(start - Offset(0f, neighbor.height * .85f), delayMillis = 100) }
            compose.mainClock.advanceTimeBy(64)
            val during = volume(1).fetchSemanticsNode().boundsInRoot.top
            compose.mainClock.advanceTimeBy(300)
            val after = volume(1).fetchSemanticsNode().boundsInRoot.top
            assertTrue("被越过的分卷应平滑让位：${neighbor.top} → $during → $after", during > neighbor.top && during < after)
            list.performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(400)
            assertTrue(app.store.state.value.books.first { it.book.ref == parent.book.ref }.volumeOrder.isEmpty())
            assertEquals(neighbor.top, volume(1).fetchSemanticsNode().boundsInRoot.top, 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun emptyVolumesOfferMountingWithoutLeavingAnUnusableSheet() {
        app.store.update { it.copy(books = listOf(parent)) }; show()
        openVolumes()
        compose.onNodeWithText("尚未挂载本地分卷").assertIsDisplayed()
        compose.onNodeWithTag("volume-mount").performClick()
        compose.onNodeWithTag("wenku-volume-picker").assertIsDisplayed()
        compose.onAllNodesWithText("关闭面板").onLast().performClick()
        compose.onNodeWithTag("wenku-volumes-sheet").assertIsDisplayed()
    }

    @Test fun largeTextAndEInkKeepGridVolumesAndOrderingReachable() {
        seedVolumes(withProgress = true); show(eInk = true, fontScale = 2f)
        compose.onNodeWithTag("local-layout-toggle").assertIsDisplayed()
        openVolumes()
        compose.onNodeWithTag("volume-resume").assertIsDisplayed()
        compose.onNodeWithTag("volume-down-${volumes[0].book.ref.key}").performClick()
        compose.runOnIdle { assertEquals(volumes[1].book.ref.key, app.store.state.value.books.first { it.book.ref == parent.book.ref }.volumeOrder.first()) }
        compose.onNodeWithTag("volume-mount").assertIsDisplayed()
        screenshot("shelf-volumes-large", "wenku-volumes-sheet")
        compose.onNodeWithTag("volume-sheet-close").performClick()
        compose.onNodeWithTag("local-layout-toggle").performClick()
        compose.onNodeWithTag("shelf-books").assertIsDisplayed()
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

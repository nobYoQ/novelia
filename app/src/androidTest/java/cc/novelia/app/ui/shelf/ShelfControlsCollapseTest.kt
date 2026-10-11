package cc.novelia.app.ui.shelf

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.book.BookRow
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ShelfControlsCollapseTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bothLocalTabsKeepFiltersAndScrollPositionWhenTheWholeToolbarCollapses() {
        withShelf { restoration ->
            compose.onNodeWithTag("local-batch-manage").assertIsDisplayed().assertIsEnabled()
            val toggleBounds = arrowBounds("local")
            compose.onNodeWithTag("local-controls-expand").assertIsDisplayed().performClick()
            assertEquals(toggleBounds, arrowBounds("local"))
            assertToggleAboveBatch("local")
            compose.onNodeWithText("本地小说").performClick()
            compose.onNodeWithTag("local-sort-picker").performClick()
            compose.onNodeWithText("书名排序").performClick()
            compose.onNodeWithTag("shelf-books").performScrollToIndex(20)
            val anchor = "shelf-book-local/collapse-20"
            fun relativeTop(): Float = (compose.onNodeWithTag(anchor).getUnclippedBoundsInRoot().top -
                compose.onNodeWithTag("shelf-books").getUnclippedBoundsInRoot().top).value
            val before = relativeTop()
            compose.onNodeWithTag("local-controls-collapse").performClick()
            assertEquals(toggleBounds, arrowBounds("local"))
            compose.onNodeWithTag("local-controls-expand").assertTextContains("本地小说 · 全部收藏夹 · 书名排序", substring = true)
            listOf("local-novel-kind", "local-toolbar").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
            compose.onNodeWithTag("local-batch-manage").assertIsDisplayed().assertIsEnabled()
            assertEquals(before, relativeTop(), 1f)
            compose.onNodeWithTag("local-controls-expand").performClick()
            compose.onNodeWithText("本地小说").assertIsSelected()
            compose.onNodeWithText("书名排序").assertIsDisplayed()
            assertEquals(before, relativeTop(), 1f)
            compose.onNodeWithTag("shelf-books").performTouchInput { swipeUp() }
            compose.onNodeWithTag("local-toolbar").assertIsDisplayed()
            compose.onNodeWithTag("local-controls-collapse").assertIsDisplayed().performClick()
            compose.onNodeWithTag("local-controls-expand").assertIsDisplayed()
            compose.onNodeWithTag("local-batch-manage").assertIsDisplayed().performClick()
            compose.onNodeWithText("全选").performClick()
            compose.onNodeWithTag("shelf-books").performTouchInput { swipeUp() }
            compose.onNodeWithText("已选 60 本").assertIsDisplayed()
            compose.onNodeWithTag("local-batch-manage").assertIsDisplayed().assertIsEnabled().performClick()

            compose.onNodeWithText("本地文件").performClick()
            val filesToggleBounds = arrowBounds("local")
            compose.onNodeWithTag("local-controls-expand").assertIsDisplayed().performClick()
            assertEquals(filesToggleBounds, arrowBounds("local"))
            assertToggleAboveBatch("local")
            compose.onNodeWithText("全部文件").assertIsSelected()
            compose.onNodeWithTag("shelf-books").performTouchInput { swipeUp() }
            compose.onNodeWithTag("local-novel-kind").assertIsDisplayed()
            compose.onNodeWithText("我的书架").performClick()
            compose.onNodeWithTag("local-controls-expand").assertIsDisplayed()
            compose.onNodeWithText("本地文件").performClick()
            compose.onNodeWithTag("local-controls-collapse").assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("local-controls-collapse").assertIsDisplayed().performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("local-controls-expand").assertIsDisplayed()
            compose.onNodeWithTag("local-toolbar").assertDoesNotExist()
            compose.onNodeWithTag("local-batch-manage").assertIsDisplayed().assertIsEnabled()
        }
    }

    @Test fun localBatchSelectionRemainsReachableWhenFiltersCollapseAndDuringPageTurns() {
        withShelf(eInk = true) {
            compose.onNodeWithTag("local-batch-manage").assertIsDisplayed().performClick()
            compose.onNodeWithText("全选").performClick()
            compose.onNodeWithTag("shelf-books").performTouchInput { swipeUp() }
            compose.onNodeWithText("已选 60 本").assertIsDisplayed()
            compose.onNodeWithTag("local-controls-expand").performClick()
            compose.onNodeWithTag("local-controls-collapse").performClick()
            compose.onNodeWithText("移动 60 本").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("local-batch-manage").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("local-controls-expand").performClick()
            compose.onNodeWithTag("shelf-books").performTouchInput { swipeUp() }
            compose.onNodeWithTag("local-controls-collapse").assertIsDisplayed()
            compose.onNodeWithText("已选 60 本").assertIsDisplayed()
            compose.onNodeWithTag("local-batch-manage").performClick()
            compose.onNodeWithTag("shelf-books").performTouchInput { swipeUp() }
            compose.onNodeWithTag("local-controls-collapse").assertIsDisplayed().performClick()
            compose.onNodeWithTag("local-controls-expand").assertIsDisplayed()
            compose.onNodeWithText("移动 60 本").assertDoesNotExist()
            compose.onNodeWithTag("local-batch-manage").assertIsDisplayed().assertIsEnabled()
        }
    }

    @Test fun cloudSummaryAndBatchActionsFitNarrowScreensAndKeepSelectionWhenCollapsed() {
        var expanded by mutableStateOf(false)
        var searchExpanded by mutableStateOf(false)
        var query by mutableStateOf("")
        var submitted by mutableStateOf("")
        var managing by mutableStateOf(false)
        var selected by mutableIntStateOf(0)
        var busy by mutableStateOf(false)
        var kind by mutableIntStateOf(0)
        var sort by mutableStateOf("update")
        var fontScale by mutableFloatStateOf(1f)
        val list = LazyListState(12, 11)
        val folder = Folder("default", "默认收藏夹")
        val manage = { managing = !managing; selected = 0 }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppInteractionMode(false, true) {
                    NoveliaTheme("dark") {
                        Surface(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("shelf-controls-preview")) {
                            Column {
                                Text("书架", Modifier.padding(20.dp), style = MaterialTheme.typography.headlineLarge)
                                PrimaryTabRow(2) {
                                    listOf("我的书架", "本地文件", "云端收藏").forEachIndexed { index, title ->
                                        Tab(index == 2, {}, text = { Text(title) })
                                    }
                                }
                                ShelfControlsPanel(
                                    "${if(kind == 0) "网络小说" else "文库小说"} · ${folder.title} · ${if(sort == "update") "更新时间" else "收藏时间"}",
                                    expanded, { expanded = !expanded }, "cloud", headerActions = {
                                        if(kind == 0) ShelfSearchToggle(searchExpanded, submitted.isNotBlank(), { searchExpanded = !searchExpanded }, "cloud")
                                    }) {
                                    CloudNovelKindSwitch(kind) { kind = it; searchExpanded = false }
                                    CloudShelfToolbar(listOf(folder), folder, {}, sort, { sort = it }, 0, false, {}) {}
                                }
                                ShelfSearchField(searchExpanded && kind == 0, query, { query = it },
                                    onSubmit = { submitted = query.trim() }, onClear = { query = ""; submitted = "" },
                                    label = "搜索中 / 日标题或作者", tagPrefix = "cloud")
                                ShelfBatchHeader(if(managing) "已选 $selected 本" else "本页 20 本",
                                    managing, manage, "cloud", !busy)
                                CloudFavoriteBatchControls(managing, selected, 20, selected == 20, busy, 1,
                                    manage, { selected = if(selected == 20) 0 else 20 }, { selected = 0 }, {}, { busy = true }, showHeader = false)
                                AppLazyColumn(Modifier.weight(1f), state = list, listModifier = Modifier.testTag("preview-books")) {
                                    items(60, key = { it }) { index ->
                                        BookRow(BookCard(BookRef("syosetu", "preview-$index"), "旅人与森林来信 · ${index + 1}",
                                            authors = listOf("山川遥"), total = 282, novelType = "连载中", updateAt = 1704067200), {},
                                            Modifier.testTag("preview-book-$index"), compactMetadata = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        capture("shelf-cloud-collapsed.png")
        compose.onNodeWithTag("cloud-novel-kind").assertDoesNotExist()
        compose.onNodeWithTag("cloud-batch-manage").assertIsDisplayed().assertIsEnabled()
        val toggleBounds = arrowBounds("cloud")
        compose.onNodeWithTag("cloud-search-toggle").performClick()
        compose.onNodeWithTag("cloud-novel-kind").assertDoesNotExist()
        compose.onNodeWithTag("cloud-search-field").performTextInput("森林")
        compose.onNodeWithTag("cloud-search-field").performImeAction()
        compose.runOnIdle { assertEquals("森林", submitted); assertFalse(expanded) }
        val searchBounds = compose.onNodeWithTag("cloud-search-field").getUnclippedBoundsInRoot()
        assertTrue(searchBounds.top >= compose.onNodeWithTag("cloud-controls").getUnclippedBoundsInRoot().bottom)
        assertTrue(searchBounds.bottom <= compose.onNodeWithTag("cloud-batch-header").getUnclippedBoundsInRoot().top)
        compose.onNodeWithTag("cloud-search-toggle").performClick()
        compose.onNodeWithTag("cloud-search-field").assertDoesNotExist()
        compose.runOnIdle { assertEquals("森林", submitted) }
        compose.onNodeWithTag("cloud-search-toggle").performClick()
        compose.onNodeWithTag("cloud-search-clear").performClick()
        compose.runOnIdle { assertEquals("", query); assertEquals("", submitted) }
        compose.onNodeWithTag("cloud-search-toggle").performClick()
        assertEquals(toggleBounds, arrowBounds("cloud"))
        compose.onNodeWithTag("cloud-controls-expand").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(toggleBounds, arrowBounds("cloud"))
        assertToggleAboveBatch("cloud")
        capture("shelf-cloud-expanded.png")
        compose.onNodeWithText("文库小说").performClick()
        compose.onNodeWithTag("cloud-sort-picker").performClick()
        compose.onNodeWithText("收藏时间").performClick()
        compose.onNodeWithTag("cloud-controls-collapse").performClick()
        assertEquals(toggleBounds, arrowBounds("cloud"))
        compose.onNodeWithTag("cloud-controls-expand").assertTextContains("文库小说 · 默认收藏夹 · 收藏时间", substring = true)
        compose.runOnIdle { assertEquals(12, list.firstVisibleItemIndex); assertEquals(11, list.firstVisibleItemScrollOffset) }
        compose.onNodeWithTag("cloud-batch-manage").performClick()
        compose.onNodeWithText("全选本页").performClick()
        compose.onNodeWithTag("preview-books").performTouchInput { swipeUp() }
        compose.onNodeWithText("已选 20 本").assertIsDisplayed()
        compose.onNodeWithTag("cloud-batch-manage").assertIsDisplayed().assertIsEnabled()
        capture("shelf-cloud-selection-scrolled.png")
        compose.onNodeWithTag("cloud-controls-expand").performClick()
        compose.onNodeWithText("文库小说").assertIsSelected()
        compose.onNodeWithTag("preview-books").performTouchInput { swipeUp() }
        compose.onNodeWithTag("cloud-toolbar").assertIsDisplayed()
        compose.onNodeWithTag("cloud-controls-collapse").performClick()
        compose.onNodeWithText("已选 20 本").assertIsDisplayed()
        compose.onNodeWithText("加入本地收藏").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("cloud-batch-manage").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("取消云端收藏").performClick()
        compose.onNodeWithText("正在处理 1 / 20 本…").assertIsDisplayed()
        compose.onNodeWithTag("cloud-batch-manage").assertIsNotEnabled()
        compose.runOnIdle { busy = false; managing = false; selected = 0; fontScale = 2f }
        compose.onNodeWithTag("cloud-controls-expand").assertIsDisplayed()
        compose.onNodeWithText("展开").assertIsDisplayed()
        compose.onNodeWithTag("cloud-batch-manage").assertIsDisplayed().assertIsEnabled()
        capture("shelf-cloud-collapsed-large-font.png")
        compose.onNodeWithTag("cloud-controls-expand").performClick()
        compose.onNodeWithTag("cloud-controls-collapse").assertIsDisplayed()
        compose.onNodeWithTag("cloud-batch-manage").assertIsDisplayed()
        capture("shelf-cloud-expanded-large-font.png")
    }

    @Test fun filterPanelAnimatesWithoutMovingTheToggleAndStopsForReducedMotionOrEInk() {
        var expanded by mutableStateOf(false)
        var reducedMotion by mutableStateOf(false)
        var eInk by mutableStateOf(false)
        compose.setContent {
            AppInteractionMode(eInk, reducedMotion) {
                NoveliaTheme("light") {
                    Column(Modifier.width(320.dp)) {
                        ShelfControlsPanel("网络小说 · 默认收藏夹 · 更新时间", expanded, { expanded = !expanded }, "motion") {
                            Box(Modifier.fillMaxWidth().height(160.dp)) { Text("筛选条件") }
                        }
                        ShelfBatchHeader("20 本", false, {}, "motion")
                    }
                }
            }
        }
        fun height() = compose.onNodeWithTag("motion-controls").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val collapsedHeight = height()
        val toggleBounds = arrowBounds("motion")
        compose.onNodeWithTag("motion-controls-expand").performClick()
        val fullHeight = height()
        assertEquals(collapsedHeight + 160.dp, fullHeight)
        compose.onNodeWithTag("motion-controls-collapse").performClick()
        assertEquals(collapsedHeight, height())
        compose.mainClock.autoAdvance = false
        fun assertTransitionHeight() {
            val current = height()
            assertTrue("展开收起应经过中间高度：$collapsedHeight < $current < $fullHeight", current > collapsedHeight && current < fullHeight)
        }

        compose.runOnIdle { expanded = true }
        compose.mainClock.advanceTimeBy(80)
        assertTransitionHeight()
        assertEquals(toggleBounds, arrowBounds("motion"))
        compose.runOnIdle { reducedMotion = true }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(fullHeight, height())
        compose.runOnIdle { expanded = false }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(collapsedHeight, height())
        compose.runOnIdle { expanded = true }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(fullHeight, height())
        compose.runOnIdle { expanded = false }
        compose.mainClock.advanceTimeByFrame()

        compose.runOnIdle { reducedMotion = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { expanded = true }
        compose.mainClock.advanceTimeBy(80)
        assertTransitionHeight()
        compose.runOnIdle { eInk = true }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(fullHeight, height())
        compose.runOnIdle { expanded = false }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(collapsedHeight, height())
        compose.runOnIdle { expanded = true }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(fullHeight, height())

        compose.runOnIdle { eInk = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { expanded = false }
        compose.mainClock.advanceTimeBy(80)
        assertTransitionHeight()
        assertEquals(toggleBounds, arrowBounds("motion"))
        assertToggleAboveBatch("motion")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(collapsedHeight, height())
    }

    private fun arrowBounds(prefix: String) =
        compose.onNodeWithTag("$prefix-controls-arrow", useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun assertToggleAboveBatch(prefix: String) {
        val batch = compose.onNodeWithTag("$prefix-batch-header").getUnclippedBoundsInRoot()
        assertTrue("筛选开关应留在批量整理行上方", arrowBounds(prefix).bottom <= batch.top)
    }

    private fun withShelf(eInk: Boolean = false, block: (StateRestorationTester) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val before = app.store.state.value
        try {
            app.store.update { it.copy(books = List(60) { index -> SavedBook(
                BookCard(BookRef("local", "collapse-$index"), "森林来信 ${index.toString().padStart(2, '0')}", authors = listOf("山川遥")), addedAt = 1L)
            }, positions = emptyMap()) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                AppInteractionMode(eInk, true) {
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

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("shelf-controls-preview").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

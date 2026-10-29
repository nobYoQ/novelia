package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.model.*
import cc.novelia.app.data.network.CloudWebFilter
import cc.novelia.app.ui.navigation.LocalRootSelectionMode
import cc.novelia.app.ui.navigation.RootDestinationLayout
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CloudShelfInteractionTest {
    @get:Rule val compose = createComposeRule()
    private var managing by mutableStateOf(false)
    private var selected by mutableStateOf(emptyMap<String, BookCard>())
    private var page by mutableIntStateOf(0)
    private var busy by mutableStateOf(false)
    private var kind by mutableIntStateOf(0)
    private var folder by mutableStateOf("all")
    private var sort by mutableStateOf("update")
    private var query by mutableStateOf("")
    private var filter by mutableStateOf(CloudWebFilter())
    private var expanded by mutableStateOf(false)
    private var grid by mutableStateOf(false)
    private var local by mutableStateOf(false)
    private var created = false
    private var opened = 0
    private var savedFolder: String? = null
    private val books = List(20) { BookCard(BookRef("wenku", "cloud-preview-$it"), "云端收藏示例 ${it + 1}", authors = listOf("山川遥")) }
    private val folders = listOf(Folder("all", "全部收藏"), Folder("reading", "正在阅读的长名称收藏夹"))

    private fun show(fontScale: Float = 1f, reduced: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                AppInteractionMode(false, reduced) { NoveliaTheme("dark") {
                    Surface(Modifier.fillMaxSize()) { RootDestinationLayout("shelf", {}) {
                        val mode = LocalRootSelectionMode.current
                        SideEffect { mode(managing) }
                        Column(Modifier.fillMaxSize()) {
                            Text("书架", Modifier.padding(20.dp), style = MaterialTheme.typography.headlineLarge)
                            PrimaryTabRow(2) { listOf("我的书架", "本地文件", "云端收藏").forEachIndexed { index, title -> Tab(index == 2, {}, text = { Text(title) }) } }
                            var height by remember { mutableIntStateOf(0) }
                            CompactCloudControls(folders, folders.first { it.id == folder }, { folder = it }, sort, { sort = it }, kind, filter,
                                { nextKind, next -> kind = nextKind; filter = next; managing = false; selected = emptyMap() },
                                query, { query = it }, { filter = filter.copy(query = query.trim()) }, { query = ""; filter = filter.copy(query = "") },
                                expanded, { expanded = it }, "本页 10 本", 580.dp, true, !managing, Modifier.onSizeChanged { height = it.height },
                                grid = grid, onToggleLayout = { grid = !grid }) { close ->
                                DropdownMenuItem({ Text("新建收藏夹") }, { close(); created = true })
                            }
                            val state = key(page) { rememberLazyListState() }
                            Box(Modifier.weight(1f)) {
                                CloudShelfBooks(books.drop(page * 10).take(10), LibraryState(), "preview", state, page, 2, { page = it },
                                    managing, { managing = it }, selected, { selected = it }, busy, 1, true, height, null,
                                    { opened++ }, { local = true }, { busy = true }, {}, grid = grid)
                            }
                        }
                    } }
                    if(local) CloudFavoriteLocalSheet(listOf("默认收藏", "周末阅读"), selected.size, { local = false }) {
                        savedFolder = it; local = false
                    }
                } }
            }
        }
    }

    @Test fun dropdownsAndCloudFiltersUseDraftsAndKeepFolderSearchAndSort() {
        show(reduced = false)
        compose.onNodeWithText("批量整理").assertDoesNotExist()
        screenshot("cloud-compact-v2")
        compose.onNodeWithTag("cloud-folder-picker").performClick()
        compose.onNodeWithText(folders[1].title).performClick()
        compose.onNodeWithTag("cloud-sort-picker").performClick()
        compose.onNodeWithText("收藏时间").performClick()
        compose.onNodeWithTag("cloud-search-toggle").performClick()
        compose.onNodeWithTag("cloud-search-field").performTextInput("森林")
        compose.onNodeWithTag("cloud-search-field").performImeAction()
        compose.onNodeWithTag("cloud-search-toggle").performClick()
        compose.onNodeWithTag("cloud-clear-query").assertTextContains("搜索：森林")
        compose.onNodeWithTag("cloud-filter-toggle").performClick()
        compose.onNodeWithTag("cloud-source-toggle").assertDoesNotExist()
        compose.onNodeWithTag("cloud-source-syosetu").assertIsDisplayed().performClick().assertIsNotSelected()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithTag("cloud-source-syosetu").assertIsSelected()
        screenshot("cloud-source-chips", "cloud-filter-sheet")
        compose.onNodeWithTag("cloud-source-syosetu").performClick()
        compose.onNodeWithTag("cloud-type-已完结").performScrollTo().performClick()
        screenshot("cloud-filter-v2", "cloud-filter-sheet")
        compose.runOnIdle { assertEquals(0, filter.type) }
        compose.onNodeWithTag("cloud-filter-close").performClick()
        compose.onNodeWithTag("cloud-filter-toggle").performClick()
        compose.onNodeWithTag("cloud-source-syosetu").assertIsSelected()
        compose.onNodeWithTag("cloud-type-已完结").assertIsNotSelected().performScrollTo().performClick()
        providers.keys.forEach { compose.onNodeWithTag("cloud-source-$it").performScrollTo().performClick().assertIsNotSelected() }
        compose.runOnIdle { assertTrue(filter.source.isNotEmpty()) }
        compose.onNodeWithTag("cloud-filter-apply").performClick()
        compose.runOnIdle { assertEquals(2, filter.type); assertEquals("", filter.source); assertEquals("reading", folder); assertEquals("create", sort); assertEquals("森林", filter.query) }
        compose.onNodeWithTag("cloud-clear-type").assertIsDisplayed()
        compose.onNodeWithTag("cloud-filter-toggle").performClick()
        compose.onNodeWithTag("cloud-novel-kind-文库小说").performClick()
        compose.onNodeWithTag("cloud-sources").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, kind) }
        compose.onNodeWithTag("cloud-filter-apply").performClick()
        compose.runOnIdle { assertEquals(1, kind) }
        compose.onNodeWithTag("cloud-search-toggle").assertDoesNotExist()
        compose.onNodeWithTag("cloud-filter-toggle").performClick()
        compose.onNodeWithTag("cloud-filter-reset").performClick()
        compose.onNodeWithTag("cloud-filter-apply").performClick()
        compose.runOnIdle { assertEquals(0, kind); assertEquals(0, filter.type); assertEquals("reading", folder); assertEquals("create", sort) }
        compose.onNodeWithTag("cloud-folder-picker").performClick()
        compose.onNodeWithText("新建收藏夹").performClick()
        compose.runOnIdle { assertTrue(created) }
    }

    @Test fun longPressDragTapAndPageChangesKeepSelectionAndIconActionsWork() {
        show(reduced = false)
        val list = compose.onNodeWithTag("cloud-books")
        compose.onNodeWithContentDescription("取消云端收藏").assertDoesNotExist()
        val first = compose.onNodeWithTag("cloud-book-${books[0].ref.key}")
        first.performTouchInput { longClick() }
        compose.onNodeWithText("已选 1 本").assertIsDisplayed()
        compose.onNodeWithContentDescription("发现", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, opened) }
        compose.onNodeWithTag("cloud-book-${books[1].ref.key}").performClick()
        compose.runOnIdle { assertEquals(2, selected.size) }
        list.performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(2, selected.size) }
        list.performScrollToIndex(10)
        compose.onNodeWithContentDescription("第 2 页").performClick()
        compose.runOnIdle { assertEquals(1, page); assertEquals(2, selected.size) }
        val next = compose.onNodeWithTag("cloud-book-${books[10].ref.key}")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val start = next.fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        val end = compose.onNodeWithTag("cloud-book-${books[12].ref.key}").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput { down(start); moveTo(start + Offset(0f, 1f), delayMillis = 700) }
            compose.mainClock.advanceTimeBy(32)
            list.performTouchInput { moveTo(end, delayMillis = 100); up() }
            compose.mainClock.advanceTimeBy(400)
        } finally { compose.mainClock.autoAdvance = true }
        compose.runOnIdle { assertEquals(5, selected.size); assertTrue(books[0].ref.key in selected) }
        screenshot("cloud-selection-v2")
        compose.onNodeWithText("加入本地").assertIsDisplayed()
        compose.onNodeWithText("清空选择").assertIsDisplayed()
        compose.onNodeWithText("取消收藏").assertIsDisplayed()
        compose.onNodeWithContentDescription("加入本地收藏").performClick()
        compose.onNodeWithText("周末阅读").performClick()
        compose.runOnIdle { assertEquals("周末阅读", savedFolder) }
        compose.onNodeWithTag("cloud-selection-remove").performClick()
        compose.onNodeWithText("正在处理 1 / 5 本…").assertIsDisplayed()
        listOf("cloud-selection-local", "cloud-selection-clear", "cloud-selection-remove", "cloud-selection-done").forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
        compose.runOnIdle { busy = false }
        compose.onNodeWithTag("cloud-selection-clear").performClick()
        compose.onNodeWithTag("cloud-selection-remove").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-selection-done").performClick()
        compose.onNodeWithContentDescription("发现", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun largeTextKeepsCloudFilterChoicesAndIconActionsReachable() {
        show(fontScale = 2f)
        compose.onNodeWithTag("cloud-filter-toggle").performClick()
        compose.onNodeWithTag("cloud-translate-Sakura").performScrollTo().performClick()
        compose.onNodeWithTag("cloud-filter-apply").performClick()
        compose.runOnIdle { assertEquals(2, filter.translate) }
        compose.onNodeWithTag("cloud-book-${books[0].ref.key}").performTouchInput { longClick() }
        listOf("cloud-selection-local", "cloud-selection-clear", "cloud-selection-remove").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun cloudGridUsesTheSameSelectionActionsAndSwitchesBackWithoutLosingTheBook() {
        grid = true
        show(reduced = false)
        compose.onNodeWithTag("cloud-books-grid").performScrollToIndex(2)
        compose.onNodeWithTag("cloud-layout-toggle").performClick()
        compose.onNodeWithTag("cloud-book-${books[4].ref.key}").assertIsDisplayed()
        compose.onNodeWithTag("cloud-layout-toggle").performClick()
        compose.onNodeWithTag("cloud-book-${books[4].ref.key}").assertIsDisplayed()
        compose.onNodeWithTag("cloud-books-grid").performScrollToIndex(0)
        screenshot("cloud-grid-live")
        compose.onNodeWithTag("cloud-book-${books[1].ref.key}").performTouchInput { longClick() }
        compose.onNodeWithText("已选 1 本").assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf(books[1].ref.key), selected.keys); assertEquals(0, opened) }
        compose.onNodeWithTag("cloud-book-${books[0].ref.key}").performClick()
        compose.onNodeWithText("已选 2 本").assertIsDisplayed()
        compose.onNodeWithTag("cloud-selection-local").performClick()
        compose.onNodeWithText("周末阅读").performClick()
        compose.runOnIdle { assertEquals("周末阅读", savedFolder) }
        compose.onNodeWithTag("cloud-selection-done").performClick()
        compose.onNodeWithTag("cloud-books-grid").performScrollToIndex(5)
        compose.onNodeWithContentDescription("第 2 页").performClick()
        compose.onNodeWithTag("cloud-book-${books[10].ref.key}").assertIsDisplayed()
    }

    private fun screenshot(name: String, tag: String? = null) {
        val image = (if(tag == null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir("screenshots"), "$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}

package cc.novelia.app.ui.shelf

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.library.ShelfBookType
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.ui.components.BookRow
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ShelfFilterLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localShelfAndFilesShareCompactControlsAndKeepFilterActions() {
        var files by mutableStateOf(false)
        var type by mutableStateOf(ShelfBookType.All)
        var folder by mutableStateOf("全部")
        var sort by mutableIntStateOf(0)
        var status by mutableStateOf("全部")
        var query by mutableStateOf("")
        var expanded by mutableStateOf(false)
        var controlsExpanded by mutableStateOf(true)
        var scale by mutableFloatStateOf(1f)
        var created = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                AppInteractionMode(false, true) {
                    NoveliaTheme("dark") {
                        Surface(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("shelf-controls-preview")) {
                            Column {
                                Text("书架", Modifier.padding(20.dp), style = MaterialTheme.typography.headlineLarge)
                                PrimaryTabRow(if(files) 1 else 0) {
                                    listOf("我的收藏", "本地文件", "云端收藏").forEachIndexed { index, title ->
                                        Tab((if(files) 1 else 0) == index, { files = index == 1; type = ShelfBookType.All; expanded = false }, text = { Text(title) })
                                    }
                                }
                                LocalShelfFilters(files, type, { type = it }, listOf("默认收藏", "周末阅读"), folder, { folder = it },
                                    sort, { sort = it }, query, { query = it }, status, { status = it }, expanded, { expanded = it }, 300.dp,
                                    { created = true }, {}, {}, controlsExpanded = controlsExpanded,
                                    onToggleControls = { controlsExpanded = !controlsExpanded; if(!controlsExpanded) expanded = false })
                                ShelfBatchHeader("4 本 · 2 分卷", false, {}, "local")
                                BookRow(BookCard(BookRef("wenku", "preview"), "旅人与森林来信", subtitle = "山川遥"), {})
                                BookRow(BookCard(BookRef("syosetu", "preview"), "在星空下开始新的故事", subtitle = "夏木"), {})
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("搜索书名或作者").assertDoesNotExist()
        compose.onNodeWithTag("local-search-toggle").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("local-filter-arrow", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("新建收藏夹").assertDoesNotExist()
        val typeRows = listOf("全部", "网络小说", "文库小说", "本地小说").map {
            compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("窄屏默认字号下四个类型应保持同一排", 1, typeRows.distinct().size)
        capture("ui-refinement-shelf-dark.png")
        compose.onNodeWithText("文库小说").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(ShelfBookType.Wenku, type) }
        compose.onNodeWithTag("local-filter-toggle").performClick()
        compose.onNodeWithContentDescription("收起筛选").assertIsDisplayed()
        compose.onNodeWithText("搜索书名或作者").assertDoesNotExist()
        compose.onNodeWithText("想读").performClick()
        compose.onNodeWithTag("local-search-toggle").performClick()
        compose.onNodeWithText("阅读状态").assertIsDisplayed()
        assertSearchBelowFilters()
        compose.onNodeWithText("搜索书名或作者").performTextInput("夏木")
        compose.onNodeWithText("搜索书名或作者").performImeAction()
        capture("shelf-search-and-filters.png")
        capture("ui-refinement-shelf-filters.png")
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("local-filter-summary").assertTextContains("想读")
        compose.onNodeWithTag("local-search-field").assertIsDisplayed()
        compose.onNodeWithTag("local-controls-collapse").performClick()
        compose.onNodeWithTag("local-search-field").assertIsDisplayed()
        compose.onNodeWithTag("local-controls-expand").assertTextContains("搜索：夏木", substring = true)
        assertSearchBelowFilters()
        capture("shelf-search-only.png")
        compose.onNodeWithTag("local-controls-expand").performClick()
        compose.onNodeWithTag("local-search-toggle").performClick()
        compose.onNodeWithText("搜索书名或作者").assertDoesNotExist()
        compose.onNodeWithTag("local-toolbar").assertIsDisplayed()
        compose.runOnIdle { assertEquals("夏木", query); assertEquals("想读", status) }
        compose.onNodeWithTag("local-folder-picker").performClick()
        compose.onNodeWithText("周末阅读").performClick()
        compose.runOnIdle { assertEquals("周末阅读", folder) }
        compose.onNodeWithTag("local-sort-picker").performClick()
        compose.onNodeWithText("书名排序").performClick()
        compose.runOnIdle { assertEquals(2, sort) }
        compose.onNodeWithTag("local-folder-picker").performClick()
        compose.onNodeWithText("新建收藏夹").performClick()
        compose.runOnIdle { assertTrue(created) }
        compose.onNodeWithText("本地文件").performClick()
        compose.onNodeWithText("文库分卷").performClick().assertIsSelected()
        compose.onNodeWithTag("local-filter-toggle").performClick()
        compose.onNodeWithText("重置筛选").performClick()
        compose.runOnIdle { assertEquals("夏木", query); assertEquals("全部", status); assertEquals("周末阅读", folder); assertEquals(2, sort) }
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("local-search-toggle").performClick()
        compose.onNodeWithTag("local-search-clear").performClick()
        compose.runOnIdle { assertEquals("", query) }
        compose.onNodeWithTag("local-search-toggle").performClick()
        capture("ui-refinement-local-files.png")
        compose.runOnIdle { scale = 2f }
        val toolbar = compose.onNodeWithTag("local-toolbar").fetchSemanticsNode().boundsInRoot
        listOf("local-folder-picker", "local-sort-picker", "local-filter-toggle").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= toolbar.left && bounds.right <= toolbar.right)
        }
        val search = compose.onNodeWithTag("local-search-toggle").assertIsDisplayed().getUnclippedBoundsInRoot()
        val arrow = compose.onNodeWithTag("local-controls-arrow", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("搜索入口应位于收起按钮左侧", search.right <= arrow.left)
        compose.onNodeWithTag("local-filter-arrow", useUnmergedTree = true).assertIsDisplayed()
        capture("ui-refinement-local-large-font.png")
    }

    private fun assertSearchBelowFilters() {
        val filters = compose.onNodeWithTag("local-controls").getUnclippedBoundsInRoot()
        val search = compose.onNodeWithTag("local-search-field").getUnclippedBoundsInRoot()
        val batch = compose.onNodeWithTag("local-batch-header").getUnclippedBoundsInRoot()
        assertTrue("搜索框应位于筛选区下方", search.top >= filters.bottom)
        assertTrue("搜索框应位于批量整理上方", search.bottom <= batch.top)
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("shelf-controls-preview").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

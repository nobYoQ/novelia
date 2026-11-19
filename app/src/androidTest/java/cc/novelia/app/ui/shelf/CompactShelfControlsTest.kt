package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.CharacterCountFilter
import cc.novelia.app.data.library.ShelfBookType
import cc.novelia.app.data.model.DEFAULT_FOLDER_ID
import cc.novelia.app.data.model.Folder
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompactShelfControlsTest {
    @get:Rule val compose = createComposeRule()
    private var type by mutableStateOf(ShelfBookType.All)
    private var status by mutableStateOf("全部")
    private var folder by mutableStateOf("全部")
    private var sort by mutableIntStateOf(0)
    private var query by mutableStateOf("")
    private var characters by mutableStateOf(CharacterCountFilter())
    private var expanded by mutableStateOf(false)
    private var created = false

    private fun show(files: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                AppInteractionMode(false, true) { NoveliaTheme("dark") {
                    Surface(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("controls-root")) {
                        Column {
                            LocalShelfFilters(files, type, { type = it }, listOf(Folder(DEFAULT_FOLDER_ID, "默认收藏"), Folder("weekend", "周末阅读")), folder, { folder = it },
                                sort, { sort = it }, query, { query = it }, status, { status = it }, expanded, { expanded = it },
                                580.dp, { created = true }, {}, {}, characters, { characters = it }, countLabel = "3 本 · 4 分卷")
                            Text("书籍区域", Modifier.testTag("book-content"))
                        }
                    }
                } }
            }
        }
    }

    @Test fun foldersAndSortingStayDropdownsAndFiltersAreDraftsUntilApplied() {
        show()
        compose.onNodeWithText("批量整理").assertDoesNotExist()
        compose.onNodeWithTag("local-novel-kind").assertDoesNotExist()
        val controls = compose.onNodeWithTag("local-controls").getUnclippedBoundsInRoot()
        assertTrue(controls.bottom - controls.top <= 104.dp)
        compose.onNodeWithTag("local-folder-picker").performClick()
        compose.onNodeWithTag("local-folder-menu").assertExists()
        compose.onNodeWithTag("local-filter-sheet").assertDoesNotExist()
        compose.onNodeWithText("周末阅读").performClick()
        compose.runOnIdle { assertEquals("周末阅读", folder) }
        compose.onNodeWithTag("local-folder-picker").performClick()
        compose.onNodeWithText("新建收藏夹").performClick()
        compose.runOnIdle { assertTrue(created) }
        compose.onNodeWithTag("local-sort-picker").performClick()
        compose.onNodeWithTag("local-sort-menu").assertExists()
        compose.onNodeWithText("书名排序").performClick()
        compose.onNodeWithTag("local-filter-toggle").performClick()
        compose.onNodeWithText("文库小说").performClick()
        compose.onNodeWithTag("local-reading-status-想读").performClick()
        compose.runOnIdle { assertEquals(ShelfBookType.All, type); assertEquals("全部", status) }
        compose.onNodeWithText("关闭面板").performClick()
        compose.onNodeWithTag("local-filter-summary").assertDoesNotExist()
        compose.onNodeWithTag("local-filter-toggle").performClick()
        compose.onNodeWithText("文库小说").performClick()
        compose.onNodeWithTag("local-reading-status-想读").performClick()
        compose.onNodeWithTag("local-filter-apply").performClick()
        compose.runOnIdle { assertEquals(ShelfBookType.Wenku, type); assertEquals("想读", status); assertEquals(2, sort) }
        compose.onNodeWithTag("local-clear-type").assertTextContains("文库小说")
        compose.onNodeWithTag("local-search-toggle").performClick()
        compose.onNodeWithTag("local-search-field").performTextInput("夏木")
        compose.onNodeWithTag("local-search-toggle").performClick()
        compose.onNodeWithTag("local-clear-query").assertTextContains("搜索：夏木")
        compose.onNodeWithTag("local-filter-toggle").performClick()
        compose.onNodeWithTag("local-filter-reset").performClick()
        compose.runOnIdle { assertEquals("想读", status) }
        compose.onNodeWithTag("local-filter-apply").performClick()
        compose.runOnIdle { assertEquals(ShelfBookType.All, type); assertEquals("全部", status); assertEquals("周末阅读", folder); assertEquals(2, sort); assertEquals("夏木", query) }
        compose.onNodeWithTag("local-clear-query").performClick()
        compose.onNodeWithTag("local-filter-summary").assertDoesNotExist()
    }

    @Test fun customCharacterRangeIsAppliedByTheMainButtonAndRejectsInvalidRanges() {
        show()
        compose.onNodeWithTag("local-filter-toggle").performClick()
        compose.onNodeWithText("网络小说").performClick()
        compose.onNodeWithTag("characters-custom").performScrollTo().performClick()
        compose.onNodeWithTag("characters-minimum").performScrollTo().performTextReplacement("100")
        compose.onNodeWithTag("characters-maximum").performScrollTo().performTextReplacement("50")
        compose.onNodeWithTag("local-filter-apply").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(characters.active) }
        compose.onNodeWithTag("characters-maximum").performTextReplacement("200")
        compose.onNodeWithTag("local-filter-apply").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ShelfBookType.Web, type); assertEquals(100L, characters.minimum); assertEquals(200L, characters.maximum) }
        compose.onNodeWithTag("local-clear-characters").assertIsDisplayed().performClick()
        compose.runOnIdle { assertFalse(characters.active) }
    }

    @Test fun largeTextAndLocalFilesKeepAllDropdownAndFilterActionsReachable() {
        show(files = true, fontScale = 2f)
        val root = compose.onNodeWithTag("controls-root").getUnclippedBoundsInRoot()
        listOf("local-folder-picker", "local-search-toggle", "local-filter-toggle", "local-sort-picker").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= root.left && bounds.right <= root.right)
        }
        compose.onNodeWithTag("local-filter-toggle").performClick()
        compose.onNodeWithText("文库分卷").performScrollTo().performClick()
        compose.onNodeWithText("网络小说").assertDoesNotExist()
        compose.onNodeWithTag("character-filter-fields").assertDoesNotExist()
        compose.onNodeWithTag("local-filter-apply").performClick()
        compose.runOnIdle { assertEquals(ShelfBookType.Wenku, type) }
    }
}

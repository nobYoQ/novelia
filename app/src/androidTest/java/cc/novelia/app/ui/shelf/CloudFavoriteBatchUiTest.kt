package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudFavoriteBatchUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedAndFullImportsBothOfferCloudFolderRouting() {
        var stage by mutableIntStateOf(0)
        val grouped = mutableListOf<String>()
        compose.setContent {
            NoveliaTheme("dark") {
                when(stage) {
                    0 -> CloudFavoriteLocalSheet(listOf("默认收藏"), 3, {}, onCloudFolders = {
                        grouped += "selected"; stage++
                    }) { fail("按云端归类不应走单一收藏夹") }
                    1 -> CloudFavoriteImportSheet(listOf("默认收藏"), false, {}, onCloudFolders = {
                        grouped += "web"; stage++
                    }) { fail("按云端归类不应走单一收藏夹") }
                    2 -> CloudFavoriteImportSheet(listOf("默认收藏"), true, {}, onCloudFolders = {
                        grouped += "wenku"; stage++
                    }) { fail("按云端归类不应走单一收藏夹") }
                }
            }
        }
        compose.onNodeWithText("加入本地收藏 · 3 本").assertIsDisplayed()
        compose.onNodeWithText("按云端收藏夹导入").assertIsDisplayed().performClick()
        compose.onNodeWithText("导入全部网络小说").assertIsDisplayed()
        compose.onNodeWithText("按云端收藏夹导入").assertIsDisplayed().performClick()
        compose.onNodeWithText("导入全部文库小说").assertIsDisplayed()
        compose.onNodeWithText("按云端收藏夹导入").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("selected", "web", "wenku"), grouped) }
    }

    @Test fun localFolderChoiceAndBusyBatchActionsStayReachableAtNarrowWidths() {
        var managing by mutableStateOf(true)
        var selected by mutableIntStateOf(0)
        var busy by mutableStateOf(false)
        var local by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        var savedFolder: String? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                NoveliaTheme("dark") {
                    Surface(Modifier.requiredWidth(320.dp)) {
                        Column {
                            ShelfSelectionHeader(selected, !busy, 48.dp, { selected = if(selected == 2) 0 else 2 },
                                { managing = false }, "cloud", if(selected == 2) "取消本页全选" else "全选本页")
                            if(managing) CloudSelectionActions(selected, busy, 1, { local = true }, { busy = true }, { selected = 0 }, onImportAll = {})
                        }
                    }
                    if(local) CloudFavoriteLocalSheet(listOf("默认收藏", "周末阅读"), selected, { local = false }) {
                        savedFolder = it; local = false
                    }
                }
            }
        }
        compose.onNodeWithText("批量整理").assertDoesNotExist()
        compose.onNodeWithContentDescription("加入本地收藏").assertIsNotEnabled()
        compose.onNodeWithContentDescription("取消云端收藏").assertIsNotEnabled()
        compose.onNodeWithText("全选本页").performClick()
        compose.onNodeWithText("已选 2 本").assertIsDisplayed()
        compose.onNodeWithContentDescription("加入本地收藏").performClick()
        compose.onNodeWithText("周末阅读").performClick()
        compose.runOnIdle { assertEquals("周末阅读", savedFolder); scale = 2f }
        compose.onNodeWithContentDescription("加入本地收藏").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("取消云端收藏").assertIsDisplayed().performClick()
        compose.onNodeWithText("正在处理 1 / 2 本…").assertIsDisplayed()
        listOf("cloud-selection-done", "cloud-select-all", "cloud-selection-clear", "cloud-selection-local", "cloud-selection-remove", "cloud-selection-more").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
    }

    @Test fun importAllMenuSeparatesWebAndWenkuAndWorksAfterClearingTheSelection() {
        var importKind by mutableStateOf<Boolean?>(null)
        var busy by mutableStateOf(false)
        val imports = mutableListOf<Pair<Boolean, String>>()
        compose.setContent {
            NoveliaTheme("dark") {
                Surface(Modifier.requiredWidth(320.dp)) {
                    Column {
                        CloudSelectionActions(0, busy, 0, { fail("全部导入不应触发勾选导入") },
                            { fail("全部导入不应修改云端收藏") }, {}, onImportAll = { importKind = it },
                            progressLabel = "正在读取文库小说 · 第 2 / 3 页，已读取 40 本…")
                    }
                }
                importKind?.let { wenku ->
                    CloudFavoriteImportSheet(listOf("默认收藏", "云端导入"), wenku, { importKind = null }) {
                        imports += wenku to it
                        importKind = null
                        if(wenku) busy = true
                    }
                }
            }
        }
        compose.onNodeWithTag("cloud-selection-local").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-selection-more").assertIsEnabled().performClick()
        compose.onNodeWithText("导入全部网络小说").assertIsDisplayed().performClick()
        compose.onNodeWithText("导入全部网络小说").assertIsDisplayed()
        compose.onNodeWithText("云端导入").performClick()
        compose.runOnIdle { assertEquals(listOf(false to "云端导入"), imports) }

        compose.onNodeWithTag("cloud-selection-more").performClick()
        compose.onNodeWithText("导入全部文库小说").assertIsDisplayed().performClick()
        compose.onNodeWithText("导入全部文库小说").assertIsDisplayed()
        compose.onNodeWithText("默认收藏").performClick()
        compose.runOnIdle { assertEquals(listOf(false to "云端导入", true to "默认收藏"), imports) }
        compose.onNodeWithText("正在读取文库小说 · 第 2 / 3 页，已读取 40 本…").assertIsDisplayed()
        listOf("cloud-selection-local", "cloud-selection-clear", "cloud-selection-remove", "cloud-selection-more").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
    }
}

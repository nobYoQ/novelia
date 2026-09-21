package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.catalog.*
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeywordLibraryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullLibraryFindsTagsBeyondThePreviewAndReturnsConditionsToTheAssistant() {
        val entries = (0..40).map { KeywordEntry("tag-$it", "中文标签 $it") }
        var expanded by mutableStateOf(true)
        var query = "原有关键词"
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            SearchAssistantPanel(query, entries, expanded, { expanded = it }, { query = it }, { _, _ -> }, {})
        } } }
        compose.onNodeWithTag("assistant-open-library").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithTag("library-tag-tag-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("keyword-library-search").performTextInput("中文标签 40")
        compose.waitUntil { compose.onAllNodesWithTag("library-tag-tag-40").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-tag-tag-40").performClick()
        compose.onNodeWithTag("keyword-include").performClick()
        compose.onNodeWithText("已包含").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("assistant-preview").performScrollTo().assertTextEquals("tag-40$")
        compose.onNodeWithTag("assistant-append").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("原有关键词 tag-40$", query); assertFalse(expanded) }
    }

    @Test fun categoriesCanBeCreatedRenamedAssignedAndDeletedWithoutDeletingTags() {
        var library by mutableStateOf(KeywordLibrary.defaults())
        val actions = KeywordLibraryActions(
            { library = library.createCategory(it) },
            { old, name -> library = library.renameCategory(old, name) },
            { library = library.deleteCategory(it) },
            { original, translation, category -> library = library.editEntry(original, translation, category) })
        compose.setContent { NoveliaTheme("dark") { AppInteractionMode(false, true) { Surface(Modifier.fillMaxSize()) {
            KeywordLibraryContent(library.entries, library.categories, {}, { _, _ -> }, actions)
        } } } }
        compose.onNodeWithText("管理分类").performClick()
        compose.onNodeWithText("新建分类").performClick()
        compose.onNodeWithTag("keyword-category-name").performTextInput("我的收藏")
        compose.onNodeWithText("创建分类").performClick()
        compose.onNodeWithContentDescription("重命名分类 我的收藏").performClick()
        compose.onNodeWithTag("keyword-category-name").performTextReplacement("人物收藏")
        compose.onNodeWithText("保存名称").performClick()
        compose.onNodeWithText("关闭面板").performClick()
        compose.onNodeWithTag("keyword-library-search").performTextInput("病娇")
        compose.waitUntil { compose.onAllNodesWithTag("library-tag-ヤンデレ").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-tag-ヤンデレ").performClick()
        compose.onNodeWithText("分类：人物").performClick()
        compose.onNodeWithText("人物收藏").performClick()
        compose.onNodeWithText("保存标签").performClick()
        compose.runOnIdle { assertEquals("人物收藏", library.entries.single { it.original == "ヤンデレ" }.category) }
        compose.onNodeWithTag("keyword-library-search").performTextClearance()
        compose.waitUntil { compose.onAllNodesWithText("20 个标签 · 标签库共 20 个").fetchSemanticsNodes().isNotEmpty() }
        capture("keyword-library")
        compose.onNodeWithText("管理分类").performClick()
        capture("keyword-categories")
        compose.onNodeWithContentDescription("删除分类 人物收藏").performClick()
        compose.onNodeWithText("删除分类").performClick()
        compose.runOnIdle {
            assertFalse("人物收藏" in library.categories)
            assertEquals("其他", library.entries.single { it.original == "ヤンデレ" }.category)
            assertEquals(20, library.entries.size)
        }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir("ux-screenshots"), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

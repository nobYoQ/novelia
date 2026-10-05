package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

    @Test fun categoryBatchIncludesEveryMemberThenAllowsIndividualRemovalAndExclusionBeforeApplying() {
        val members = (0..15).map { KeywordEntry("成员$it", "译名%02d".format(it), "自建分类") }
        val entries = members + KeywordEntry("有 空格", category = "自建分类") + KeywordEntry("其他标签")
        var query = "手工条件"
        var expanded by mutableStateOf(true)
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            SearchAssistantPanel(query, entries, expanded, { expanded = it }, { query = it }, { _, _ -> }, {},
                categoryNames = listOf("其他", "自建分类"))
        } } }
        compose.onNodeWithText("同类标签一起筛选").assertDoesNotExist()
        compose.onNodeWithTag("assistant-open-library").performScrollTo().performClick()
        compose.onNode(hasTestTag("keyword-category-自建分类") and hasAnyAncestor(hasTestTag("keyword-library-categories"))).performClick()
        compose.onNodeWithTag("keyword-library-search").performTextInput("译名00")
        compose.waitUntil { compose.onAllNodesWithTag("library-tag-成员0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("keyword-category-include").assertTextEquals("包含整类（16）").performClick()
        compose.onNodeWithText("包含 16 · 排除 0").assertIsDisplayed()
        compose.onNodeWithTag("keyword-category-include").performClick()
        compose.onNodeWithText("包含 16 · 排除 0").assertIsDisplayed()
        compose.onNodeWithTag("keyword-category-exclude").performClick()
        compose.onNodeWithText("包含 0 · 排除 16").assertIsDisplayed()
        compose.onNodeWithTag("keyword-category-include").performClick()
        capture("keyword-category-batch")
        compose.runOnIdle { assertEquals("手工条件", query) }
        compose.onNodeWithTag("library-tag-成员0").performClick()
        compose.onNodeWithTag("keyword-remove").performClick()
        compose.onNodeWithTag("library-tag-成员0").assertIsNotSelected()
        compose.onNodeWithTag("keyword-library-search").performTextReplacement("译名01")
        compose.waitUntil { compose.onAllNodesWithTag("library-tag-成员1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-tag-成员1").performClick()
        compose.onNodeWithTag("keyword-exclude").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        val expected = (2..15).joinToString(" ") { "成员$it$" } + " -成员1$"
        compose.onNodeWithTag("assistant-preview").performScrollTo().assertTextEquals(expected)
        capture("keyword-category-adjusted")
        compose.onNodeWithTag("assistant-append").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("手工条件 $expected", query); assertFalse(expanded) }
    }

    @Test fun builtInCategoriesAreSelectableInTheLibraryAndCanBeExcludedAsOrdinaryTags() {
        var expression = ""
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            SearchAssistantPanel("", KeywordCatalog.common, true, {}, { expression = it }, { _, _ -> }, {})
        } } }
        compose.onNodeWithTag("assistant-open-library").performScrollTo().performClick()
        for((category, count) in listOf("BL／男性恋爱" to 6, "GL／百合" to 7, "TS／性转" to 9)) {
            compose.onNode(hasTestTag("keyword-category-$category") and hasAnyAncestor(hasTestTag("keyword-library-categories")))
                .performScrollTo().performClick()
            compose.onNodeWithTag("keyword-category-exclude").assertTextEquals("排除整类（$count）")
        }
        compose.onNodeWithTag("keyword-category-exclude").performClick()
        compose.onNodeWithText("包含 0 · 排除 9").assertIsDisplayed()
        capture("keyword-ts-category")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("assistant-append").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(expression.contains("-TS$"))
            assertTrue(expression.contains("-性転換$"))
            assertFalse(expression.contains("女装"))
            assertEquals(9, SearchExpression.tagsIn(expression).size)
        }
    }

    @Test fun automaticCompoundTranslationIsVisibleInLibraryAndEditorWithoutSavingItAsAUserEdit() {
        var saves = 0
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            KeywordLibraryContent(listOf(KeywordEntry("ヤンデレヒロイン")), listOf("其他"), {}, { _, _ -> saves++ })
        } } }
        compose.onNodeWithTag("keyword-library-search").performTextInput("病娇女主角")
        compose.waitUntil { compose.onAllNodesWithTag("library-tag-ヤンデレヒロイン").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-tag-ヤンデレヒロイン").assertTextEquals("ヤンデレヒロイン (病娇女主角)").performClick()
        compose.onNodeWithTag("keyword-translation").assertTextContains("病娇女主角")
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertEquals(0, saves) }
    }

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
        compose.onNodeWithTag("library-tag-tag-40").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已包含"))
        val includedBounds = compose.onNodeWithTag("library-tag-tag-40").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("library-tag-tag-40").performClick()
        compose.onNodeWithTag("keyword-exclude").performClick()
        compose.onNodeWithTag("library-tag-tag-40").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已排除"))
        assertEquals(includedBounds, compose.onNodeWithTag("library-tag-tag-40").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("library-tag-tag-40").performClick()
        compose.onNodeWithTag("keyword-include").performClick()
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
            { original, translation, category -> library = library.editEntry(original, translation, category) },
            { library = library.reorderCategories(it) })
        compose.setContent { NoveliaTheme("dark") { AppInteractionMode(false, true) { Surface(Modifier.fillMaxSize()) {
            KeywordLibraryContent(library.entries, library.categories, {}, { _, _ -> }, actions)
        } } } }
        compose.onNodeWithText("管理分类").performClick()
        compose.onNodeWithText("新建分类").performClick()
        compose.onNodeWithTag("keyword-category-name").performTextInput("我的收藏")
        compose.onNodeWithText("创建分类").performClick()
        compose.onNodeWithTag("keyword-category-list").performScrollToNode(hasContentDescription("重命名分类 我的收藏"))
        compose.onNodeWithContentDescription("重命名分类 我的收藏").performClick()
        compose.onNodeWithTag("keyword-category-name").performTextReplacement("人物收藏")
        compose.onNodeWithText("保存名称").performClick()
        compose.onNodeWithText("关闭面板").performClick()
        compose.onNodeWithTag("keyword-library-search").performTextInput("病娇")
        compose.waitUntil { compose.onAllNodesWithTag("library-tag-ヤンデレ").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-tag-ヤンデレ").performClick()
        compose.onNodeWithText("分类：人物").performClick()
        compose.onNode(hasText("人物收藏") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("保存标签").performClick()
        compose.runOnIdle { assertEquals("人物收藏", library.entries.single { it.original == "ヤンデレ" }.category) }
        compose.onNodeWithTag("keyword-library-search").performTextClearance()
        compose.waitUntil { compose.onAllNodesWithText("${KeywordCatalog.common.size} 个标签").fetchSemanticsNodes().isNotEmpty() }
        capture("keyword-library")
        compose.onNodeWithText("管理分类").performClick()
        capture("keyword-categories")
        compose.onNodeWithTag("keyword-category-list").performScrollToNode(hasContentDescription("删除分类 人物收藏"))
        compose.onNodeWithContentDescription("删除分类 人物收藏").performClick()
        compose.onNodeWithText("删除分类").performClick()
        compose.runOnIdle {
            assertFalse("人物收藏" in library.categories)
            assertEquals("其他", library.entries.single { it.original == "ヤンデレ" }.category)
            assertEquals(KeywordCatalog.common.size, library.entries.size)
        }
    }

    @Test fun chipsWrapAndLargeCatalogsStayLazyWhileCategoriesScrollHorizontally() {
        val entries = listOf(KeywordEntry("勇者", lastUsedAt = 999), KeywordEntry("短标签", lastUsedAt = 998)) + KeywordCatalog.common.filterNot { it.original == "勇者" } + (0 until 1000).map {
            KeywordEntry("标记%04d".format(it), category = "分类${it % 8}")
        }
        var fontScale by mutableFloatStateOf(1f)
        var dark by mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NoveliaTheme(if(dark) "dark" else "light") { AppInteractionMode(false, true) { Surface(Modifier.fillMaxSize()) {
                    KeywordLibraryContent(entries, KeywordCatalog.defaultCategories + (0..7).map { "分类$it" }, {}, { _, _ -> })
                } } }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library-tag-勇者").fetchSemanticsNodes().isNotEmpty() }
        val first = compose.onNodeWithTag("library-tag-勇者").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("library-tag-短标签").assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        assertEquals("短标签应该并排展示", first.top, second.top)
        assertTrue("标签之间需要留白", second.left - first.right >= 10.dp)
        val composedTags = compose.onAllNodes(SemanticsMatcher("tag chip") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("library-tag-") == true }).fetchSemanticsNodes().size
        assertTrue("千条标签只组合可见行", composedTags in 2..99)
        capture("keyword-cloud-dark")
        compose.runOnIdle { dark = false }
        capture("keyword-cloud-light")
        compose.onNodeWithTag("keyword-category-分类7").performScrollTo().performClick().assertIsSelected()
        compose.waitUntil { compose.onAllNodesWithText("125 / ${entries.size} 个标签").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("keyword-library-search").performTextInput("标记0999")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library-tag-标记0999").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-tag-标记0999").assertIsDisplayed()
        compose.runOnIdle { fontScale = 1.8f }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library-tag-标记0999").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-tag-标记0999").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // 弹窗与底层辅助面板各有一个根，截取实际屏幕以包含最上层弹窗。
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir("ux-screenshots"), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

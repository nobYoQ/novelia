package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.catalog.KeywordEntry
import cc.novelia.app.data.catalog.KeywordLibrary
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeywordCategoryReorderTest {
    @get:Rule val compose = createComposeRule()
    private var library by mutableStateOf(KeywordLibrary(listOf(KeywordEntry("标签", category = "甲")), listOf("甲", "乙", "丙", "其他")))
    private var saves = 0

    @Test fun draggingBothWaysCommitsOnceAndReopenedManagementKeepsOrderAndMembers() {
        showLibrary()
        val entries = library.entries
        drag("甲", "丙")
        compose.runOnIdle {
            assertEquals(listOf("乙", "丙", "甲", "其他"), library.categories)
            assertEquals(1, saves)
            assertEquals(entries, library.entries)
        }
        compose.onNodeWithText("关闭面板").performClick()
        compose.onNodeWithText("管理分类").performClick()
        assertTrue(compose.onNodeWithTag("keyword-category-row-乙").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("keyword-category-row-甲").fetchSemanticsNode().boundsInRoot.top)
        drag("甲", "乙")
        compose.runOnIdle { assertEquals(listOf("甲", "乙", "丙", "其他"), library.categories); assertEquals(2, saves) }
        compose.onNodeWithTag("keyword-category-drag-其他").performClick()
        compose.onNodeWithText("上移一位").performClick()
        compose.runOnIdle { assertEquals(listOf("甲", "乙", "其他", "丙"), library.categories) }
    }

    @Test fun cancellingADragRestoresOriginalOrderWithoutSaving() {
        showLibrary()
        val original = library
        val handle = compose.onNodeWithTag("keyword-category-drag-甲")
        val target = compose.onNodeWithTag("keyword-category-row-丙").fetchSemanticsNode().boundsInRoot.center
        val start = handle.fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        try {
            handle.performTouchInput {
                down(center)
                moveBy(Offset(0f, target.y - start.y), delayMillis = 300)
            }
            compose.mainClock.advanceTimeBy(600)
            handle.performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(200)
        } finally { compose.mainClock.autoAdvance = true }
        compose.runOnIdle { assertEquals(original, library); assertEquals(0, saves) }
    }

    @Test fun holdingTheHandleAtTheEdgeScrollsBeyondTheInitialViewport() {
        library = library.copy(categories = listOf("甲") + (1..18).map { "分类$it" } + "其他")
        val original = library
        showLibrary()
        val list = compose.onNodeWithTag("keyword-category-list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithTag("keyword-category-drag-甲").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput {
                down(start)
                repeat(20) { step -> moveTo(Offset(start.x, start.y + (height - 20f - start.y) * (step + 1) / 20), delayMillis = 16) }
            }
            compose.mainClock.advanceTimeBy(3200)
            list.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(400)
        } finally { compose.mainClock.autoAdvance = true }
        compose.runOnIdle {
            assertTrue(library.categories.indexOf("甲") > 6)
            assertEquals(original.categories.toSet(), library.categories.toSet())
            assertEquals(original.entries, library.entries)
            assertEquals(1, saves)
        }
    }

    @Test fun eInkButtonsMoveCategoriesWithoutChangingMembership() {
        showLibrary(eInk = true)
        compose.onNodeWithContentDescription("下移分类 甲").performClick()
        compose.runOnIdle { assertEquals(listOf("乙", "甲", "丙", "其他"), library.categories) }
        compose.onNodeWithContentDescription("上移分类 甲").performClick()
        compose.runOnIdle { assertEquals(listOf("甲", "乙", "丙", "其他"), library.categories); assertEquals("甲", library.entries.single().category) }
    }

    private fun showLibrary(eInk: Boolean = false) {
        val actions = KeywordLibraryActions(
            { library = library.createCategory(it) }, { old, name -> library = library.renameCategory(old, name) },
            { library = library.deleteCategory(it) }, { original, translation, category -> library = library.editEntry(original, translation, category) },
            { library = library.reorderCategories(it); saves++ })
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(eInk, true) { Surface(Modifier.fillMaxSize()) {
            KeywordLibraryContent(library.entries, library.categories, {}, { _, _ -> }, actions)
        } } } }
        compose.onNodeWithText("管理分类").performClick()
    }

    private fun drag(name: String, target: String) {
        val list = compose.onNodeWithTag("keyword-category-list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val from = compose.onNodeWithTag("keyword-category-drag-$name").fetchSemanticsNode().boundsInRoot.center
        val to = compose.onNodeWithTag("keyword-category-row-$target").fetchSemanticsNode().boundsInRoot.center
        val start = from - bounds.topLeft
        // 越过目标行中线并留出触控拖动阈值，保证真正跨过该分类。
        val end = (to.y - bounds.top + if(to.y > from.y) 48f else -48f).coerceIn(20f, bounds.height - 20f)
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput {
                down(start)
                repeat(20) { step -> moveTo(Offset(start.x, start.y + (end - start.y) * (step + 1) / 20), delayMillis = 16) }
            }
            compose.mainClock.advanceTimeBy(800)
            list.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(400)
        } finally { compose.mainClock.autoAdvance = true }
    }
}

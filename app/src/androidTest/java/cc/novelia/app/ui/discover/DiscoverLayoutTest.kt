package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.theme.AppInteractionMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DiscoverLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var query by mutableStateOf("魔法")
    private var submitted by mutableStateOf("魔法")
    private var editing by mutableStateOf(false)
    private var searches = 0

    @Test fun inlineSearchKeepsListPositionAndScrollingPreservesItsDraft() {
        showSearch()
        compose.onNodeWithTag("discover-search-input").assertDoesNotExist()
        val resultsTop = compose.onNodeWithTag("discover-test-results").getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("discover-search-bar").assertTextContains("魔法").performClick()
        assertEquals("编辑搜索不应增加一排或下移结果", resultsTop,
            compose.onNodeWithTag("discover-test-results").getUnclippedBoundsInRoot().top)
        compose.onNodeWithTag("discover-search-input").performTextReplacement("旅行")
        compose.onNodeWithTag("discover-test-results").performTouchInput { swipeUp(durationMillis = 600) }
        compose.onNodeWithTag("discover-search-input").assertDoesNotExist()
        compose.onNodeWithTag("discover-search-bar").assertTextContains("魔法")
        compose.runOnIdle { assertEquals("旅行", query); assertEquals(0, searches) }

        compose.onNodeWithTag("discover-search-bar").performClick()
        compose.onNodeWithTag("discover-search-input").assertTextContains("旅行").performImeAction()
        compose.onNodeWithTag("discover-search-input").assertDoesNotExist()
        compose.onNodeWithTag("discover-search-bar").assertTextContains("旅行")
        compose.runOnIdle { assertEquals(1, searches) }
    }

    @Test fun scrollingTowardsEarlierResultsDoesNotCloseTheEditor() {
        showSearch(initialIndex = 30)
        compose.onNodeWithTag("discover-search-bar").performClick()
        compose.onNodeWithTag("discover-test-results").performTouchInput { swipeDown(durationMillis = 600) }
        compose.onNodeWithTag("discover-search-input").assertIsDisplayed()
        compose.onNodeWithTag("discover-test-results").performTouchInput { swipeUp(durationMillis = 600) }
        compose.onNodeWithTag("discover-search-input").assertDoesNotExist()
    }

    @Test fun eInkPageTurnAlsoEndsEditingAndKeepsTheSubmittedQuery() {
        showSearch(eInk = true)
        compose.onNodeWithTag("discover-search-bar").performClick()
        compose.onNodeWithText("下一屏").performClick()
        compose.onNodeWithTag("discover-search-input").assertDoesNotExist()
        compose.onNodeWithTag("discover-search-bar").assertTextContains("魔法")
    }

    @Test fun tagsExpandAllValuesIndependentlyWithoutOpeningTheBook() {
        val tags = listOf("异世界冒险", "魔法学院", "日常生活", "成长故事", "女主人公", "旅行见闻", "友情羁绊", "最后一个标签")
        val first = BookCard(BookRef("syosetu", "tags-first"), "第一本小说", tags = tags)
        val second = first.copy(ref = BookRef("syosetu", "tags-second"), title = "第二本小说")
        var openedBooks = 0
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.width(360.dp)) {
                    Column {
                        DiscoverBookRow(first, { openedBooks++ })
                        DiscoverBookRow(second, { openedBooks++ })
                    }
                }
            }
        }
        val firstText = compose.onNodeWithTag("discover-tags-text-${first.ref.key}", useUnmergedTree = true)
        val secondText = compose.onNodeWithTag("discover-tags-text-${second.ref.key}", useUnmergedTree = true)
        assertEquals(1, textLayout(firstText).lineCount)
        compose.onNodeWithTag("discover-tags-${first.ref.key}").performClick()
        val allTags = textLayout(firstText)
        assertTrue(allTags.lineCount > 1)
        assertEquals(tags.joinToString(" · "), allTags.layoutInput.text.text)
        assertEquals(allTags.layoutInput.text.length, allTags.getLineEnd(allTags.lineCount - 1))
        assertEquals(1, textLayout(secondText).lineCount)
        compose.onNodeWithTag("discover-tags-${first.ref.key}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "标签已展开"))
            .performClick()
        assertEquals(1, textLayout(firstText).lineCount)
        compose.runOnIdle { assertEquals(0, openedBooks) }
    }

    private fun showSearch(eInk: Boolean = false, initialIndex: Int = 0) {
        compose.setContent {
            AppInteractionMode(eInk = eInk, reducedMotion = eInk) {
                MaterialTheme {
                    val state = rememberLazyListState(initialIndex)
                    DiscoverSearchLayout(query, submitted, editing, { query = it }, { editing = it },
                        { submitted = query.trim(); searches++ }) { onPageTurn ->
                        AppLazyColumn(Modifier.fillMaxSize(), state = state,
                            listModifier = Modifier.testTag("discover-test-results"), onPageTurn = onPageTurn) {
                            items(100) { Text("搜索结果 $it", Modifier.fillMaxWidth().height(72.dp).padding(20.dp)) }
                        }
                    }
                }
            }
        }
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}

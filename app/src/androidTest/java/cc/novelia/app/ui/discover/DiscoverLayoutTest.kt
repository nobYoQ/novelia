package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.model.CloudFolders
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.updates.BookUpdateInfo
import cc.novelia.app.ui.components.book.BookListPresentation
import cc.novelia.app.ui.components.book.LocalBookListPresentation
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.theme.AppInteractionMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DiscoverLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savingABookDoesNotAddShelfMetadataToDiscovery() {
        var web by mutableStateOf(BookCard(BookRef("syosetu", "metadata-web"), "网络小说", total = 103, novelType = "已完结"))
        var wenku by mutableStateOf(BookCard(BookRef("wenku", "metadata-wenku"), "文库小说"))
        var presentation by mutableStateOf(BookListPresentation())
        compose.setContent {
            CompositionLocalProvider(LocalBookListPresentation provides presentation) {
                MaterialTheme {
                    Column(Modifier.width(360.dp)) {
                        DiscoverBookRow(web, {})
                        DiscoverBookRow(wenku, {})
                    }
                }
            }
        }
        compose.onNodeWithText("已完结 · 103 章", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            presentation = BookListPresentation(books = mapOf(
                web.ref.key to SavedBook(web.copy(authors = listOf("仅书架作者"), totalCharacters = 761_000, updateAt = 1_700_000_000)),
                wenku.ref.key to SavedBook(wenku.copy(publishedVolumeCount = 8, updateAt = 1_700_000_000)),
            ), updates = mapOf(web.ref.key to BookUpdateInfo(newChapters = 3)))
        }
        compose.onNodeWithTag("book-favorite-local-${web.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("已完结 · 103 章", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("仅书架作者", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("万字", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("8 卷", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("更新 3 章", useUnmergedTree = true).assertDoesNotExist()
        for(book in listOf(web, wenku)) compose.onNodeWithTag("book-update-date-${book.ref.key}", useUnmergedTree = true).assertDoesNotExist()

        // 列表自身（包括启用筛选后的补查结果）提供的信息仍正常显示。
        compose.runOnIdle {
            web = web.copy(authors = listOf("列表作者"), totalCharacters = 762_000, updateAt = 1_700_000_000)
            wenku = wenku.copy(publishedVolumeCount = 9)
            presentation = BookListPresentation()
        }
        compose.onNodeWithText("列表作者", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("万字", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("9 卷", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("book-update-date-${web.ref.key}", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun recommendationAndSearchLabelsDistinguishFavoriteSourcesWithoutCoveringArt() {
        val web = BookCard(BookRef("syosetu", "favorite-web"), "网络小说")
        val wenku = BookCard(BookRef("wenku", "favorite-wenku"), "文库小说", favored = "reading")
        var presentation by mutableStateOf(BookListPresentation(account = "alice"))
        compose.setContent {
            CompositionLocalProvider(LocalBookListPresentation provides presentation) {
                MaterialTheme {
                    Column(Modifier.width(360.dp)) {
                        DiscoverBookRow(web, {})
                        DiscoverBookRow(wenku, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("book-favorite-${web.ref.key}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("book-favorite-cloud-${wenku.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("book-favorite-local-${wenku.ref.key}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("云端收藏", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { presentation = presentation.copy(
            books = mapOf(web.ref.key to SavedBook(web, "本地追更"), wenku.ref.key to SavedBook(wenku, "本地文库")),
            cloudFolders = CloudFolders(favoredWeb = listOf(Folder("reading", "网络待读")),
                favoredWenku = listOf(Folder("reading", "文库待读"))),
        ) }
        compose.onNodeWithText("本地追更", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("本地文库", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("文库待读", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("网络待读", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("book-favorite-local-${web.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("book-favorite-cloud-${web.ref.key}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("book-favorite-local-${wenku.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("book-favorite-cloud-${wenku.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        for(book in listOf(web, wenku)) {
            val cover = compose.onNodeWithContentDescription("${book.title} 默认封面", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val labels = compose.onNodeWithTag("book-favorite-${book.ref.key}", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("收藏标记应位于封面右侧，不能覆盖封面", labels.left >= cover.right)
        }
        val queued = PendingAction("favorite", "alice", "PUT", "user/favored-wenku/reading/${wenku.ref.id}")
        compose.runOnIdle { presentation = presentation.copy(pending = listOf(queued)) }
        compose.onNodeWithText("文库待读", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("· 待同步", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { presentation = presentation.copy(cloudFolders = null) }
        compose.onNodeWithText("云端收藏待同步", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { presentation = presentation.copy(pending = listOf(queued.copy(method = "DELETE"))) }
        compose.onNodeWithTag("book-favorite-cloud-${wenku.ref.key}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("book-favorite-local-${wenku.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { presentation = presentation.copy(account = null) }
        compose.onNodeWithTag("book-favorite-cloud-${wenku.ref.key}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("book-favorite-local-${web.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("book-favorite-local-${wenku.ref.key}", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { presentation = presentation.copy(books = emptyMap()) }
        compose.onNodeWithTag("book-favorite-${web.ref.key}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("book-favorite-${wenku.ref.key}", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun switchingActionsAnimatesSearchWidthAndStaticModeFinishesImmediately() {
        var wenku by mutableStateOf(false)
        var reduced by mutableStateOf(false)
        compose.setContent {
            AppInteractionMode(eInk = false, reducedMotion = reduced) {
                MaterialTheme {
                    DiscoverSearchLayout("", "", false, {}, {}, {}, actions = {
                        if(wenku) IconButton(onClick = {}) { Text("新建") }
                        IconButton(onClick = {}) { Text("排行") }
                    }) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        fun searchWidth() = compose.onNodeWithTag("discover-search-bar").getUnclippedBoundsInRoot().let { it.right - it.left }
        val fullWidth = searchWidth()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { wenku = true }
        compose.mainClock.advanceTimeBy(80)
        val during = searchWidth()
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeByFrame()
        val narrow = searchWidth()
        assertTrue("搜索框应经过中间宽度", during < fullWidth && during > narrow)
        compose.runOnIdle { wenku = false }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(fullWidth, searchWidth())
        compose.mainClock.autoAdvance = true
    }
    private var query by mutableStateOf("魔法")
    private var submitted by mutableStateOf("魔法")
    private var editing by mutableStateOf(false)
    private var searches = 0

    @Test fun inlineSearchAppendsOnEntryAndAfterSubmissionButRespectsManualSelection() {
        showSearch()
        compose.onNodeWithTag("discover-search-bar").performClick()
        val input = compose.onNodeWithTag("discover-search-input")
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2)))
        input.performTextInput("学院")
        input.assertTextContains("魔法学院")

        input.performTextInputSelection(TextRange(2))
        input.performTextInput("新")
        input.performTextInput("的")
        input.assertTextContains("魔法新的学院")
        input.performTextInputSelection(TextRange(2, 4))
        input.performTextInput("旧")
        input.assertTextContains("魔法旧学院").performImeAction()

        compose.onNodeWithTag("discover-search-bar").performClick()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(5)))
        input.performTextInput("冒险")
        input.assertTextContains("魔法旧学院冒险")
        compose.runOnIdle {
            assertEquals("魔法旧学院冒险", query)
            assertEquals("魔法旧学院", submitted)
            assertEquals(1, searches)
        }
    }

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
        compose.onNodeWithTag("discover-search-input").performTextInput("冒险")
        compose.onNodeWithTag("discover-search-input").assertTextContains("旅行冒险").performImeAction()
        compose.onNodeWithTag("discover-search-input").assertDoesNotExist()
        compose.onNodeWithTag("discover-search-bar").assertTextContains("旅行冒险")
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
        compose.onNodeWithTag("discover-search-bar").performClick()
        compose.onNodeWithTag("discover-search-input").performTextInput("学院")
        compose.onNodeWithTag("discover-search-input").assertTextContains("魔法学院")
        compose.runOnIdle { assertEquals("魔法学院", query); assertEquals(0, searches) }
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

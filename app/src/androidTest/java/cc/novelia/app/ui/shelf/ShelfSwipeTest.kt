package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ShelfSwipeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun swipesAndTabClicksStayInSyncWithoutWrappingOrChangingTabsOnVerticalScroll() {
        compose.setContent {
            NoveliaTheme("light") {
                var selected by rememberSaveable { mutableIntStateOf(0) }
                ShelfPager(selected, { selected = it }, true, Modifier.size(360.dp, 480.dp),
                    controls = { Text("$selected", Modifier.testTag("selected-section")) }) { page ->
                    LazyColumn(Modifier.fillMaxSize().testTag("section-list-$page")) {
                        items(40) { Text("分区 $page 书目 $it", Modifier.fillMaxWidth().height(60.dp)) }
                    }
                }
            }
        }
        val pager = compose.onNodeWithTag("shelf-pager")
        pager.performTouchInput { swipeRight() }
        compose.onNodeWithTag("shelf-tab-0").assertIsSelected()
        pager.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("shelf-tab-1").assertIsSelected()
        compose.onNodeWithTag("section-list-1").assertIsDisplayed()
        compose.onNodeWithTag("selected-section").assertTextEquals("1")
        pager.performTouchInput { swipeUp() }
        compose.onNodeWithTag("shelf-tab-1").assertIsSelected()
        pager.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("shelf-tab-2").assertIsSelected()
        pager.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("selected-section").assertTextEquals("2")
        compose.onNodeWithTag("shelf-tab-0").performClick().assertIsSelected()
        compose.onNodeWithTag("section-list-0").assertIsDisplayed()
        compose.onNodeWithTag("selected-section").assertTextEquals("0")
        compose.onNodeWithTag("shelf-tab-2").performClick()
        pager.performTouchInput { swipeRight() }
        compose.onNodeWithTag("shelf-tab-1").assertIsSelected()
    }

    @Test fun pagesRestoreTheirOwnScrollAndFilterState() = pageStateSurvives(false)

    @Test fun comicShelfRestoresTheVisibleBookAfterClassificationFinishes() {
        var classifying by mutableStateOf(false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ShelfPager(0, {}, true, Modifier.size(360.dp, 480.dp), singlePage = true, classifyingBooks = classifying) {
                LazyColumn(Modifier.fillMaxSize().testTag("comic-list")) {
                    items(40, key = { it }) { Text("漫画 $it", Modifier.fillMaxWidth().height(80.dp)) }
                }
            }
        }
        compose.onNodeWithTag("comic-list").performScrollToIndex(20)
        compose.onNodeWithText("漫画 20").assertIsDisplayed()
        compose.runOnIdle { classifying = true }
        compose.onNodeWithTag("comic-list").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { classifying = false }
        compose.onNodeWithText("漫画 20").assertIsDisplayed()
    }

    @Test fun staticModeSupportsSwipesClicksAndRestoration() = pageStateSurvives(true)

    private fun pageStateSurvives(static: Boolean) {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppInteractionMode(eInk = static, reducedMotion = static) {
                NoveliaTheme("light") {
                    // 外层故意不保存，验证分页器恢复后也会同步分区选择。
                    var selected by remember { mutableIntStateOf(0) }
                    ShelfPager(selected, { selected = it }, true, Modifier.size(360.dp, 480.dp),
                        controls = { Text("$selected", Modifier.testTag("selected-section")) }) { page ->
                        var query by rememberSaveable { mutableStateOf("") }
                        Column(Modifier.fillMaxSize()) {
                            OutlinedTextField(query, { query = it }, Modifier.testTag("section-query-$page"))
                            LazyColumn(Modifier.weight(1f).testTag("section-list-$page")) {
                                items(40) { Text("分区 $page 书目 $it", Modifier.fillMaxWidth().height(60.dp)) }
                            }
                        }
                    }
                }
            }
        }
        val pager = compose.onNodeWithTag("shelf-pager")
        compose.onNodeWithTag("section-list-0").performScrollToIndex(12)
        pager.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("section-query-1").performTextInput("已选筛选")
        compose.onNodeWithTag("section-list-1").performScrollToIndex(8)
        pager.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("shelf-tab-2").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("shelf-tab-2").assertIsSelected()
        compose.onNodeWithTag("selected-section").assertTextEquals("2")
        pager.performTouchInput { swipeRight() }
        compose.onNodeWithTag("section-query-1").assertTextContains("已选筛选")
        compose.onNodeWithText("分区 1 书目 8").assertIsDisplayed()
        compose.onNodeWithTag("shelf-tab-0").performClick()
        compose.onNodeWithText("分区 0 书目 12").assertIsDisplayed()
        compose.onNodeWithTag("section-query-0").assertTextEquals("")
    }

    @Test fun runningBatchDisablesBothGesturesAndTabClicks() {
        var enabled by mutableStateOf(false)
        compose.setContent {
            AppInteractionMode(eInk = true, reducedMotion = true) {
                NoveliaTheme("light") {
                    var selected by rememberSaveable { mutableIntStateOf(0) }
                    ShelfPager(selected, { selected = it }, enabled, Modifier.size(360.dp, 480.dp)) { page -> Text("分区 $page") }
                }
            }
        }
        compose.onNodeWithTag("shelf-tab-1").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("shelf-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("shelf-tab-0").assertIsSelected()
        compose.runOnIdle { enabled = true }
        compose.onNodeWithTag("shelf-tab-2").assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithTag("shelf-pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("shelf-tab-1").assertIsSelected()
    }

    @Test fun realShelfKeepsSectionsSeparateAndClearsBatchSelectionAfterSwitching() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        assertNull("Use the anonymous dedicated test emulator", app.session.profile.value)
        val before = app.store.state.value
        try {
            val web = BookCard(BookRef("syosetu", "swipe-web"), "网络小说")
            val local = BookCard(BookRef("local", "swipe-local"), "本地小说")
            app.store.update { it.copy(books = listOf(SavedBook(web), SavedBook(local)), positions = emptyMap()) }
            compose.setContent {
                AppInteractionMode(eInk = true, reducedMotion = true) {
                    NoveliaTheme("light") {
                        val nav = rememberNavController()
                        val scope = rememberCoroutineScope()
                        val c = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
                        ShelfScreen(c, onOpenBook = {})
                    }
                }
            }
            compose.onNodeWithTag("shelf-book-${web.ref.key}").assertIsDisplayed()
            compose.onNodeWithTag("shelf-book-${web.ref.key}").performTouchInput { longClick() }
            compose.onNodeWithText("全选").performClick()
            compose.onNodeWithText("已选 2 本").assertIsDisplayed()
            val pager = compose.onNodeWithTag("shelf-pager")
            // 拖出邻页后撤回，预组合不能把本地文件的可选集合套到我的书架。
            pager.performTouchInput {
                down(center)
                moveTo(center.copy(x = center.x - width * .35f), 300)
                moveTo(center, 300)
                up()
            }
            compose.onNodeWithTag("shelf-tab-0").assertIsSelected()
            compose.onNodeWithText("已选 2 本").assertIsDisplayed()
            pager.performTouchInput { swipeLeft() }
            compose.onNodeWithTag("shelf-tab-1").assertIsSelected()
            compose.onNode(hasTestTag("shelf-book-${local.ref.key}") and hasAnyAncestor(hasTestTag("shelf-page-1"))).assertIsDisplayed()
            compose.onNode(hasTestTag("shelf-book-${web.ref.key}") and hasAnyAncestor(hasTestTag("shelf-page-1"))).assertDoesNotExist()
            compose.onNodeWithText("已选 2 本").assertDoesNotExist()
            compose.onNodeWithTag("shelf-book-${local.ref.key}").performTouchInput { longClick() }
            compose.onNodeWithText("全选").performClick()
            compose.onNodeWithText("已选 1 本").assertIsDisplayed()
            pager.performTouchInput { swipeLeft() }
            compose.onNodeWithTag("shelf-tab-2").assertIsSelected()
            compose.onNodeWithText("连接你的云端书架").assertIsDisplayed()
            compose.onNodeWithTag("shelf-tab-0").performClick()
            compose.onNodeWithTag("shelf-book-${web.ref.key}").assertIsDisplayed()
            compose.onNodeWithText("已选 1 本").assertDoesNotExist()
        } finally { app.store.update { before } }
    }
}

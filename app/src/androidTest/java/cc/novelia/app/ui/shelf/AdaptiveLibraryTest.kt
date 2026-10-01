package cc.novelia.app.ui.shelf

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.shelf.AdaptiveLibraryLayout
import cc.novelia.app.ui.shelf.AdaptiveLibraryScreen
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AdaptiveLibraryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resizingAndRecreationKeepSelectionShelfPositionAndEachBookDetail() {
        var width by mutableStateOf(1000.dp)
        var compactDetail = false
        lateinit var shelfScroll: LazyListState
        var systemBack: (() -> Unit)? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppInteractionMode(eInk = true, reducedMotion = true) {
                NoveliaTheme("light") {
                    val dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                    SideEffect { systemBack = dispatcher::onBackPressed }
                    var selected by rememberSaveable { mutableStateOf<String?>(null) }
                    AdaptiveLibraryLayout(selected, { selected = null }, Modifier.requiredWidth(width).height(520.dp),
                        onCompactDetailChanged = { compactDetail = it },
                        shelf = {
                            var query by rememberSaveable { mutableStateOf("") }
                            val scroll = rememberLazyListState()
                            SideEffect { shelfScroll = scroll }
                            Column {
                                OutlinedTextField(query, { query = it }, Modifier.testTag("library-query"))
                                Row {
                                    Button(onClick = { selected = "a" }, Modifier.testTag("select-a")) { Text("作品 A") }
                                    Button(onClick = { selected = "b" }, Modifier.testTag("select-b")) { Text("作品 B") }
                                }
                                LazyColumn(Modifier.weight(1f).testTag("library-position"), state = scroll) {
                                    items((0..100).toList()) { Text("书目 $it", Modifier.fillMaxWidth().height(48.dp)) }
                                }
                            }
                        },
                        detail = { key ->
                            var chapterQuery by rememberSaveable { mutableStateOf("") }
                            Column {
                                Text("详情 $key", Modifier.testTag("selected-book"))
                                OutlinedTextField(chapterQuery, { chapterQuery = it }, Modifier.testTag("detail-query"))
                            }
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("library-dual-pane").assertExists()
        compose.onNodeWithTag("library-query").performTextReplacement("保留书架筛选")
        compose.onNodeWithTag("library-position").performScrollToIndex(37)
        compose.onNodeWithTag("select-a").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail-query").performTextReplacement("A 的目录搜索")
        compose.runOnIdle { assertEquals(37, shelfScroll.firstVisibleItemIndex); assertFalse(compactDetail); width = 390.dp }
        compose.onNodeWithTag("library-detail-only").assertExists()
        compose.onNodeWithTag("library-query").assertDoesNotExist()
        compose.onNodeWithTag("detail-query").assertTextContains("A 的目录搜索")
        compose.runOnIdle { assertTrue(compactDetail) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("selected-book").assertTextEquals("详情 a")
        compose.onNodeWithTag("detail-query").assertTextContains("A 的目录搜索")
        compose.runOnIdle { width = 1000.dp }
        compose.onNodeWithTag("library-query").assertTextContains("保留书架筛选")
        compose.runOnIdle { assertEquals(37, shelfScroll.firstVisibleItemIndex) }
        compose.onNodeWithTag("select-b").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("selected-book").assertTextEquals("详情 b")
        compose.onNodeWithTag("detail-query").assertTextEquals("")
        compose.onNodeWithTag("detail-query").performTextReplacement("B 的目录搜索")
        compose.onNodeWithTag("select-a").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail-query").assertTextContains("A 的目录搜索")
        compose.runOnIdle { width = 390.dp }
        compose.runOnIdle { systemBack!!.invoke() }
        compose.onNodeWithTag("library-shelf-only").assertExists()
        compose.onNodeWithTag("library-query").assertTextContains("保留书架筛选")
        compose.runOnIdle { assertFalse(compactDetail); assertEquals(37, shelfScroll.firstVisibleItemIndex) }
    }

    @Test fun localSelectionOpensSharedDetailAndChapterNavigationReturnsToSamePane() {
        val app = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication)
            .also { runBlocking { it.initialization.await() } }
        val before = app.store.state.value
        val id = "adaptive-library-${System.nanoTime()}"
        val ref = BookRef("local", id)
        try {
            app.store.saveDocument(LocalDocument(id, "双栏阅读测试", "txt", listOf(
                LocalChapter("first", "第一章 林间", listOf("第一章正文")),
                LocalChapter("second", "第二章 湖畔", listOf("第二章正文")),
            )))
            app.store.update { it.copy(books = listOf(SavedBook(BookCard(ref, "双栏阅读测试"))), positions = emptyMap()) }
            compose.setContent {
                AppInteractionMode(eInk = true, reducedMotion = true) {
                    NoveliaTheme("light") {
                        val nav = rememberNavController()
                        val scope = rememberCoroutineScope()
                        val c = remember(app, nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
                        Box(Modifier.requiredWidth(1000.dp).height(620.dp)) {
                            NavHost(nav, "shelf") {
                                composable("shelf") { entry -> AdaptiveLibraryScreen(c, entry.savedStateHandle) }
                                composable("reader/{provider}/{id}/{chapter}") { entry ->
                                    Column {
                                        Text("阅读章节：${entry.arguments?.getString("chapter")}")
                                        Button(onClick = c::back, modifier = Modifier.testTag("return-from-reader")) { Text("返回详情") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("双栏阅读测试").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("目录 2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("library-dual-pane").assertExists()
            compose.onNodeWithText("目录 2").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("搜索章节").performTextInput("湖畔")
            compose.waitUntil(5_000) { compose.onAllNodesWithText("第一章 林间").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("第二章 湖畔").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("阅读章节：second").assertExists()
            compose.onNodeWithTag("return-from-reader").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("library-dual-pane").assertExists()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("搜索章节").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("搜索章节").assertTextContains("湖畔")
            compose.onNodeWithText("第二章 湖畔").assertExists()
            compose.onNodeWithContentDescription("返回").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("选择一本书").assertExists()
            compose.onNodeWithText("双栏阅读测试").assertExists()

            // 移除当前选中的本地书籍时，应立即销毁其缓存详情。
            compose.onNodeWithText("双栏阅读测试").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("目录 2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("管理 双栏阅读测试").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("移出书架").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("选择一本书").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("本地书籍").assertDoesNotExist()
            compose.onNodeWithTag("shelf-book-${ref.key}").assertDoesNotExist()
            compose.runOnIdle { assertEquals("双栏阅读测试", app.store.documentIndex(id).name) }

            // 重新加入保留的文档，再验证删除文件的操作。
            compose.runOnIdle { app.store.saveBook(BookCard(ref, "双栏阅读测试")) }
            compose.onNodeWithText("双栏阅读测试").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("目录 2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("管理 双栏阅读测试").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("删除本地小说").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("删除本地小说").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("选择一本书").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("本地书籍").assertDoesNotExist()
            compose.onNodeWithTag("shelf-book-${ref.key}").assertDoesNotExist()
            compose.runOnIdle { assertTrue(runCatching { app.store.documentIndex(id) }.isFailure) }
        } finally {
            app.store.removeDocument(id)
            app.store.update { before }
        }
    }
}

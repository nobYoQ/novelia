package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.Note
import cc.novelia.app.ui.book.AdaptiveBookDetail
import cc.novelia.app.ui.downloads.DownloadsScreen
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.finishLoginNavigation
import cc.novelia.app.ui.navigation.loginForFavorite
import cc.novelia.app.ui.notes.NotesScreen
import cc.novelia.app.ui.shelf.FavoriteSheet
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LibraryInteractionTest {
    @get:Rule val compose = createComposeRule()
    private fun application(): NoveliaApplication =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication).also { runBlocking { it.initialization.await() } }

    @Test fun deletingNoteOffersUndoAndPreservesItsMetadata() {
        val app = application()
        val before = app.store.state.value
        val note = Note("undo-test", "local/notes-test", "chapter", 0, "为读者保留的摘录", "测试笔记", bookTitle = "森林来信", chapterTitle = "第一章")
        try {
            app.store.update { it.copy(notes = listOf(note)) }
            compose.setContent {
                NoveliaTheme("light") {
                    val snackbar = remember { SnackbarHostState() }
                    val controller = AppController(app, rememberNavController(), rememberCoroutineScope(), snackbar)
                    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding -> Box(Modifier.padding(padding)) { NotesScreen(controller) } }
                }
            }
            compose.onNodeWithText("森林来信").assertIsDisplayed()
            compose.onNodeWithText("第一章 · 第 1 段").assertIsDisplayed()
            compose.onNodeWithText("删除").performScrollTo().performClick()
            compose.onNodeWithText("撤销").performClick()
            compose.runOnIdle { assertEquals(note, app.store.state.value.notes.single()) }
            compose.onNodeWithTag("notes-search").performScrollTo().performTextInput("不存在的内容")
            compose.waitUntil(5_000) { compose.onAllNodesWithText("没有匹配的笔记").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("没有匹配的笔记").assertIsDisplayed()
        } finally { app.store.update { before } }
    }

    @Test fun completedDownloadPromotesReadingAndKeepsFileActionsInTheMenu() {
        val app = application()
        val before = app.store.state.value
        try {
            app.store.update { it.copy(downloads = listOf(DownloadEntry("completed-ui", "已完成的书", "ui.txt", "https://example.invalid/file", status = "已完成"))) }
            compose.setContent { NoveliaTheme("light") { DownloadsScreen(AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() })) } }
            compose.onNodeWithText("开始阅读").assertIsDisplayed()
            compose.onNodeWithText("导出文件").assertDoesNotExist()
            compose.onNodeWithContentDescription("更多下载操作 已完成的书").performClick()
            compose.onNodeWithText("导出文件").assertIsDisplayed()
            compose.onNodeWithText("删除").assertIsDisplayed()
        } finally { app.store.update { before } }
    }

    @Test fun guestCloudFavoriteSurvivesRecreationAndIsConsumedOnlyOnce() {
        val app = application()
        assumeTrue(app.session.profile.value == null)
        val book = BookCard(BookRef("syosetu", "favorite-resume"), "登录后继续收藏的作品")
        lateinit var controller: AppController
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            NoveliaTheme("light") {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val snackbar = remember { SnackbarHostState() }
                controller = remember(app, nav, scope, snackbar) { AppController(app, nav, scope, snackbar) }
                NavHost(nav, startDestination = "book") {
                    composable("book") {
                        var visible by rememberSaveable { mutableStateOf(true) }
                        if(visible) FavoriteSheet(controller, book) { visible = false }
                    }
                    composable("login") { Button(onClick = { finishLoginNavigation(controller) }) { Text("模拟完成登录验证") } }
                }
            }
        }
        compose.onNodeWithText("原站云端").performClick()
        compose.onNodeWithText("登录后继续", useUnmergedTree = true).performClick()
        compose.onNodeWithText("模拟完成登录验证").assertIsDisplayed()
        val previousController = controller
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("模拟完成登录验证").assertIsDisplayed()
        compose.runOnIdle { assertNotSame(previousController, controller); assertNull(controller.afterLogin) }
        compose.onNodeWithText("模拟完成登录验证").performClick()
        compose.runOnIdle {
            assertEquals(book, controller.pendingFavorite)
            assertTrue(controller.pendingFavoriteCloud)
            controller.pendingFavorite = null
        }
        var ordinaryActionCount = 0
        compose.runOnIdle { controller.requireLogin { ordinaryActionCount++ } }
        compose.onNodeWithText("模拟完成登录验证").performClick()
        compose.runOnIdle { assertEquals(1, ordinaryActionCount); assertNull(controller.pendingFavorite) }

        // Cancelling a later favorite login must not leak its intent into an ordinary login.
        compose.runOnIdle { loginForFavorite(controller, book) }
        compose.runOnIdle { controller.back() }
        compose.waitForIdle()
        compose.runOnIdle { controller.requireLogin { ordinaryActionCount++ } }
        compose.onNodeWithText("模拟完成登录验证").performClick()
        compose.runOnIdle { assertEquals(2, ordinaryActionCount); assertNull(controller.pendingFavorite) }
    }

    @Test fun bookDetailsUseIndependentPanesAndKeepStateWhenResized() {
        var width by mutableStateOf(900.dp)
        var selected by mutableIntStateOf(0)
        compose.setContent {
            NoveliaTheme("light") {
                AdaptiveBookDetail(selected, { selected = it }, listOf("简介", "目录", "讨论"), rememberSaveableStateHolder(), Modifier.requiredWidth(width).height(420.dp)) { index ->
                    var text by rememberSaveable { mutableStateOf("内容 $index") }
                    OutlinedTextField(text, { text = it }, modifier = Modifier.testTag("panel-$index"))
                }
            }
        }
        compose.onNodeWithTag("book-detail-dual-pane").assertExists()
        compose.onNodeWithTag("panel-0").assertExists()
        compose.onNodeWithTag("panel-1").assertExists()
        compose.onNodeWithTag("panel-0").performTextReplacement("保留简介位置")
        compose.runOnIdle { width = 390.dp; selected = 1 }
        compose.onNodeWithTag("book-detail-single-pane").assertExists()
        compose.onNodeWithTag("panel-0").assertDoesNotExist()
        compose.runOnIdle { selected = 0 }
        compose.onNodeWithTag("panel-0").assertTextContains("保留简介位置")
        compose.runOnIdle { width = 900.dp }
        compose.onNodeWithTag("panel-0").assertTextContains("保留简介位置")
        compose.onNodeWithTag("panel-1").assertExists()
        compose.onNodeWithTag("panel-1").performTextReplacement("保留目录搜索")
        compose.runOnIdle { selected = 2 }
        compose.onNodeWithTag("panel-2").assertExists()
        compose.runOnIdle { width = 390.dp; selected = 1 }
        compose.onNodeWithTag("panel-1").assertTextContains("保留目录搜索")
        compose.runOnIdle { width = 900.dp }
        compose.onNodeWithTag("panel-0").assertTextContains("保留简介位置")
        compose.onNodeWithTag("panel-1").assertTextContains("保留目录搜索")
    }
}

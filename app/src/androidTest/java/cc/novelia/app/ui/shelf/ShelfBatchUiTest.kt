package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.saveTestScreenshot
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ShelfBatchUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedShelfBooksCanBeRemovedAndUndoneAndFilesHaveSeparateDeleteConfirmation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        assumeTrue(app.session.profile.value == null)
        val previous = app.store.state.value
        val local = BookCard(BookRef("local", "batch-ui-${UUID.randomUUID()}"), "批量操作本地书")
        val web = BookCard(BookRef("syosetu", "batch-ui-${UUID.randomUUID()}"), "保留的网络书")
        try {
            app.store.update { it.copy(books = listOf(SavedBook(local), SavedBook(web)), deleteLocalCopyOnShelfRemoval = false) }
            compose.setContent { AppInteractionMode(false, true) { NoveliaTheme("light") {
                val snackbar = remember { SnackbarHostState() }
                val controller = AppController(app, rememberNavController(), rememberCoroutineScope(), snackbar)
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding -> Box(Modifier.padding(padding)) { ShelfScreen(controller) } }
            } } }
            compose.onNodeWithText("我的书架").assertIsDisplayed()
            compose.onNodeWithTag("local-batch-manage").performClick()
            compose.onNodeWithTag("shelf-book-${local.ref.key}").performClick()
            compose.onNodeWithText("批量移出书架").performClick()
            compose.onNodeWithText("取消").performClick()
            compose.runOnIdle { assertEquals(2, app.store.state.value.books.size) }
            compose.onNodeWithText("批量移出书架").performClick()
            compose.onNodeWithText("移出书架").performClick()
            compose.waitUntil(5_000) { app.store.state.value.books.none { it.book.ref == local.ref } }
            compose.runOnIdle { assertEquals(web.ref, app.store.state.value.books.single().book.ref) }
            compose.onNodeWithText("撤销").performClick()
            compose.waitUntil(5_000) { app.store.state.value.books.size == 2 }
            compose.onNodeWithText("本地文件").performClick()
            compose.onNodeWithTag("local-batch-manage").performClick()
            compose.onNodeWithText("全选").performClick()
            compose.onNodeWithText("批量移出书架").assertDoesNotExist()
            compose.onNodeWithText("批量删除").performClick()
            compose.onNodeWithText("删除本地副本").assertIsDisplayed()
            saveTestScreenshot("problem-shelf-batch-delete.png")
            compose.onNodeWithText("删除本地副本").performClick()
            compose.waitUntil(5_000) { app.store.state.value.books.none { it.book.ref == local.ref } }
            compose.runOnIdle { assertEquals(web.ref, app.store.state.value.books.single().book.ref) }
        } finally { app.store.update { previous }; runBlocking { app.store.flush() } }
    }
}

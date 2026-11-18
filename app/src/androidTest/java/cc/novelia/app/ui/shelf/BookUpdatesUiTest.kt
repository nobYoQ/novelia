package cc.novelia.app.ui.shelf

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.updates.ShelfCheckReport
import cc.novelia.app.data.updates.ShelfCheckStatus
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BookUpdatesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manualResultIsSeparateFromUnreadBooksAndRemainsAfterLeaving() {
        val book = SavedBook(BookCard(BookRef("syosetu", "check"), "已有未读更新的书"), hasUpdates = true)
        var library by mutableStateOf(LibraryState(books = listOf(book)))
        var manual by mutableStateOf<ShelfCheckStatus?>(null)
        var open by mutableStateOf(true)
        var checks = 0
        compose.setContent { NoveliaTheme("light") {
            if(open) BookUpdatesView(library, manual, false, {}, {
                checks++; manual = ShelfCheckStatus("检查已排队，等待网络和系统调度", active = true)
            }, {}, {})
        } }
        val check = compose.onNodeWithContentDescription("检查更新")
        check.performClick()
        compose.onNodeWithText("检查已排队，等待网络和系统调度").assertIsDisplayed()
        check.assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, checks); manual = ShelfCheckStatus("正在检查书架：0 / 1 本", active = true, progress = 0f) }
        compose.onNodeWithText("正在检查书架：0 / 1 本").assertIsDisplayed()
        compose.runOnIdle {
            library = library.copy(drafts = mapOf(ShelfCheckReport.KEY to ShelfCheckReport(200, 0, 0, 1).encode()))
            manual = null
        }
        compose.onNodeWithText("检查完成：未发现更新").assertIsDisplayed()
        compose.onNodeWithText("待查看的更新 · 1 本").assertIsDisplayed()
        check.assertIsEnabled()
        compose.runOnIdle { open = false }
        compose.runOnIdle { open = true }
        compose.onNodeWithText("检查完成：未发现更新").assertIsDisplayed()
        compose.runOnIdle {
            library = library.copy(drafts = mapOf(ShelfCheckReport.KEY to ShelfCheckReport(300, 1, 1, 2).encode()))
            manual = ShelfCheckStatus("旧的手动检查", finishedAt = 200)
        }
        compose.onNodeWithText("发现 1 本有更新，另有 1 本检查失败").assertIsDisplayed()
        compose.runOnIdle { manual = ShelfCheckStatus("手动检查已取消，可重新检查") }
        compose.onNodeWithText("手动检查已取消，可重新检查").assertIsDisplayed()
        compose.onNodeWithText("发现 1 本有更新，另有 1 本检查失败").assertIsDisplayed()
    }
}

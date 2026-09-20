package cc.novelia.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.*
import cc.novelia.app.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookSyncUiTest {
    @get:Rule val compose = createComposeRule()
    private val book = BookCard(BookRef("syosetu", "sync-fixture"), "同步状态测试作品")
    private val action = PendingAction("retry-fixture", "alice", "PUT", "user/favored-web/folder/${book.ref.key}")

    @Test fun retryRunsInsideTheRowWithoutOpeningTheBookAndClearsAfterSuccess() {
        val queue = CloudMutationQueue()
        val response = CompletableDeferred<Unit>()
        var pending by mutableStateOf(listOf(action))
        var opened = 0
        var submitted = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val active by queue.inFlight.collectAsState()
            val status = CloudSyncStatus(failures = mapOf(action.id to "网络或服务暂不可用，将自动重试"))
            NoveliaTheme("light") {
                CompositionLocalProvider(LocalBookSyncPresentation provides BookSyncPresentation(
                    bookSyncStates("alice", pending, status, active.values, automatic = false),
                    retry = { ref ->
                        assertEquals(book.ref, ref)
                        scope.launch {
                            queue.replayEligible("alice", { pending }, { pending = it(pending) }) {
                                submitted++
                                response.await()
                            }
                        }
                    },
                )) { BookRow(book, { opened++ }) }
            }
        }
        compose.onNodeWithText("收藏同步失败", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("网络或服务暂不可用，可手动重试", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("重试同步").performClick()
        compose.onNodeWithText("收藏同步中", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("重试同步").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, opened); assertEquals(1, submitted); response.complete(Unit) }
        compose.waitUntil(5_000) { pending.isEmpty() }
        compose.onNodeWithTag("book-sync-${book.ref.key}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(book.title).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun switchingAccountAndLoggingOutImmediatelyHideThePreviousAccountsFailure() {
        var account by mutableStateOf<String?>("alice")
        var loginRequests = 0
        val pending = listOf(action, action.copy(id = "bob", account = "bob", method = "DELETE"))
        compose.setContent {
            val status = if (account == "alice") CloudSyncStatus(requiresLogin = true) else CloudSyncStatus()
            NoveliaTheme("light") {
                CompositionLocalProvider(LocalBookSyncPresentation provides BookSyncPresentation(
                    bookSyncStates(account, pending, status), login = { loginRequests++ },
                )) { Column { Text("账号隔离"); BookRow(book, {}) } }
            }
        }
        compose.onNodeWithText("收藏等待登录", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("重新登录").performClick()
        compose.runOnIdle { assertEquals(1, loginRequests); account = "bob" }
        compose.onNodeWithText("收藏等待登录", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("取消收藏待同步", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { account = null }
        compose.onNodeWithTag("book-sync-${book.ref.key}", useUnmergedTree = true).assertDoesNotExist()
    }
}

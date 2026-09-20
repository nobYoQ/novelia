package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.theme.LocalReducedMotion
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AsyncContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingIdentityNeverRendersThePreviousResultWithTheNewKey() {
        var identity by mutableStateOf("first")
        val nextResult = CompletableDeferred<String>()
        val rendered = CopyOnWriteArrayList<Pair<String, String>>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AsyncContent(identity, load = { if(identity == "first") "first result" else nextResult.await() }) { result, _ ->
                        SideEffect { rendered += identity to result }
                        Text(result)
                    }
                }
            }
        }
        compose.onNodeWithText("first result").assertIsDisplayed()
        compose.runOnIdle { identity = "second" }
        compose.onNodeWithText("first result").assertDoesNotExist()
        compose.onNodeWithText("正在加载…").assertIsDisplayed()
        compose.runOnIdle { nextResult.complete("second result") }
        compose.onNodeWithText("second result").assertIsDisplayed()
        assertTrue("A new identity must never receive the previous result", rendered.none { (key, value) -> key == "second" && value == "first result" })
    }

    @Test fun refreshKeyKeepsContentAndRememberedChildStateUntilAndAfterCompletion() {
        var revision by mutableIntStateOf(0)
        val refreshedResult = CompletableDeferred<String>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AsyncContent("book", refreshKey = revision, load = { if(revision == 0) "initial result" else refreshedResult.await() }) { result, _ ->
                        var selected by remember { mutableIntStateOf(0) }
                        Column {
                            Text(result)
                            Text("selected: $selected")
                            Button(onClick = { selected++ }) { Text("Select") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("selected: 1").assertIsDisplayed()
        compose.runOnIdle { revision++ }
        compose.onNodeWithText("initial result").assertIsDisplayed()
        compose.onNodeWithText("selected: 1").assertIsDisplayed()
        compose.runOnIdle { refreshedResult.complete("refreshed result") }
        compose.onNodeWithText("refreshed result").assertIsDisplayed()
        compose.onNodeWithText("selected: 1").assertIsDisplayed()
    }

    @Test fun failedRefreshPreservesContentAndOffersAWorkingRetry() {
        var attempts = 0
        val refreshFailure = CompletableDeferred<String>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AsyncContent("book", load = {
                        when(++attempts) {
                            1 -> "saved result"
                            2 -> refreshFailure.await()
                            else -> "recovered result"
                        }
                    }) { result, refresh ->
                        Column {
                            Text(result)
                            Button(onClick = refresh) { Text("Refresh") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("saved result").assertIsDisplayed()
        compose.onNodeWithText("Refresh").performClick()
        compose.onNodeWithText("saved result").assertIsDisplayed()
        compose.runOnIdle { refreshFailure.completeExceptionally(IllegalArgumentException("测试刷新失败")) }
        compose.onNodeWithText("saved result").assertIsDisplayed()
        compose.onNodeWithText("刷新未完成：测试刷新失败").assertIsDisplayed()
        compose.onNodeWithText("暂时无法加载").assertDoesNotExist()
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("recovered result").assertIsDisplayed()
        compose.onNodeWithText("刷新未完成：测试刷新失败").assertDoesNotExist()
    }
}

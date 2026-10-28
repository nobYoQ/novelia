package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.cache.UiResultCache
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.AppInteractionMode
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CachedAsyncContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pageRoundTripAndRecreationRestoreContentAndScrollWithoutRequests() {
        val cache = UiResultCache<String>()
        val requests = AtomicInteger()
        val restoration = StateRestorationTester(compose)
        var changePage: (String) -> Unit = {}
        restoration.setContent {
            var page by rememberSaveable { mutableStateOf("first") }
            changePage = { page = it }
            Scene(cache, page, { requests.incrementAndGet(); page })
        }
        awaitText("first 0")
        compose.onNodeWithTag("feed").performScrollToIndex(20)
        compose.runOnIdle { changePage("second") }
        awaitText("second 0")
        compose.runOnIdle { changePage("first") }
        compose.onNodeWithText("first 20").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("first 20").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, requests.get()) }
    }

    @Test fun expiredSeedStaysVisibleDuringFailureAndRetry() {
        var now = 1_000L
        val cache = UiResultCache<String>(lifetimeMillis = 100, clock = { now })
        cache.put("page", "原内容")
        now += 101
        val pending = CompletableDeferred<String>()
        val requests = AtomicInteger()
        compose.setContent { Scene(cache, "page", {
            if(requests.incrementAndGet() == 1) pending.await() else "已更新"
        }) }
        compose.onNodeWithText("原内容 0").assertIsDisplayed()
        compose.waitUntil { requests.get() == 1 }
        compose.runOnIdle { pending.completeExceptionally(IOException("离线")) }
        compose.onNodeWithText("原内容 0").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        awaitText("已更新 0")
        compose.runOnIdle { assertEquals(2, requests.get()) }
    }

    @Test fun unrelatedMutationDoesNotReloadVisibleBookAndMatchingMutationDoes() {
        val cache = UiResultCache<String>()
        cache.put("a", "A"); cache.put("b", "B")
        val requests = AtomicInteger()
        compose.setContent { Scene(cache, "a", { requests.incrementAndGet(); "新版 A" }) }
        compose.runOnIdle { cache.invalidate { it == "b" } }
        compose.onNodeWithText("A 0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, requests.get()); cache.invalidate { it == "a" } }
        awaitText("新版 A 0")
        compose.runOnIdle { assertEquals(1, requests.get()) }
    }

    @Test fun identityChangeNeverSeedsThePreviousAccountContent() {
        val cache = UiResultCache<String>()
        cache.put("alice", "Alice")
        var identity by mutableStateOf("alice")
        val response = CompletableDeferred<String>()
        compose.setContent { Scene(cache, identity, { response.await() }) }
        compose.onNodeWithText("Alice 0").assertIsDisplayed()
        compose.runOnIdle { identity = "bob" }
        compose.onNodeWithText("Alice 0").assertDoesNotExist()
        compose.runOnIdle { response.complete("Bob") }
        awaitText("Bob 0")
    }

    @Test fun permissionFailureDiscardsTheExpiredSeedInsteadOfShowingProtectedContent() {
        val cache = UiResultCache<String>()
        cache.put("page", "旧权限内容")
        cache.invalidate()
        val response = CompletableDeferred<String>()
        compose.setContent { Scene(cache, "page", { response.await() }) }
        compose.onNodeWithText("旧权限内容 0").assertIsDisplayed()
        compose.runOnIdle { response.completeExceptionally(cc.novelia.app.data.network.ApiException(403, "权限已变化")) }
        awaitText("暂时无法加载")
        compose.onNodeWithText("旧权限内容 0").assertDoesNotExist()
        compose.runOnIdle { assertNull(cache.snapshot("page")) }
    }

    @Test fun nestedCachedRefreshShowsOnlyTheBookProgressAndStillReportsFolderFailures() {
        val folders = UiResultCache<String>().apply { put("folders", "已缓存收藏夹"); invalidate() }
        val books = UiResultCache<String>().apply { put("books", "已缓存书籍"); invalidate() }
        val folderResponse = CompletableDeferred<String>()
        val bookResponse = CompletableDeferred<String>()
        val attempts = AtomicInteger()
        var reducedMotion by mutableStateOf(false)
        compose.setContent {
            AppInteractionMode(eInk = false, reducedMotion = reducedMotion) { MaterialTheme {
                CachedAsyncContent(folders, "folders", load = {
                    if(attempts.incrementAndGet() == 1) folderResponse.await() else "已更新收藏夹"
                }, modifier = Modifier.size(360.dp, 480.dp), showRefreshIndicator = false) { folder, _ ->
                    Column {
                        Text(folder)
                        CachedAsyncContent(books, "books", load = { bookResponse.await() },
                            modifier = Modifier.weight(1f).testTag("books")) { value, _ -> Text(value) }
                    }
                }
            } }
        }
        val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        compose.onNodeWithText("已缓存收藏夹").assertIsDisplayed()
        compose.onNodeWithText("已缓存书籍").assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(1)
        compose.onNode(progress and hasAnyAncestor(hasTestTag("books"))).assertIsDisplayed()
        compose.runOnIdle { reducedMotion = true }
        compose.onAllNodesWithText("正在刷新…").assertCountEquals(1)
        compose.runOnIdle { bookResponse.complete("已更新书籍") }
        awaitText("已更新书籍")
        compose.onAllNodes(progress).assertCountEquals(0)
        compose.onAllNodesWithText("正在刷新…").assertCountEquals(0)
        compose.runOnIdle { folderResponse.completeExceptionally(IllegalArgumentException("收藏夹刷新失败")) }
        compose.onNodeWithText("刷新未完成：收藏夹刷新失败").assertIsDisplayed()
        compose.onNodeWithText("已缓存收藏夹").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        awaitText("已更新收藏夹")
        compose.onNodeWithText("已更新书籍").assertIsDisplayed()
    }

    @Test fun silentRefreshOptionPreservesInitialLoadingAndFailureFeedback() {
        val cache = UiResultCache<String>()
        val response = CompletableDeferred<String>()
        compose.setContent {
            AppInteractionMode(eInk = false, reducedMotion = true) { MaterialTheme {
                CachedAsyncContent(cache, "folders", load = { response.await() }, showRefreshIndicator = false) { value, _ -> Text(value) }
            } }
        }
        compose.onNodeWithText("正在加载…").assertIsDisplayed()
        compose.runOnIdle { response.completeExceptionally(IOException("离线")) }
        awaitText("暂时无法加载")
        compose.onNodeWithText("重试").assertIsDisplayed()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Composable private fun Scene(cache: UiResultCache<String>, identity: String, load: suspend () -> String) {
        AppInteractionMode(eInk = false, reducedMotion = true) {
            MaterialTheme {
                val scroll = rememberCachedListState(cache, identity)
                CachedAsyncContent(cache, identity, load, Modifier.size(360.dp, 480.dp)) { value, _ ->
                    AppLazyColumn(state = scroll, listModifier = Modifier.testTag("feed")) {
                        items(50) { Text("$value $it", Modifier.fillMaxWidth().height(64.dp)) }
                    }
                }
            }
        }
    }
}

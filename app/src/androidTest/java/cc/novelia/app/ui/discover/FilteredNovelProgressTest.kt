package cc.novelia.app.ui.discover

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.catalog.CharacterCountFilter
import cc.novelia.app.data.catalog.NovelLocalFilter
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.navigation.AppController
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FilteredNovelProgressTest {
    @get:Rule val compose = createComposeRule()

    @Test fun favoriteChangesRecomputeAccumulatedResultsAndRemovingFavoritesRestoresBooks() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val favorite = BookCard(BookRef("syosetu", "favorite-filter-a"), "收藏过滤作品", totalCharacters = 123_456)
        val other = favorite.copy(ref = BookRef("syosetu", "favorite-filter-b"), title = "保留作品")
        var library by mutableStateOf(LibraryState(hideLocalFavorites = true))
        compose.setContent {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val controller = remember { AppController(app, nav, scope, SnackbarHostState()) }
            MaterialTheme { FilteredNovelList(controller, "favorite-filter-progress", NovelLocalFilter(CharacterCountFilter(minimum = 100_000)),
                library, loadPage = { _, _ -> Page(1, listOf(favorite, other)) }, onReset = {}) }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已检查 2 本，找到 2 本").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("收藏过滤作品").assertExists()
        compose.runOnIdle { library = library.copy(books = listOf(SavedBook(favorite))) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已检查 2 本，找到 1 本").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("收藏过滤作品").assertDoesNotExist()
        compose.onNodeWithText("保留作品").assertExists()
        compose.runOnIdle { library = library.copy(books = emptyList()) }
        compose.onNodeWithText("收藏过滤作品").assertExists()
        compose.runOnIdle { library = library.copy(books = listOf(SavedBook(favorite)), hideLocalFavorites = false) }
        compose.onNodeWithText("收藏过滤作品").assertExists()
    }

    @Test fun completedPagesStayVisibleWhileLoadingAndRetryResumesTheFailedPage() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val secondPage = CompletableDeferred<Unit>()
        val requested = CopyOnWriteArrayList<Int>()
        var fail = true
        compose.setContent {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val controller = remember { AppController(app, nav, scope, SnackbarHostState()) }
            MaterialTheme { FilteredNovelList(controller, "progress-test", NovelLocalFilter(CharacterCountFilter(minimum = 100_000)),
                LibraryState(), loadPage = { page, _ ->
                    requested += page
                    if(page == 1) {
                        secondPage.await()
                        if(fail) { fail = false; throw IOException("测试网络中断") }
                    }
                    Page(2, listOf(BookCard(BookRef("syosetu", "filter-progress-$page"), "匹配作品 $page", totalCharacters = 123_456)))
                }, onReset = {}) }
        }
        compose.waitUntil(10_000) { requested.size == 2 }
        compose.onNodeWithText("匹配作品 0").assertIsDisplayed()
        compose.onNodeWithText("12.3 万字").assertIsDisplayed()
        compose.onNodeWithTag("local-filter-loading").assertIsDisplayed()
        compose.onNodeWithTag("continue-filtered-search").assertIsNotEnabled()
        secondPage.complete(Unit)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("local-filter-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("匹配作品 0").assertIsDisplayed()
        compose.onNodeWithTag("continue-filtered-search").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("匹配作品 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("匹配作品 1").assertIsDisplayed()
        assertEquals(listOf(0, 1, 1), requested.toList())
    }
}

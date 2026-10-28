package cc.novelia.app.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import cc.novelia.app.ui.book.BookScreen
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.saveTestScreenshot
import cc.novelia.app.ui.theme.NoveliaTheme
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChapterCacheManagementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun storageEntryListsBooksAndConfirmedClearRemovesOnlyTheSelectedBook() {
        val app = application()
        val first = BookRef("syosetu", "cache-ui-${UUID.randomUUID()}")
        val second = BookRef("kakuyomu", "cache-ui-${UUID.randomUUID()}")
        app.store.cacheChapter(first, "one", Chapter(novelTitleZh = "待清理的测试小说", paragraphs = listOf("正文")))
        app.store.cacheChapter(second, "one", Chapter(novelTitleZh = "保留的测试小说", paragraphs = listOf("正文")))
        try {
            compose.setContent { NoveliaTheme("light") {
                val nav = rememberNavController()
                val c = AppController(app, nav, rememberCoroutineScope(), remember { SnackbarHostState() })
                NavHost(nav, startDestination = "settings") {
                    composable("settings") { SettingsScreen(c, "STORAGE") }
                    composable("chapter-cache") { ChapterCacheScreen(c) }
                }
            } }
            compose.onNodeWithText("书籍章节缓存").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("待清理的测试小说").fetchSemanticsNodes().isNotEmpty() }
            saveTestScreenshot("chapter-cache-management.png")
            compose.onNodeWithText("待清理的测试小说").performClick()
            compose.onNodeWithText("取消").performClick()
            assertNotNull(app.store.cachedChapter(first, "one"))
            compose.onNodeWithText("待清理的测试小说").performClick()
            compose.onNodeWithText("清理章节缓存").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("待清理的测试小说").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("保留的测试小说").assertIsDisplayed()
            assertNull(app.store.cachedChapter(first, "one"))
            assertNotNull(app.store.cachedChapter(second, "one"))
        } finally {
            app.store.clearChapterCache(first)
            app.store.clearChapterCache(second)
        }
    }

    @Test fun bookDetailsOfferSingleBookCleanupAndKeepTheDetailsVisible() {
        val app = application()
        val ref = BookRef("syosetu", "cache-detail-${UUID.randomUUID()}")
        val key = hashName("${app.session.capture().cacheAccount}:novel/${ref.key}")
        app.store.cacheChapter(ref, "one", Chapter(novelTitleZh = "详情缓存测试小说", paragraphs = listOf("正文")))
        app.metadataCache.write(key, appJson.encodeToString(WebDetail(titleJp = "详情缓存测试小说",
            toc = listOf(TocItem(titleJp = "第一章", chapterId = "one")))))
        try {
            compose.setContent { NoveliaTheme("light") {
                BookScreen(AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() }), ref)
            } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("详情缓存测试小说").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("清理本书章节缓存").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("清理章节缓存").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("清理章节缓存").performClick()
            compose.waitUntil(5_000) { !app.store.chapterFile(ref, "one").exists() }
            compose.onAllNodesWithText("详情缓存测试小说").onFirst().assertIsDisplayed()
            assertNotNull(app.metadataCache.read(key))
        } finally {
            app.store.clearChapterCache(ref)
            app.metadataCache.remove(key)
        }
    }

    private fun application(): NoveliaApplication {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        return app
    }
}

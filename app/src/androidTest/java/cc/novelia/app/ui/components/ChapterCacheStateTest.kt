package cc.novelia.app.ui.components

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.storage.LocalStore
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import cc.novelia.app.ui.components.book.rememberCachedChapterIds

class ChapterCacheStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cacheWritesAndClearsUpdateAnOpenDirectoryWithoutCancellingDownloads() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "toc-cache-${UUID.randomUUID()}")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        val store = LocalStore(context)
        val ref = BookRef("syosetu", "cache-test")
        val toc = listOf(TocItem(titleJp = "分节"), TocItem(titleJp = "第一章", chapterId = "one"))
        val generation = store.cacheGeneration.value
        compose.setContent {
            val cached = rememberCachedChapterIds(store, ref, toc)
            Text(if("one" in cached) "可离线阅读" else "未缓存")
        }
        compose.onNodeWithText("未缓存").assertIsDisplayed()
        store.cacheChapter(ref, "one", Chapter(paragraphs = listOf("测试正文")))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("可离线阅读").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("落盘通知不能使其他在途章节请求失效", generation, store.cacheGeneration.value)
        store.clearCache()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("未缓存").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(generation + 1, store.cacheGeneration.value)
    }
}

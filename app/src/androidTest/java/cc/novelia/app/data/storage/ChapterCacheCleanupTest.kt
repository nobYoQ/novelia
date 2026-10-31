package cc.novelia.app.data.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.chapters.chapterFreshness
import cc.novelia.app.data.chapters.recordChapterFreshness
import cc.novelia.app.data.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ChapterCacheCleanupTest {
    @Test fun clearingOneBookRemovesItsMemoryAndDiskCacheAndRejectsOldWritesOnlyForThatBook() = runBlocking {
        val store = isolatedStore()
        val first = BookRef("syosetu", "first")
        val second = BookRef("kakuyomu", "first")
        val chapter = Chapter(novelTitleZh = "同名小说", paragraphs = listOf("正文"))
        store.cacheChapter(first, "one", chapter)
        store.cacheChapter(second, "one", chapter)
        recordChapterFreshness(store, first, "one", 100)
        recordChapterFreshness(store, second, "one", 200)
        val firstGeneration = store.chapterGeneration(first)
        val secondGeneration = store.chapterGeneration(second)
        val globalGeneration = store.cacheGeneration.value
        val revision = store.chapterCacheRevision.value
        store.savePosition(first, Position("one", 5), "同名小说")
        val document = File(store.documentsDir, "local.txt").apply { writeText("本地小说", Charsets.UTF_8) }
        val download = File(store.downloadsDir, "download.txt").apply { writeText("下载文件", Charsets.UTF_8) }
        store.metadataCache.write(hashName("details"), "{}")
        assertNotNull(store.cachedChapter(first, "one"))

        store.clearChapterCache(first)

        assertNull(store.cachedChapter(first, "one"))
        assertFalse(store.chapterFile(first, "one").exists())
        assertEquals(chapter, store.cachedChapter(second, "one"))
        assertEquals(0L, chapterFreshness(store, first, "one"))
        assertEquals(200L, chapterFreshness(store, second, "one"))
        assertEquals(globalGeneration, store.cacheGeneration.value)
        assertTrue(store.chapterCacheRevision.value > revision)
        assertNull(store.withChapterCacheGeneration(first, firstGeneration) { store.cacheChapter(first, "late", chapter); true })
        assertEquals(true, store.withChapterCacheGeneration(second, secondGeneration) { store.cacheChapter(second, "next", chapter); true })
        assertFalse(store.chapterFile(first, "late").exists())
        assertEquals(5, store.state.value.positions.getValue(first.key).index)
        assertTrue(document.exists())
        assertTrue(download.exists())
        assertEquals("{}", store.metadataCache.read(hashName("details")))
        store.flush()
        assertEquals(second, LocalStore(store.context).chapterCacheSummaries().single().ref)
        store.clearCache()
        assertNull(store.withChapterCacheGeneration(second, secondGeneration) { true })
        assertTrue(store.chapterCacheSummaries().isEmpty())
    }

    @Test fun oldHashedChaptersCanBeRecoveredFromSavedDirectoryWithoutNetwork() {
        val store = isolatedStore()
        val ref = BookRef("syosetu", "legacy")
        store.saveBook(BookCard(ref, "旧版小说"))
        store.chapterFile(ref, "one").writeText(appJson.encodeToString(Chapter(paragraphs = listOf("正文"))), Charsets.UTF_8)
        store.metadataCache.write(hashName("old-account:novel/${ref.key}"),
            appJson.encodeToString(WebDetail(titleZh = "旧版小说", toc = listOf(TocItem(chapterId = "one")))))
        assertEquals(ref, store.chapterCacheSummaries().single().ref)
        store.clearChapterCache(ref)
        assertFalse(store.chapterFile(ref, "one").exists())
        assertTrue(store.chapterCacheSummaries().isEmpty())
    }

    private fun isolatedStore(): LocalStore {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "chapter-cleanup-${UUID.randomUUID()}")
        return LocalStore(object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        })
    }
}

package cc.novelia.app.data.cache

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.storage.hashName
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChapterCacheCatalogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val first = BookRef("syosetu", "first")
    private val second = BookRef("syosetu", "second")

    @Test fun sameTitleBooksRemainSeparateAfterRestartAndSingleBookRemoval() {
        val directory = temporary.newFolder()
        val index = temporary.newFile()
        val catalog = ChapterCacheCatalog(directory, index)
        val a = chapter(directory, first, "one", "第一本")
        val b = chapter(directory, second, "one", "第二本正文")
        catalog.register(first, "同名小说", listOf("one", "missing"))
        catalog.register(second, "同名小说", listOf("one"))
        val restored = ChapterCacheCatalog(directory, index)
        assertEquals(listOf(b), restored.files(second))
        assertEquals(2, restored.summaries().size)
        assertEquals(b.length(), restored.summaries().first().bytes)
        assertEquals(1, restored.summaries().first().chapters)
        restored.files(first).forEach { assertTrue(it.delete()) }
        restored.prune()
        assertFalse(a.exists())
        assertTrue(b.exists())
        assertEquals(second, ChapterCacheCatalog(directory, index).summaries().single().ref)
    }

    @Test fun legacyRecoveryUsesExactBookAndChapterHashesAndKeepsUnknownFilesManageable() {
        val directory = temporary.newFolder()
        val catalog = ChapterCacheCatalog(directory, temporary.newFile())
        val a = chapter(directory, first, "one", "旧缓存")
        val b = chapter(directory, second, "one", "同名旧缓存")
        File(directory, "unrelated.txt").writeText("保留", Charsets.UTF_8)
        catalog.recover(mapOf(first to "同名小说"), listOf("one", "missing"))
        assertEquals(listOf(a), catalog.files(first))
        assertEquals(listOf(b), catalog.files(null))
        assertEquals(setOf(first, null), catalog.summaries().map { it.ref }.toSet())
        assertEquals(a.length() + b.length(), catalog.summaries().sumOf { it.bytes })
        catalog.register(second, "恢复的第二本", listOf("one"))
        assertTrue(catalog.files(null).isEmpty())
    }

    @Test fun evictionAndRewritesUpdateReportedCountsAndSizesWithoutDeletingOtherFiles() {
        val directory = temporary.newFolder()
        val catalog = ChapterCacheCatalog(directory, temporary.newFile())
        val a = chapter(directory, first, "one", "一")
        val b = chapter(directory, first, "two", "二")
        catalog.register(first, "小说", listOf("one", "two"))
        assertTrue(a.delete())
        b.writeText("新的正文更长", Charsets.UTF_8)
        catalog.prune()
        val summary = catalog.summaries().single()
        assertEquals(1, summary.chapters)
        assertEquals(b.length(), summary.bytes)
    }

    @Test fun corruptIndexDoesNotHideOldFilesOrAuthorizePathsOutsideChapterDirectory() {
        val directory = temporary.newFolder()
        val index = temporary.newFile().apply { writeText("broken", Charsets.UTF_8) }
        val a = chapter(directory, first, "one", "正文")
        val catalog = ChapterCacheCatalog(directory, index)
        assertEquals(listOf(a), catalog.files(null))
        catalog.recover(mapOf(first to "小说"), listOf("one"))
        assertEquals(listOf(a), ChapterCacheCatalog(directory, index).files(first))
    }

    private fun chapter(directory: File, ref: BookRef, id: String, text: String) =
        File(directory, hashName("${ref.key}/$id") + ".json").apply { writeText(text, Charsets.UTF_8) }
}

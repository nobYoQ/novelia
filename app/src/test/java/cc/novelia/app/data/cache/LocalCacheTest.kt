package cc.novelia.app.data.cache

import cc.novelia.app.data.storage.hashName
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun weightedCacheRetainsRecentlyReadItemsWithinItsBudget() {
        val cache = WeightedMemoryCache<String, String>(3, 8) { it.length.toLong() }
        cache.put("a", "aaaa")
        cache.put("b", "bbbb")
        assertEquals("aaaa", cache["a"])
        cache.put("c", "cccc")

        assertEquals("aaaa", cache["a"])
        assertNull(cache["b"])
        assertEquals("cccc", cache["c"])
    }

    @Test fun oversizedReplacementDoesNotKeepStaleContentOrEvictOtherItems() {
        val cache = WeightedMemoryCache<String, String>(3, 8) { it.length.toLong() }
        cache.put("a", "aaaa")
        cache.put("b", "bbbb")
        cache.put("a", "a".repeat(9))

        assertNull(cache["a"])
        assertEquals("bbbb", cache["b"])
    }

    @Test fun entryLimitAppliesEvenToTinyValuesAndClearReleasesThem() {
        val cache = WeightedMemoryCache<String, String>(2, 100) { it.length.toLong() }
        cache.put("a", "")
        cache.put("b", "")
        cache.put("c", "")
        assertNull(cache["a"])
        cache.clear()
        assertNull(cache["b"])
        assertNull(cache["c"])
    }

    @Test fun diskCacheEvictsTheLeastRecentlyAccessedChapter() {
        val directory = temporaryFolder.newFolder()
        val a = chapter(directory, "a", 4, 1_000)
        val b = chapter(directory, "b", 4, 2_000)
        val index = ChapterCacheIndex(directory, 8)
        assertEquals(8L, index.size())
        index.accessed(a)
        val c = chapter(directory, "c", 4, 3_000)

        assertEquals(listOf("b.json"), index.written(c))
        assertTrue(a.exists())
        assertFalse(b.exists())
        assertTrue(c.exists())
        assertEquals(8L, index.size())
    }

    @Test fun replacingAChapterAccountsForItsPreviousSize() {
        val directory = temporaryFolder.newFolder()
        val a = chapter(directory, "a", 4, 1_000)
        val b = chapter(directory, "b", 4, 2_000)
        val index = ChapterCacheIndex(directory, 8)
        assertEquals(8L, index.size())
        a.writeText("a".repeat(6), Charsets.UTF_8)

        assertEquals(listOf("b.json"), index.written(a))
        assertFalse(b.exists())
        assertEquals(6L, index.size())
        index.reset()
        assertEquals(6L, index.size())
    }

    @Test fun importedDocumentsAndNonCacheFilesAreNotEvicted() {
        val directory = temporaryFolder.newFolder()
        val source = File(directory, "source.epub").apply { writeText("document") }
        val a = chapter(directory, "a", 16, 1_000)
        val index = ChapterCacheIndex(directory, 8)

        assertEquals(listOf("a.json"), index.written(a))
        assertTrue(source.exists())
        assertEquals(0L, index.size())
    }

    @Test fun cacheFileNamesKeepTheExistingUtf8Sha256Contract() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hashName("abc"))
        assertEquals(64, hashName("章节/一").length)
        assertTrue(hashName("章节/一").all { it in '0'..'9' || it in 'a'..'f' })
    }

    private fun chapter(directory: File, name: String, bytes: Int, modified: Long): File =
        File(directory, "$name.json").apply {
            writeText("x".repeat(bytes), Charsets.UTF_8)
            assertTrue(setLastModified(modified))
        }
}

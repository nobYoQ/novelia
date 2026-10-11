package cc.novelia.app.data.cache

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MetadataCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val firstKey = "a".repeat(64)
    private val secondKey = "b".repeat(64)

    @Test fun freshResponseExpiresAndCanStillBeReadOffline() {
        val cache = MetadataCache(temporary.root)
        cache.write(firstKey, "中文缓存")
        val written = File(temporary.root, "$firstKey.json").lastModified()
        assertEquals("中文缓存", cache.read(firstKey, now = written + 10, maxAgeMillis = 20))
        assertNull(cache.read(firstKey, now = written + 30, maxAgeMillis = 20))
        assertEquals("中文缓存", cache.read(firstKey, now = written + 30))
        assertNull(cache.read(firstKey, now = written + 10, newerThan = written))
        assertNull(cache.read(firstKey, now = written - 1))
        assertNull(cache.read(secondKey))
    }

    @Test fun oldestResponsesAreEvictedWithinBudget() {
        val cache = MetadataCache(temporary.root, maxBytes = 10)
        cache.write(firstKey, "123456")
        File(temporary.root, "$firstKey.json").setLastModified(1_000)
        cache.write(secondKey, "abcdef")
        assertNull(cache.read(firstKey))
        assertEquals("abcdef", cache.read(secondKey))
        assertTrue(temporary.root.listFiles()!!.sumOf { it.length() } <= 10)
        cache.write(firstKey, "x".repeat(11))
        assertNull(cache.read(firstKey))
    }

    @Test fun mutationInvalidationSurvivesCacheRecreation() {
        val cache = MetadataCache(temporary.root)
        cache.write(firstKey, "before editing")
        val written = File(temporary.root, "$firstKey.json").lastModified()
        cache.invalidate(written)
        assertNull(MetadataCache(temporary.root).read(firstKey, now = written + 10))
    }

    @Test fun replacingAndClearingCacheMaintainAccurateIncrementalSize() {
        val cache = MetadataCache(temporary.root, maxBytes = 12)
        cache.write(firstKey, "123456", fetchedAt = 1_000)
        cache.write(secondKey, "abcd", fetchedAt = 2_000)
        assertEquals(10L, cache.size())
        cache.write(firstKey, "123", fetchedAt = 3_000)
        assertEquals(7L, cache.size())
        assertEquals(7L, MetadataCache(temporary.root, 12).size())
        cache.invalidate(3_000)
        cache.clear()
        assertEquals(0L, cache.size())
        cache.write(firstKey, "new", fetchedAt = 4_000)
        assertEquals("new", cache.read(firstKey, now = 4_001))
        assertEquals(3L, cache.size())
    }

    @Test fun hotReadsReuseTextWithoutExtendingFreshnessAndHonorInvalidation() {
        val cache = MetadataCache(temporary.root)
        cache.write(firstKey, "重复打开的详情", fetchedAt = 1_000)
        val first = cache.read(firstKey, now = 1_010, maxAgeMillis = 20)
        assertSame(first, cache.read(firstKey, now = 1_020, maxAgeMillis = 20))
        assertNull(cache.read(firstKey, now = 1_021, maxAgeMillis = 20))
        cache.invalidate(1_000)
        assertNull(cache.read(firstKey, now = 1_030))
        cache.write(firstKey, "编辑后的详情", fetchedAt = 2_000)
        assertEquals("编辑后的详情", cache.read(firstKey, now = 2_001))
        cache.clear()
        assertNull(cache.read(firstKey, now = 2_002))
    }

    @Test fun delayedResponsesAndEqualTimestampsDoNotCorruptEvictionOrder() {
        val cache = MetadataCache(temporary.root, maxBytes = 12)
        val thirdKey = "c".repeat(64)
        cache.write(firstKey, "aaaaaa", fetchedAt = 3_000)
        cache.write(secondKey, "bbbbbb", fetchedAt = 3_000)
        cache.write(thirdKey, "cccccc", fetchedAt = 1_000)
        assertNull(cache.read(thirdKey, now = 4_000))
        assertEquals("aaaaaa", cache.read(firstKey, now = 4_000))
        cache.write(firstKey, "newaaa", fetchedAt = 5_000)
        cache.write(thirdKey, "newccc", fetchedAt = 6_000)
        assertNull(cache.read(secondKey, now = 7_000))
        assertEquals("newaaa", cache.read(firstKey, now = 7_000))
        assertEquals(12L, cache.size())
        assertEquals(12L, MetadataCache(temporary.root, 12).size())
    }

    @Test fun evictingHotTextFromMemoryKeepsTheDiskCopyAvailable() {
        val cache = MetadataCache(temporary.root)
        cache.write(firstKey, "磁盘副本", fetchedAt = 1_000)
        val original = cache.read(firstKey, now = 2_000)
        repeat(16) { index -> cache.write(index.toString(16).padStart(64, '0'), "other-$index", fetchedAt = 1_000) }
        val reloaded = cache.read(firstKey, now = 2_000)
        assertEquals(original, reloaded)
        assertNotSame(original, reloaded)
        assertSame(reloaded, cache.read(firstKey, now = 2_000))
        File(temporary.root, "$firstKey.json").delete()
        assertNull(cache.read(firstKey, now = 2_000))
    }

    @Test fun delayedDetailsCannotReplaceANewerChapterDirectoryOrItsObservationTime() {
        val cache = MetadataCache(temporary.root)
        cache.write(firstKey, "最新目录", fetchedAt = 3_000)
        cache.write(firstKey, "旧目录", fetchedAt = 2_000)
        val snapshot = cache.readSnapshot(firstKey, now = 4_000)!!
        assertEquals("最新目录", snapshot.text)
        assertEquals(3_000L, snapshot.fetchedAt)
        assertEquals(snapshot, MetadataCache(temporary.root).readSnapshot(firstKey, now = 5_000))
        assertNull(cache.readSnapshot(firstKey, now = 5_000, maxAgeMillis = 1_000))
        assertEquals(3_000L, File(temporary.root, "$firstKey.json").lastModified())
    }
}

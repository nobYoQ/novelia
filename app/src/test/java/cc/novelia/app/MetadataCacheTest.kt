package cc.novelia.app

import cc.novelia.app.data.MetadataCache
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
}

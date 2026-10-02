package cc.novelia.app

import cc.novelia.app.data.cache.MetadataCache
import cc.novelia.app.data.catalog.*
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelFilterCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val book = BookCard(BookRef("syosetu", "n1234"), "测试小说", total = 150, updateAt = 123L, novelType = "连载中")
    private val details = NovelFilterDetails(123_456, listOf(Author("作者")))

    @Test fun smallMetadataSurvivesRestartAndReusesUnchangedBooksBeyondFiveMinutes() {
        val disk = MetadataCache(temporary.root)
        NovelFilterMetadataCache(disk).write(book, "account", details, 1_000)
        val restored = NovelFilterMetadataCache(MetadataCache(temporary.root)).read(book.ref, "account", 0, now = 600_000)!!
        assertTrue(restored.reusable(book, now = 600_000))
        assertEquals(123_456L, restored.metadata.details.applyTo(book).totalCharacters)
        assertEquals(listOf("作者"), restored.metadata.details.applyTo(book).authors)
        assertTrue("字数缓存不保存完整目录", disk.size() < 1_024)
        assertFalse(restored.reusable(book, now = 24 * 60 * 60_000L + 1_001))
        assertFalse(restored.reusable(book, now = 999))
    }

    @Test fun chapterCountUpdateTimeAndNovelTypeInvalidateEvenFreshMetadata() {
        val cache = NovelFilterMetadataCache(MetadataCache(temporary.root))
        cache.write(book, "account", details, 1_000)
        val hit = cache.read(book.ref, "account", 0, now = 2_000)!!
        assertFalse(hit.reusable(book.copy(total = 151), 2_000))
        assertFalse(hit.reusable(book.copy(updateAt = 124L), 2_000))
        assertFalse(hit.reusable(book.copy(novelType = "已完结"), 2_000))
        assertTrue(hit.reusable(book.copy(title = "新书名", tags = listOf("新标签")), 2_000))
    }

    @Test fun unknownCountsHaveAShorterLifetimeAndNeverBecomeZero() {
        val cache = NovelFilterMetadataCache(MetadataCache(temporary.root))
        cache.write(book, "guest", NovelFilterDetails(), 1_000)
        val unknown = cache.read(book.ref, "guest", 0, now = 2_000)!!
        assertTrue(unknown.reusable(book, now = 15 * 60_000L + 1_000))
        assertFalse(unknown.reusable(book, now = 15 * 60_000L + 1_001))
        assertNull(unknown.metadata.details.applyTo(book).totalCharacters)
    }

    @Test fun accountIsolationMutationsAndCacheClearingArePreserved() {
        val disk = MetadataCache(temporary.root)
        val cache = NovelFilterMetadataCache(disk)
        cache.write(book, "one", details, 1_000)
        assertNull(cache.read(book.ref, "two", 0, now = 2_000))
        assertNull(cache.read(book.ref, "one", 1_000, now = 2_000))
        disk.invalidate(1_000)
        assertNull(cache.read(book.ref, "one", 0, now = 2_000))
        cache.write(book, "one", details, 3_000)
        assertNotNull(cache.read(book.ref, "one", 0, now = 3_001))
        disk.clear()
        assertNull(cache.read(book.ref, "one", 0, now = 3_001))
    }

    @Test fun filteringDecodesOnlySmallFieldsAndPreservesTheExactCount() {
        // 筛选无需解析 TocItem，未知目录字段可直接跳过。
        val raw = """{"totalCharacters":123456,"authors":[{"name":"作者"}],"toc":[${List(4_000) { "{\"chapterId\":\"$it\",\"titleJp\":\"正文目录\"}" }.joinToString(",")}]}"""
        val parsed = appJson.decodeFromString<NovelFilterDetails>(raw)
        assertEquals(details, parsed)
        assertTrue(appJson.encodeToString(parsed).length < 200)
        assertFalse(CharacterCountFilter(minimum = 123_457).matches(parsed.totalCharacters))
        assertEquals("12.3 万字", formatApproximateCharacters(parsed.totalCharacters!!))
    }

    @Test fun candidatePagesAreBoundedExpireAndRespectIdentity() {
        val cache = NovelFilterPageCache(capacity = 2, lifetimeMillis = 120_000)
        val first = Page(5, listOf(book))
        val firstKey = listOf("account", 0, "魔法", 0)
        cache.put(firstKey, first, 1_000)
        assertSame(first, cache.get(firstKey, 121_000))
        assertNull(cache.get(firstKey, 121_001))
        assertNull(cache.get(listOf("other-account", 0, "魔法", 0), 2_000))
        assertNull(cache.get(listOf("account", 1, "魔法", 0), 2_000))
        cache.put("second", first, 1_000)
        cache.get(firstKey, 2_000)
        cache.put("third", first, 1_000)
        assertNull(cache.get("second", 2_000))
        assertSame(first, cache.get(firstKey, 2_000))
    }

    @Test fun approximateCountsRoundWithoutVerboseDigitsAndExactCountsStayAvailable() {
        assertEquals("0 字", formatApproximateCharacters(0))
        assertEquals("999 字", formatApproximateCharacters(999))
        assertEquals("1 千字", formatApproximateCharacters(1_000))
        assertEquals("1.3 千字", formatApproximateCharacters(1_250))
        assertEquals("1 万字", formatApproximateCharacters(9_999))
        assertEquals("12.3 万字", formatApproximateCharacters(123_456))
        assertEquals("100 万字", formatApproximateCharacters(999_999))
        assertEquals("1 亿字", formatApproximateCharacters(99_999_999))
        assertEquals("1.2 亿字", formatApproximateCharacters(123_456_789))
        assertEquals("123,456 字", formatExactCharacters(123_456))
        assertEquals("不超过 123456 字", CharacterCountFilter(maximum = 123_456).summary())
    }
}

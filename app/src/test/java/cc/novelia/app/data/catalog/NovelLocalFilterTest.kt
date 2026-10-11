package cc.novelia.app.data.catalog

import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class NovelLocalFilterTest {
    private fun book(id: Int, count: Long? = null, tags: List<String> = emptyList()) =
        BookCard(BookRef("syosetu", "$id"), "作品 $id", totalCharacters = count, tags = tags)

    @Test fun inclusiveBoundsUnknownAndZeroRemainDistinct() {
        val range = CharacterCountFilter(100_000, 500_000)
        assertFalse(range.matches(99_999))
        assertTrue(range.matches(100_000))
        assertTrue(range.matches(500_000))
        assertFalse(range.matches(500_001))
        assertFalse(range.matches(null))
        assertTrue(range.copy(includeUnknown = true).matches(null))
        assertFalse(range.copy(includeUnknown = true).matches(0))
        assertTrue(CharacterCountFilter(maximum = 0).matches(0))
        assertFalse(CharacterCountFilter(maximum = 0).matches(null))
        assertTrue(CharacterCountFilter().matches(null))
        assertFalse(range.matches(-1))
    }

    @Test fun filtersAndCharacterMetadataSurviveLegacyAndNewBackups() {
        val legacy = appJson.decodeFromString<SavedSearchPreset>("""{"name":"旧搜索"}""")
        assertFalse(legacy.localFilter.active)
        val filter = NovelLocalFilter(CharacterCountFilter(100_000, includeUnknown = true))
        val preset = legacy.copy(localFilter = filter)
        assertEquals(preset, appJson.decodeFromString<SavedSearchPreset>(appJson.encodeToString(preset)))
        assertFalse(preset.hasSameConditions(legacy))
        assertTrue(preset.summary().contains("10 万字"))
        val card = book(1, 123_456)
        assertEquals(card, appJson.decodeFromString<BookCard>(appJson.encodeToString(card)))
        assertEquals(123_456L, card.copy(totalCharacters = null).withKnownUpdateTime(card).totalCharacters)
        assertEquals(0L, card.copy(totalCharacters = 0).withKnownUpdateTime(card).totalCharacters)
        assertNull(book(2).withKnownUpdateTime(card).totalCharacters)
        assertEquals(123_456L, WebDetail(totalCharacters = 123_456).card(card.ref).totalCharacters)
    }

    @Test fun oldGroupPresetsBecomeVisibleOrdinaryTagConditionsExactlyOnce() {
        val old = appJson.decodeFromString<SavedSearchPreset>("""{"name":"旧分组","query":"旅人 GL$","localFilter":{"includedGroups":["gl","bl","invalid"],"excludedGroups":["bl"]}}""")
        val migrated = old.normalized()
        assertTrue(migrated.query.startsWith("旅人 GL$ "))
        assertTrue(migrated.query.contains("ガールズラブ$"))
        assertTrue(migrated.query.contains("-ボーイズラブ$"))
        assertEquals(1, migrated.query.split(' ').count { it == "GL$" })
        assertEquals(NovelLocalFilter(), migrated.localFilter)
        assertEquals(migrated, migrated.normalized())
    }

    @Test fun sparseResultsStopAfterBoundedWorkAndResumeWithoutSkippingBooks() = runTest {
        val requests = mutableListOf<Int>()
        val filter = NovelLocalFilter(CharacterCountFilter(minimum = 100))
        val loader: suspend (Int) -> Page<BookCard> = { page -> requests += page; Page(5, listOf(book(page, if(page == 4) 100 else 50))) }
        val first = loadFilteredNovels(0, filter, loadPage = loader, enrich = { it })
        assertEquals(listOf(0, 1, 2), requests)
        assertEquals(3, first.nextPage)
        assertFalse(first.endReached)
        assertTrue(first.books.isEmpty())
        val second = loadFilteredNovels(first.nextPage, filter, loadPage = loader, enrich = { it })
        assertEquals(listOf(0, 1, 2, 3, 4), requests)
        assertEquals(listOf("4"), second.books.map { it.ref.id })
        assertTrue(second.endReached)
    }

    @Test fun lastPageOverflowIsPreservedAndDuplicatesAreNotEnrichedTwice() = runTest {
        val enriched = mutableListOf<String>()
        val result = loadFilteredNovels(0, NovelLocalFilter(CharacterCountFilter(minimum = 1)), targetSize = 3,
            loadPage = { page -> Page(4, if(page == 0) listOf(book(1, 10), book(2, 10)) else listOf(book(2, 10), book(3, 10), book(4, 10))) },
            enrich = { books -> enriched += books.map { it.ref.id }; books })
        assertEquals(listOf("1", "2", "3", "4"), result.books.map { it.ref.id })
        assertEquals(enriched.distinct(), enriched)
        assertEquals(2, result.nextPage)
    }

    @Test fun unknownCountsRemainCountedAfterTagsHaveBeenHandledByTheRemoteQuery() = runTest {
        val enriched = mutableListOf<String>()
        val result = loadFilteredNovels(0, NovelLocalFilter(CharacterCountFilter(minimum = 10)),
            loadPage = { Page(1, listOf(book(1, tags = listOf("GL")), book(3, 20, listOf("百合")))) },
            enrich = { books -> enriched += books.map { it.ref.id }; books })
        assertEquals(listOf("1", "3"), enriched)
        assertEquals(1, result.unknown)
        assertEquals(listOf("3"), result.books.map { it.ref.id })
    }

    @Test fun cancellationDoesNotTurnIntoEmptySuccess() = runTest {
        try {
            loadFilteredNovels(0, NovelLocalFilter(CharacterCountFilter(minimum = 1)),
                loadPage = { Page(1, listOf(book(1))) }, enrich = { throw CancellationException("条件改变") })
            fail("取消必须传播")
        } catch(_: CancellationException) { }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun resultsArePublishedAfterEachPageInsteadOfWaitingForTheWholeScan() = runTest {
        val published = mutableListOf<Pair<Long, FilteredNovelBatch>>()
        val result = loadFilteredNovels(0, NovelLocalFilter(CharacterCountFilter(minimum = 10)),
            loadPage = { page -> Page(3, listOf(book(page, 20))) },
            enrich = { delay(1_000); it }, onProgress = { published += testScheduler.currentTime to it })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), published.map { it.first })
        assertEquals(listOf(1, 2, 3), published.map { it.second.books.size })
        assertEquals(result, published.last().second)
    }

    @Test fun laterPageFailureKeepsACommittedCursorAndDoesNotSkipTheFailedPage() = runTest {
        var checkpoint = FilteredNovelBatch()
        try {
            loadFilteredNovels(0, NovelLocalFilter(), loadPage = { page ->
                if(page == 1) throw java.io.IOException("网络中断")
                Page(2, listOf(book(1)))
            }, enrich = { it }, onProgress = { checkpoint = it })
            fail("应保留网络错误")
        } catch(_: java.io.IOException) { }
        assertEquals(1, checkpoint.nextPage)
        assertEquals(listOf("1"), checkpoint.books.map { it.ref.id })
        assertEquals(1, checkpoint.scanned)
        val resumed = loadFilteredNovels(checkpoint.nextPage, NovelLocalFilter(),
            loadPage = { page -> assertEquals(1, page); Page(2, listOf(book(2))) }, enrich = { it })
        assertEquals(listOf("1", "2"), (checkpoint.books + resumed.books).map { it.ref.id })
    }

    @Test fun knownBlockedBooksDoNotNeedAWordCountRequest() = runTest {
        val fetched = mutableListOf<String>()
        loadFilteredNovels(0, NovelLocalFilter(CharacterCountFilter(minimum = 1)),
            loadPage = { Page(1, listOf(book(1), book(2))) }, visible = { it.ref.id != "1" },
            enrich = { books -> fetched += books.map { it.ref.id }; books })
        assertEquals(listOf("2"), fetched)
    }
}

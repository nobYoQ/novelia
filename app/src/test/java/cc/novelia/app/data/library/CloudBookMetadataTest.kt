package cc.novelia.app.data.library

import cc.novelia.app.data.auth.AuthenticationSession
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.cache.MetadataCache
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import cc.novelia.app.ui.components.book.bookRowStatus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CloudBookMetadataTest {
    @get:Rule val temporary = TemporaryFolder()
    private class TestSession : AuthenticationSession {
        var binding = SessionBinding("alice", 1)
        override fun capture() = binding
        override fun tokenFor(binding: SessionBinding): String? {
            if(binding != this.binding) throw SessionChangedException()
            return null
        }
        override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?) = false
    }
    private val book = BookCard(BookRef("syosetu", "story"), "作品", updateAt = 1_700_000_100L)

    @Test fun historyFetchesUnvisitedBooksForGuestsAndReusesTheCatalogAndCoverClassification() = runBlocking {
        val session = TestSession().apply { binding = SessionBinding(null, 2) }
        val cache = MetadataCache(temporary.newFolder())
        val key = hashName("guest:novel/${book.ref.key}")
        val detail = WebDetail(type = "连载中", attentions = listOf("R18"), toc = listOf(
            TocItem(chapterId = "chapter", titleZh = "最新话", createAt = 1_700_000_000L)))
        var fetched = 0
        val loader = HistoryCatalogLoader(session, cache, { 0 }) {
            fetched++
            cache.write(key, appJson.encodeToString(detail))
            detail
        }
        val seen = mutableListOf<BookCard>()
        repeat(2) { loader.load(book, session.binding, seen::add) }
        assertEquals(1, fetched)
        assertEquals(2, seen.size)
        for (result in seen) {
            assertEquals("最新话", result.latestChapter?.title)
            assertEquals("连载中", result.novelType)
            assertEquals(listOf("R18"), result.attentions)
            assertNull(result.cloudReading)
        }
        loader.load(book.copy(ref = BookRef("local", "volume")), session.binding, seen::add)
        loader.load(book.copy(ref = BookRef("wenku", "parent")), session.binding, seen::add)
        assertEquals(1, fetched)
        assertEquals(2, seen.size)
    }

    @Test fun historyRechecksAShortCachedCatalogAndAcceptsTheServersCurrentOrder() = runBlocking {
        val session = TestSession()
        val cache = MetadataCache(temporary.newFolder())
        val detail = WebDetail(toc = listOf(TocItem(chapterId = "current", titleZh = "当前章")),
            lastReadChapterId = "current")
        cache.write(hashName("alice:novel/${book.ref.key}"), appJson.encodeToString(detail))
        val versions = mutableListOf<BookCard>()
        val loader = HistoryCatalogLoader(session, cache, { 0 }) { detail }
        loader.load(book.copy(total = 3), session.binding, versions::add)
        assertEquals(listOf(null, "current"), versions.map { it.latestChapter?.chapterId })
        assertEquals("alice", versions.last().cloudReading?.account)
    }

    @Test fun onlyDetailCanConfirmUnreadAndKnownListDatesAreRetained() = runBlocking {
        val session = TestSession()
        val loader = CloudBookMetadataLoader(session) { WebDetail(toc = listOf(TocItem(chapterId = "1", createAt = 1_700_000_000L))) }
        val resolved = loader.load(book)
        assertTrue(resolved.cloudReading!!.chapterResolved)
        assertEquals("未读", bookRowStatus(resolved, null, null, null, "alice").progressLabel)
        assertEquals(book.updateAt, resolved.updateAt)
    }

    @Test fun switchingAccountsDuringARequestDiscardsTheResult() = runBlocking {
        val session = TestSession()
        val loader = CloudBookMetadataLoader(session) {
            session.binding = SessionBinding("bob", 2)
            WebDetail(lastReadChapterId = "private-chapter")
        }
        try {
            loader.load(book)
            fail("A former account's response must not enter the new account's list")
        } catch(_: SessionChangedException) { }
    }

    @Test fun atMostTwoDetailsLoadAndScrollingAwayCancelsAQueuedBook() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        val loader = CloudBookMetadataLoader(TestSession()) {
            started++
            gate.await()
            WebDetail()
        }
        val loads = (1..3).map { async(start = CoroutineStart.UNDISPATCHED) { loader.load(book.copy(ref = BookRef("syosetu", "$it"))) } }
        assertEquals(2, started)
        loads.last().cancelAndJoin()
        gate.complete(Unit)
        loads.take(2).awaitAll()
        assertEquals(2, started)
    }

    @Test fun localVolumesWenkuAndGuestListsDoNotFetchWebDetails() = runBlocking {
        val session = TestSession()
        val loader = CloudBookMetadataLoader(session) { error("Unexpected web request") }
        for(ref in listOf(BookRef("local", "volume"), BookRef("wenku", "series"))) {
            val card = book.copy(ref = ref)
            assertEquals(card, loader.load(card))
        }
        session.binding = SessionBinding(null, 2)
        assertEquals(book, loader.load(book))
    }
}

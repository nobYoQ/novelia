package cc.novelia.app.data.library

import cc.novelia.app.data.auth.AuthenticationSession
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.components.book.bookRowStatus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CloudBookMetadataTest {
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

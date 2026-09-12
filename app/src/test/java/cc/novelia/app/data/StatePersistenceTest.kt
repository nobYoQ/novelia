package cc.novelia.app.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatePersistenceTest {
    @Test fun burstPersistsOnlyTheLatestSnapshot() = runTest {
        val writes = mutableListOf<Int>()
        val persistence = StatePersistence<Int>(backgroundScope) { writes += it }
        repeat(100) { persistence.submit(it) }

        runCurrent()
        assertTrue(writes.isEmpty())
        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf(99), writes)
    }

    @Test fun flushMakesPendingStateDurableWithoutWaitingForTheBatchDelay() = runTest {
        val writes = mutableListOf<Int>()
        val persistence = StatePersistence<Int>(backgroundScope) { writes += it }
        persistence.submit(1)
        persistence.flush()
        assertEquals(listOf(1), writes)

        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(1), writes)
    }

    @Test fun updateDuringAnActiveWriteIsNotLostOrWrittenOutOfOrder() = runTest {
        val writes = mutableListOf<Int>()
        val firstWrite = CompletableDeferred<Unit>()
        val persistence = StatePersistence<Int>(backgroundScope) {
            if (it == 1) firstWrite.await()
            writes += it
        }
        persistence.submit(1)
        val flush = async { persistence.flush() }
        runCurrent()
        persistence.submit(2)
        firstWrite.complete(Unit)
        flush.await()
        persistence.flush()

        assertEquals(listOf(1, 2), writes)
    }

    @Test fun backgroundWriteRecoversAfterFailureAndRetainsTheNewestState() = runTest {
        val writes = mutableListOf<Int>()
        var attempts = 0
        val persistence = StatePersistence<Int>(backgroundScope) {
            if (attempts++ == 0) throw IOException("disk unavailable")
            writes += it
        }
        persistence.submit(1)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertNotNull(persistence.error.value)
        persistence.submit(2)

        advanceTimeBy(1_100)
        runCurrent()

        assertEquals(listOf(2), writes)
        assertNull(persistence.error.value)
    }

    @Test fun flushReportsFailureAndKeepsTheSnapshotAvailableForRetry() = runTest {
        var failWrite = true
        val writes = mutableListOf<Int>()
        val persistence = StatePersistence<Int>(backgroundScope) {
            if (failWrite) throw IOException("disk unavailable")
            writes += it
        }
        persistence.submit(7)
        try {
            persistence.flush()
            fail("flush must report an unsuccessful write")
        } catch (_: IOException) {
            assertNotNull(persistence.error.value)
        }
        failWrite = false
        persistence.flush()
        assertEquals(listOf(7), writes)
        assertNull(persistence.error.value)
    }
}

package cc.novelia.app.ui.reader

import androidx.compose.ui.geometry.Offset
import cc.novelia.app.reader.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderTapNavigationStateTest {
    private val customDoubleTap = ReaderTapZoneMap.Default.withAction(0, 0, true, ReaderTapAction.Navigate)
    private class Harness(scope: CoroutineScope, var map: ReaderTapZoneMap = ReaderTapZoneMap.Default) {
        val events = mutableListOf<String>()
        val navigation = ReaderTapNavigation(scope)
        init { configure() }
        fun configure(enabled: Boolean = true) = navigation.configure(true, enabled, { events += "menu" }, { events += "page:$it" }, map, { events += "toc" })
        fun down(cell: Int, time: Long) = navigation.begin(Offset((cell % 3 + .5f) * 300, (cell / 3 + .5f) * 600), 900, 1800, time)
        fun tap(cell: Int, time: Long = 0) { down(cell, time); navigation.release(true, time + 10); navigation.click(); navigation.finish() }
    }

    @Test fun navigateAndDisabledCellsHaveDistinctActions() = runTest {
        val harness = Harness(this, ReaderTapZoneMap.Default.withAction(2, 1, false, ReaderTapAction.Navigate))
        harness.tap(5)
        assertEquals(listOf("toc"), harness.events)
        harness.map = harness.map.withAction(2, 1, false, ReaderTapAction.None)
        harness.configure()
        harness.tap(5, 500)
        advanceUntilIdle()
        assertEquals(listOf("toc"), harness.events)
    }

    @Test fun doubleTapRunsOnlyTheDoubleAction() = runTest {
        val harness = Harness(this, customDoubleTap)
        harness.tap(0)
        advanceTimeBy(100)
        assertTrue(harness.events.isEmpty())
        harness.tap(0, 100)
        advanceUntilIdle()
        assertEquals(listOf("toc"), harness.events)
    }

    @Test fun singleTapWaitsOnlyWhereDoubleTapIsConfigured() = runTest {
        val harness = Harness(this, customDoubleTap)
        harness.tap(5)
        assertEquals(listOf("page:1"), harness.events)
        harness.tap(0, 500)
        advanceTimeBy(301)
        assertEquals(listOf("page:1", "page:-1"), harness.events)
        assertEquals(harness.map.regionFor(0).bounds, harness.navigation.feedback!!.bounds)
    }

    @Test fun doubleOnlyActionNeverLeaksIntoASingleTap() = runTest {
        val harness = Harness(this, ReaderTapZoneMap.Default.withAction(2, 1, false, ReaderTapAction.None)
            .withAction(2, 1, true, ReaderTapAction.NextPage))
        harness.tap(5)
        advanceUntilIdle()
        assertTrue(harness.events.isEmpty())
    }

    @Test fun equivalentDecodedMapDoesNotCancelAnActiveTap() = runTest {
        val harness = Harness(this)
        harness.down(5, 0)
        harness.map = ReaderTapZoneMap.decode(ReaderTapZoneMap.encode(harness.map))
        harness.configure()
        harness.navigation.release(true, 10)
        harness.navigation.click()
        assertEquals(listOf("page:1"), harness.events)
    }

    @Test fun disablingDraggingOrDisposingCancelsPendingActions() = runTest {
        val harness = Harness(this, customDoubleTap)
        harness.tap(0)
        harness.configure(false)
        advanceUntilIdle()
        assertTrue(harness.events.isEmpty())
        harness.configure()
        harness.tap(0, 500)
        harness.navigation.moved(Offset(500f, 500f), 10f, true)
        advanceUntilIdle()
        assertTrue(harness.events.isEmpty())
        harness.tap(0, 1_000)
        harness.navigation.dispose()
        advanceUntilIdle()
        assertTrue(harness.events.isEmpty())
    }

    @Test fun everyDefaultZoneRespondsOnReleaseWithoutWaitingEvenForRapidRepeatedTaps() = runTest {
        val harness = Harness(this)
        val expected = listOf("page:-1", "page:-1", "page:1", "page:-1", "menu", "page:1", "page:-1", "page:1", "page:1")
        expected.forEachIndexed { cell, action ->
            repeat(2) { tap ->
                harness.tap(cell, cell * 500L + tap * 100L)
                assertEquals(cell * 2 + tap + 1, harness.events.size)
                assertEquals(action, harness.events.last())
            }
        }
        advanceUntilIdle()
        assertEquals(18, harness.events.size)
    }
}

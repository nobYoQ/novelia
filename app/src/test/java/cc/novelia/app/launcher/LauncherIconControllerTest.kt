package cc.novelia.app.launcher

import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherIconControllerTest {
    @Test fun selectionIsDurableButOnlyChangesComponentsAfterBackgrounding() = runTest {
        val backend = FakeBackend()
        val controller = LauncherIconController(backend, this, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.onForeground()
        controller.select("night")
        advanceUntilIdle()
        assertEquals("night", backend.saved)
        assertEquals(setOf("default"), backend.enabled)
        assertTrue(controller.state.value.pending)
        controller.onBackground()
        advanceUntilIdle()
        assertEquals(setOf("night"), backend.enabled)
        assertFalse(controller.state.value.pending)
    }

    @Test fun latestChoiceWinsAndSelectingCurrentIconCancelsPendingChange() = runTest {
        val backend = FakeBackend()
        val controller = LauncherIconController(backend, this, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.select("night")
        controller.select("ink")
        advanceUntilIdle()
        assertEquals("ink", backend.saved)
        controller.select("default")
        controller.onBackground()
        advanceUntilIdle()
        assertEquals("default", backend.saved)
        assertEquals(0, backend.applyCalls)
        assertFalse(controller.state.value.pending)
    }

    @Test fun restartRecoversPendingSelectionWithoutApplyingItDuringStartup() = runTest {
        val backend = FakeBackend()
        val controller = LauncherIconController(backend, this, StandardTestDispatcher(testScheduler))
        controller.select("ink")
        advanceUntilIdle()
        val restored = LauncherIconController(backend, this, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals("ink", restored.state.value.selectedId)
        assertTrue(restored.state.value.pending)
        assertEquals(0, backend.applyCalls)
        restored.onBackground()
        advanceUntilIdle()
        val restartedAgain = LauncherIconController(backend, this, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertFalse(restartedAgain.state.value.pending)
        assertEquals("ink", restartedAgain.state.value.current?.id)
    }

    @Test fun returningToForegroundWhileDiskSaveIsPendingPreventsSwitching() = runTest {
        val disk = QueuedDispatcher()
        val backend = FakeBackend()
        val controller = LauncherIconController(backend, this, disk)
        runCurrent()
        disk.runQueued()
        runCurrent()
        controller.select("night")
        runCurrent()
        assertTrue(controller.state.value.saving)
        controller.onBackground()
        controller.onForeground()
        disk.runQueued()
        advanceUntilIdle()
        assertEquals(0, backend.applyCalls)
        assertTrue(controller.state.value.pending)
        controller.onBackground()
        advanceUntilIdle()
        assertEquals(setOf("night"), backend.enabled)
    }

    @Test fun failedSaveDoesNotChangeSelectionOrScheduleAnUnpersistedIcon() = runTest {
        val backend = FakeBackend().apply { failSave = true }
        val controller = LauncherIconController(backend, this, StandardTestDispatcher(testScheduler))
        controller.select("night")
        advanceUntilIdle()
        assertEquals("default", controller.state.value.selectedId)
        assertNotNull(controller.state.value.error)
        assertFalse(controller.state.value.saving)
        controller.onBackground()
        advanceUntilIdle()
        assertEquals(0, backend.applyCalls)
    }

    @Test fun partialSwitchKeepsLaunchableEntryAndRetriesOnNextBackground() = runTest {
        val backend = FakeBackend().apply { failDisable = true }
        val controller = LauncherIconController(backend, this, StandardTestDispatcher(testScheduler))
        controller.select("night")
        advanceUntilIdle()
        controller.onBackground()
        advanceUntilIdle()
        assertEquals(setOf("default", "night"), backend.enabled)
        assertTrue(controller.state.value.pending)
        assertNotNull(controller.state.value.error)
        backend.failDisable = false
        controller.onForeground()
        controller.onBackground()
        advanceUntilIdle()
        assertEquals(setOf("night"), backend.enabled)
        assertNull(controller.state.value.error)
    }

    @Test fun unavailableSelectionIsIgnored() = runTest {
        val backend = FakeBackend()
        val controller = LauncherIconController(backend, this, StandardTestDispatcher(testScheduler))
        controller.select("missing")
        controller.onBackground()
        advanceUntilIdle()
        assertEquals("default", backend.saved)
        assertEquals(0, backend.applyCalls)
    }

    @Test fun legacySwitchNeverDisablesLastEntryEvenIfEnablingFails() {
        val enabled = mutableSetOf("default")
        assertThrows(IOException::class.java) {
            switchLauncherIconSafely("night", setOf("default", "night"), enabled.toSet(), change = { item ->
                if (item.enabled) throw IOException("Cannot enable")
                enabled.remove(item.id)
            })
        }
        assertEquals(setOf("default"), enabled)
        switchLauncherIconSafely("night", setOf("default", "night"), enabled.toSet(), change = { item ->
            if (item.enabled) enabled.add(item.id) else enabled.remove(item.id)
            assertTrue("Every intermediate state must be launchable", enabled.isNotEmpty())
        })
        assertEquals(setOf("night"), enabled)
    }

    @Test fun modernAndroidUsesOneAtomicTransactionAndSkipsAlreadyAppliedSelection() {
        val enabled = mutableSetOf("default")
        var transactions = 0
        val atomic: (List<LauncherIconChange>) -> Unit = { changes ->
            transactions++
            changes.forEach { if (it.enabled) enabled.add(it.id) else enabled.remove(it.id) }
        }
        repeat(2) {
            switchLauncherIconSafely("night", setOf("default", "night"), enabled.toSet(), change = { fail("Atomic switch must not call the legacy setter") }, atomicChange = atomic)
        }
        assertEquals(1, transactions)
        assertEquals(setOf("night"), enabled)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun runQueued() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    private class FakeBackend : LauncherIconBackend {
        var saved = "default"
        val enabled = mutableSetOf("default")
        var failSave = false
        var failDisable = false
        var applyCalls = 0
        private val icons = listOf("default", "ink", "night").map { LauncherIcon(it, it, 1, "Icon_$it") }
        override fun load() = LauncherIconState(icons, saved, enabled.toSet())
        override fun saveSelection(id: String) {
            if (failSave) throw IOException("Cannot save")
            saved = id
        }
        override fun enabledIds() = enabled.toSet()
        override fun apply(id: String): Set<String> {
            applyCalls++
            switchLauncherIconSafely(id, icons.mapTo(mutableSetOf()) { it.id }, enabled.toSet(), change = {
                if (it.enabled) enabled.add(it.id) else {
                    if (failDisable) throw IOException("Cannot disable")
                    enabled.remove(it.id)
                }
            })
            return enabled.toSet()
        }
    }
}

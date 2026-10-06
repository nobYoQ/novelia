@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package cc.novelia.app.startup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StartupLoaderTest {
    @Test fun reportsRealStepsAndWaitsForSuccessfulRetryBeforeOpeningLibrary() = runTest {
        val loader = StartupLoader()
        val observed = mutableListOf<StartupStep>()
        var attempts = 0
        val load = launch {
            loader.load(listOf(
                StartupStep.LIBRARY to { observed += loader.progress.value.step },
                StartupStep.KEYWORDS to {
                    observed += loader.progress.value.step
                    if(++attempts == 1) error("synthetic startup failure")
                },
                StartupStep.INTERFACE to { observed += loader.progress.value.step }
            ))
        }
        runCurrent()
        assertFalse(load.isCompleted)
        assertTrue(loader.progress.value.failed)
        assertFalse(loader.progress.value.ready)
        assertEquals(StartupStep.KEYWORDS, loader.progress.value.step)
        loader.retry()
        loader.retry()
        runCurrent()
        assertTrue(load.isCompleted)
        assertTrue(loader.progress.value.ready)
        assertFalse(loader.progress.value.failed)
        assertEquals(listOf(StartupStep.LIBRARY, StartupStep.KEYWORDS, StartupStep.LIBRARY, StartupStep.KEYWORDS, StartupStep.INTERFACE), observed)
    }

    @Test fun cancellationDoesNotBecomeRetryableStartupFailure() = runTest {
        val loader = StartupLoader()
        val load = launch { loader.load(listOf(StartupStep.LIBRARY to { throw CancellationException() })) }
        runCurrent()
        assertTrue(load.isCancelled)
        assertFalse(loader.progress.value.failed)
        assertFalse(loader.progress.value.ready)
    }
}

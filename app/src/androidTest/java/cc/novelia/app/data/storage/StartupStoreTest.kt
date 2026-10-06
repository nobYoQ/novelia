package cc.novelia.app.data.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupStoreTest {
    @Test fun startupNoOpsAndUnsyncedChangesDoNotLoadSyncPreferences(): Unit = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "startup-store-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        withContext(Dispatchers.IO) {
            var preferenceReads = 0
            val store = LocalStore(isolated) { preferenceReads++; false }
            val initial = store.state.value
            store.update { it }
            store.update { it.copy() }
            assertSame(initial, store.state.value)
            assertEquals(0, preferenceReads)
            store.update { it.copy(readerTapTutorialSeen = true) }
            assertEquals(0, preferenceReads)
            store.update { it.copy(theme = "dark") }
            assertEquals(1, preferenceReads)
            store.flush()
            assertEquals("dark", LocalStore(isolated).state.value.theme)
        }
    }
}

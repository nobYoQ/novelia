package cc.novelia.app.data.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.*
import cc.novelia.app.files.importLocalDocument
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfCopyCleanupTest {
    @Test fun defaultRemovalKeepsCopyAndReimportRestoresShelfWhileEnabledRemovalDeletesOnlyCopy() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "shelf-cleanup-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        val store = LocalStore(isolated)
        val source = File(directory, "原文件.txt").apply { writeText("第一章\n测试正文", Charsets.UTF_8) }
        val first = importLocalDocument(store, source)
        assertFalse(store.removeShelfBook(first.ref))
        assertTrue(store.documentSource(first.ref.id, "txt").isFile)
        val again = importLocalDocument(store, source)
        assertEquals(first.ref, again.ref)
        assertFalse(again.imported)
        assertTrue(store.state.value.books.any { it.book.ref == first.ref })
        store.update { it.copy(deleteLocalCopyOnShelfRemoval = true) }
        assertTrue(store.removeShelfBook(first.ref))
        assertFalse(store.documentSource(first.ref.id, "txt").exists())
        assertTrue(source.isFile)
        store.flush()
        assertTrue(LocalStore(isolated).state.value.deleteLocalCopyOnShelfRemoval)
    }
}

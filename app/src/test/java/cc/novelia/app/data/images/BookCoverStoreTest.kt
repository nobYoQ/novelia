package cc.novelia.app.data.images

import cc.novelia.app.data.model.BookRef
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookCoverStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aLateDownloadCannotOverwriteANewerCoverChoice() = runBlocking {
        val store = BookCoverStore(folder.newFolder("covers"), folder.newFolder("staging")) {}
        val book = BookRef("syosetu", "1")
        val beforeDownload = store.revision(book)
        store.replace(book) { "new choice".byteInputStream() }
        val late = runCatching { store.replace(book, beforeDownload) { "old download".byteInputStream() } }
        assertTrue(late.exceptionOrNull() is IllegalStateException)
        assertEquals("new choice", store.observe(book).first()?.file?.readText())
    }

    @Test fun replacementIsIsolatedPersistentAndReversibleEvenAfterAnInvalidPick() = runBlocking {
        val directory = File(folder.root, "covers")
        val cache = folder.newFolder("staging")
        val store = BookCoverStore(directory, cache) { require(it.readText() != "invalid") }
        val book = BookRef("wenku", "1")
        val other = BookRef("syosetu", "1")
        suspend fun image() = checkNotNull(store.observe(book).first())

        assertNull(store.observe(book).first())
        store.replace(book) { "initial".byteInputStream() }
        val initial = image()
        assertEquals("initial", initial.file.readText())
        assertNull(store.observe(other).first())

        assertTrue(runCatching { store.replace(book) { "invalid".byteInputStream() } }.isFailure)
        assertEquals(initial, image())
        assertEquals("initial", image().file.readText())
        store.replace(book) { "replacement".byteInputStream() }
        assertNotEquals(initial.revision, image().revision)
        assertEquals("replacement", BookCoverStore(directory, cache).observe(book).first()?.file?.readText())
        assertTrue(cache.list().orEmpty().isEmpty())

        store.restoreDefault(book)
        assertNull(store.observe(book).first())
        assertNull(BookCoverStore(directory, cache).observe(book).first())
    }
}

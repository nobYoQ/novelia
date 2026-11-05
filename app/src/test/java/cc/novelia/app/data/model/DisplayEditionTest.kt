package cc.novelia.app.data.model

import cc.novelia.app.BuildConfig
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DisplayEditionTest {
    @Test fun restoredGlobalAndBookPreferencesUseThisEditionsMode() {
        val global = ReaderSettings(fontSize = 18f)
        val book = ReaderSettings(fontSize = 20f).withEInkMode(true).copy(fontSize = 26f).withEInkMode(false)
        val state = LibraryState(reader = global, bookSettings = mapOf("book" to book))
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(state))
        val active = restored.readerForDisplay("book")
        assertEquals(BuildConfig.EINK_ONLY, active.eInkMode)
        assertEquals(if (BuildConfig.EINK_ONLY) 26f else 20f, active.fontSize)
        assertEquals(restored.readerForDisplay(), restored.readerForDisplay("another"))
        if (BuildConfig.EINK_ONLY) {
            assertTrue(restored.readerForDisplay().volumeKeys)
            assertEquals("auto", restored.readerForDisplay().paginationMode)
        }
        val edited = restored.withDisplayReader("book", active.copy(fontSize = 28f, paginationMode = "row"))
        assertEquals(global, edited.reader)
        assertFalse(edited.bookSettings.getValue("book").eInkMode)
        assertEquals(28f, edited.readerForDisplay("book").fontSize)
        assertEquals("row", edited.readerForDisplay("book").paginationMode)
        if (BuildConfig.EINK_ONLY) assertEquals(20f, edited.bookSettings.getValue("book").fontSize)
    }
}

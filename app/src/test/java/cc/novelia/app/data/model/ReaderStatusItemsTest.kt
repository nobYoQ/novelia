package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReaderStatusItemsTest {
    @Test fun legacyAndPerBookSettingsKeepChoicesAcrossModesAndBackups() {
        assertEquals(ReaderStatusItems.defaults, appJson.decodeFromString<ReaderSettings>("{}").readingStatusItems)
        val custom = ReaderSettings(readingStatusItems = listOf("battery", "progress", "time"))
        val library = LibraryState(bookSettings = mapOf("local/a" to custom))
        assertEquals(library, appJson.decodeFromString<LibraryState>(appJson.encodeToString(library)))
        assertEquals(SettingsBackup(reader = custom), appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(SettingsBackup(reader = custom))))
        assertEquals(custom.readingStatusItems, custom.withEInkMode(true).withEInkMode(false).readingStatusItems)
        assertTrue(custom.copy(showReadingStatusBar = false).visibleReadingStatusItems.isEmpty())
        assertTrue(custom.copy(readingStatusItems = emptyList()).visibleReadingStatusItems.isEmpty())
        val pages = custom.copy(readingStatusItems = listOf("pages"))
        assertTrue(pages.visibleReadingStatusItems.isEmpty())
        assertEquals(listOf("pages"), pages.copy(paginationMode = "auto").visibleReadingStatusItems)
    }
    @Test fun invalidListsFallBackAndNarrowOrLargeTextLayoutsReserveEnoughRows() {
        listOf(listOf("time", "time"), listOf("unknown")).forEach { items ->
            val settings = ReaderSettings(readingStatusItems = items)
            assertFalse(settings.validReadingStatusItems)
            assertEquals(ReaderStatusItems.defaults, settings.resolvedReadingStatusItems)
        }
        assertEquals(4, ReaderStatusItems.columns(390f, 1f))
        assertEquals(2, ReaderStatusItems.layout(ReaderStatusItems.labels.keys.toList(), ReaderStatusItems.columns(390f, 1f)).size)
        assertEquals(1, ReaderStatusItems.columns(320f, 2f))
        assertEquals(5, ReaderStatusItems.layout(ReaderStatusItems.labels.keys.toList(), ReaderStatusItems.columns(320f, 2f)).size)
        assertTrue(ReaderStatusItems.layout(emptyList(), 1).isEmpty())
        assertEquals(1, ReaderStatusItems.columns(0f, 2f))
    }

    @Test fun crowdedTitlesHaveAFullRowAndOtherItemsKeepTheirOrder() {
        val all = listOf("progress", "chapter", "battery", "pages", "time")
        assertEquals(listOf(listOf("chapter"), listOf("progress", "battery", "pages", "time")),
            ReaderStatusItems.layout(all, ReaderStatusItems.columns(390f, 1f)))
        assertEquals(listOf(listOf("chapter"), listOf("time", "pages", "progress")),
            ReaderStatusItems.layout(ReaderStatusItems.defaults, ReaderStatusItems.columns(390f, 1f)))
        val compact = listOf("time", "chapter", "progress")
        assertEquals(listOf(compact), ReaderStatusItems.layout(compact, 4))
        assertEquals(listOf(all), ReaderStatusItems.layout(all, ReaderStatusItems.columns(840f, 1f)))
        assertEquals(listOf(listOf("chapter")), ReaderStatusItems.layout(listOf("chapter"), 1))
        assertEquals(5, ReaderStatusItems.layout(all, ReaderStatusItems.columns(320f, 2f)).size)
        assertTrue(ReaderStatusItems.layout(emptyList(), 1).isEmpty())
        assertEquals(listOf(listOf("battery", "time"), listOf("pages")), ReaderStatusItems.layout(listOf("battery", "time", "pages"), 2))
    }
}

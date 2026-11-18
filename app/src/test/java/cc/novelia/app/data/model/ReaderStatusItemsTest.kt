package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReaderStatusItemsTest {
    @Test fun legacyAndPerBookSettingsKeepChoicesAcrossModesAndBackups() {
        assertEquals(ReaderStatusItems.defaults, appJson.decodeFromString<ReaderSettings>("{}").readingStatusItems)
        assertFalse(appJson.decodeFromString<ReaderSettings>("{}").showTopReadingStatusBar)
        val custom = ReaderSettings(readingStatusItems = listOf("battery", "progress", "book"), showTopReadingStatusBar = true,
            topReadingStatusItems = listOf("book", "time", "pages"))
        val library = LibraryState(bookSettings = mapOf("local/a" to custom))
        assertEquals(library, appJson.decodeFromString<LibraryState>(appJson.encodeToString(library)))
        assertEquals(SettingsBackup(reader = custom), appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(SettingsBackup(reader = custom))))
        assertEquals(custom.readingStatusItems, custom.withEInkMode(true).withEInkMode(false).readingStatusItems)
        assertTrue(custom.copy(showReadingStatusBar = false).visibleReadingStatusItems.isEmpty())
        assertTrue(custom.copy(readingStatusItems = emptyList()).visibleReadingStatusItems.isEmpty())
        val pages = custom.copy(readingStatusItems = listOf("pages"))
        assertTrue(pages.visibleReadingStatusItems.isEmpty())
        assertEquals(listOf("pages"), pages.copy(paginationMode = "auto").visibleReadingStatusItems)
        assertEquals(listOf("book", "time"), custom.visibleTopReadingStatusItems)
        assertEquals(custom.topReadingStatusItems, custom.copy(paginationMode = "auto").visibleTopReadingStatusItems)
        assertTrue(custom.copy(showTopReadingStatusBar = false).visibleTopReadingStatusItems.isEmpty())
        val invalidTop = custom.copy(topReadingStatusItems = listOf("book", "book"))
        assertFalse(invalidTop.validReadingStatusItems)
        assertEquals(ReaderStatusItems.topDefaults, invalidTop.resolvedTopReadingStatusItems)
        assertEquals(custom.readingStatusItems, invalidTop.resolvedReadingStatusItems)
    }
    @Test fun invalidListsFallBackAndNarrowOrLargeTextLayoutsReserveEnoughRows() {
        listOf(listOf("time", "time"), listOf("unknown")).forEach { items ->
            val settings = ReaderSettings(readingStatusItems = items)
            assertFalse(settings.validReadingStatusItems)
            assertEquals(ReaderStatusItems.defaults, settings.resolvedReadingStatusItems)
        }
        assertEquals(5, ReaderStatusItems.columns(390f, 1f))
        assertEquals(2, ReaderStatusItems.layout(ReaderStatusItems.labels.keys.toList(), ReaderStatusItems.columns(390f, 1f)).size)
        assertEquals(2, ReaderStatusItems.columns(320f, 2f))
        assertEquals(4, ReaderStatusItems.layout(ReaderStatusItems.labels.keys.toList(), ReaderStatusItems.columns(320f, 2f)).size)
        assertTrue(ReaderStatusItems.layout(emptyList(), 1).isEmpty())
        assertEquals(1, ReaderStatusItems.columns(0f, 2f))
    }

    @Test fun wrappingKeepsTheConfiguredOrderIncludingTitles() {
        val all = listOf("progress", "chapter", "battery", "pages", "time")
        assertEquals(listOf(listOf("progress", "chapter"), listOf("battery", "pages", "time")),
            ReaderStatusItems.layout(all, ReaderStatusItems.columns(390f, 1f)))
        assertEquals(listOf(listOf("time", "chapter"), listOf("pages", "progress")),
            ReaderStatusItems.layout(ReaderStatusItems.defaults, ReaderStatusItems.columns(390f, 1f)))
        val compact = listOf("time", "chapter", "progress")
        assertEquals(listOf(compact), ReaderStatusItems.layout(compact, 5))
        assertEquals(listOf(all), ReaderStatusItems.layout(all, ReaderStatusItems.columns(840f, 1f)))
        assertEquals(listOf(listOf("chapter")), ReaderStatusItems.layout(listOf("chapter"), 1))
        assertEquals(4, ReaderStatusItems.layout(all, ReaderStatusItems.columns(320f, 2f)).size)
        assertTrue(ReaderStatusItems.layout(emptyList(), 1).isEmpty())
        assertEquals(listOf(listOf("battery", "time"), listOf("pages")), ReaderStatusItems.layout(listOf("battery", "time", "pages"), 2))
    }

    @Test fun fourShortItemsStayTogetherOnPhoneWidthsWithOrWithoutTheTitle() {
        val shortItems = listOf("time", "battery", "pages", "progress")
        for(width in listOf(320f, 360f, 390f)) {
            val columns = ReaderStatusItems.columns(width, 1f)
            assertEquals(listOf(shortItems), ReaderStatusItems.layout(shortItems, columns))
            assertEquals(listOf(shortItems, listOf("chapter")), ReaderStatusItems.layout(shortItems + "chapter", columns))
        }
    }
}

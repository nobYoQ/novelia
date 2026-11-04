package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReaderPaginationModesTest {
    @Test fun partialLinePreferenceDefaultsOnAndSurvivesBookOverridesAndBackups() {
        val defaults = appJson.decodeFromString<ReaderSettings>("""{"paginationMode":"row"}""")
        assertTrue(defaults.rowShowPartialLines)
        val hidden = defaults.copy(rowShowPartialLines = false)
        val library = LibraryState(reader = defaults, bookSettings = mapOf("local/book" to hidden))
        assertEquals(library, appJson.decodeFromString<LibraryState>(appJson.encodeToString(library)))
        val backup = SettingsBackup(reader = hidden)
        assertEquals(backup, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)))
        assertFalse(hidden.withPaginationMode("scroll").withPaginationMode("auto").withPaginationMode("row").rowShowPartialLines)
        assertEquals("显示底部不完整行", cc.novelia.app.reader.readerSettingsDifferences(hidden, defaults).single().label)
    }

    @Test fun partialLinePreferenceRestoresIndependentModesAndInheritsMissingLegacyFields() {
        val normal = ReaderSettings(paginationMode = "row", rowShowPartialLines = false)
        val ink = normal.withEInkMode(true)
        assertFalse(ink.rowShowPartialLines)
        val saved = ink.copy(rowShowPartialLines = true).withEInkMode(false)
        val restored = appJson.decodeFromString<ReaderSettings>(appJson.encodeToString(saved))
        assertFalse(restored.rowShowPartialLines)
        assertTrue(restored.withEInkMode(true).rowShowPartialLines)
        assertFalse(restored.withEInkMode(true).withEInkMode(false).rowShowPartialLines)
        val legacy = appJson.decodeFromString<ReaderSettings>("""{"rowShowPartialLines":false,"eInkPreferences":{"paginationMode":"row"}}""")
        assertFalse(legacy.withEInkMode(true).rowShowPartialLines)
    }

    @Test fun existingSettingsKeepTheirModeAndRowModeIsExplicit() {
        val old = appJson.decodeFromString<ReaderSettings>("""{"paginationMode":"auto","scrollPageTurn":true}""")
        assertTrue(old.staticPagination)
        assertFalse(old.rowPagination)
        assertFalse(ReaderSettings().rowPagination)
        val row = ReaderSettings().withPaginationMode("row")
        assertTrue(row.rowPagination && row.staticPagination && row.horizontalPageTurn)
        assertEquals(row, appJson.decodeFromString<ReaderSettings>(appJson.encodeToString(row)))
        val fixed = row.withPaginationMode("auto")
        assertTrue(fixed.staticPagination)
        assertFalse(fixed.rowPagination || fixed.scrollPageTurn)
    }

    @Test fun rowModeSurvivesIndependentEInkSettingsAndRestoresToNormalMode() {
        val normal = ReaderSettings().withPaginationMode("row")
        val eInk = normal.withEInkMode(true)
        assertEquals("auto", eInk.paginationMode)
        assertEquals(normal, eInk.withEInkMode(false).copy(beforeEInk = null, eInkPreferences = null))
        val custom = eInk.withPaginationMode("row")
        assertEquals("row", custom.withEInkMode(false).withEInkMode(true).paginationMode)
    }
}

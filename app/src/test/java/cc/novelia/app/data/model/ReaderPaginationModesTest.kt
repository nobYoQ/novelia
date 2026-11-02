package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReaderPaginationModesTest {
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

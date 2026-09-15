package cc.novelia.app

import cc.novelia.app.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReaderPreferencesTest {
    @Test fun toolbarTransparencyMigratesAndPersistsAcrossModesBooksAndBackups() {
        assertEquals(.25f, appJson.decodeFromString<ReaderSettings>("{}").toolbarTransparency, 0f)
        for (transparency in listOf(0f, .25f, .6f, 1f)) {
            val settings = ReaderSettings(toolbarTransparency = transparency)
            assertEquals(transparency, settings.withEInkMode(true).withEInkMode(false).toolbarTransparency, 0f)
            assertEquals(transparency, settings.withPaginationMode("auto").toolbarTransparency, 0f)
            val backup = SettingsBackup(reader = settings)
            assertEquals(backup, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)))
            val library = LibraryState(reader = settings, bookSettings = mapOf("local/book" to settings.copy(toolbarTransparency = .8f)))
            assertEquals(library, appJson.decodeFromString<LibraryState>(appJson.encodeToString(library)))
        }
    }

    @Test fun importedToolbarTransparencyStaysWithinValidRenderingLimits() {
        assertEquals(0f, ReaderSettings(toolbarTransparency = -1f).resolvedToolbarTransparency, 0f)
        assertEquals(1f, ReaderSettings(toolbarTransparency = 2f).resolvedToolbarTransparency, 0f)
        assertEquals(.25f, ReaderSettings(toolbarTransparency = Float.NaN).resolvedToolbarTransparency, 0f)
        assertEquals(.25f, ReaderSettings(toolbarTransparency = Float.POSITIVE_INFINITY).resolvedToolbarTransparency, 0f)
    }

    @Test fun eInkToggleRestoresBothProfilesWithoutOverwritingOtherPreferences() {
        val normal = ReaderSettings(paginationMode = "scroll", showPageButtons = false, scrollPageTurn = false,
            horizontalPageTurn = true, volumeKeys = false, theme = "paper")
        val enabled = normal.withEInkMode(true)
        val customEInk = enabled.copy(scrollPageTurn = true, horizontalPageTurn = false, showPageButtons = false,
            volumeKeys = true, theme = "dark", fontSize = 24f)
        val restored = customEInk.withEInkMode(false)
        assertEquals(normal.paginationMode, restored.paginationMode)
        assertEquals(normal.scrollPageTurn, restored.scrollPageTurn)
        assertEquals(normal.horizontalPageTurn, restored.horizontalPageTurn)
        assertEquals(normal.showPageButtons, restored.showPageButtons)
        assertEquals(normal.volumeKeys, restored.volumeKeys)
        assertEquals("dark", restored.theme)
        assertEquals(24f, restored.fontSize, 0f)
        assertNull(restored.beforeEInk)
        val secondNormal = restored.copy(paginationMode = "auto", showPageButtons = true, volumeKeys = true)
        val reenabled = secondNormal.withEInkMode(true)
        assertEquals(customEInk.paginationMode, reenabled.paginationMode)
        assertEquals(customEInk.scrollPageTurn, reenabled.scrollPageTurn)
        assertEquals(customEInk.horizontalPageTurn, reenabled.horizontalPageTurn)
        assertEquals(customEInk.showPageButtons, reenabled.showPageButtons)
        assertEquals(customEInk.volumeKeys, reenabled.volumeKeys)
        assertEquals(secondNormal, reenabled.withEInkMode(false))
    }

    @Test fun eInkSnapshotsSurviveBackupAndStaySeparateForEachBook() {
        val normal = ReaderSettings(paginationMode = "scroll", showPageButtons = true)
        val book = ReaderSettings(paginationMode = "auto", horizontalPageTurn = true, showPageButtons = false)
        val enabled = normal.withEInkMode(true)
        val backup = SettingsBackup(reader = enabled)
        val decoded = appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup))
        val disabled = decoded.reader.withEInkMode(false)
        assertEquals(normal.paginationMode, disabled.paginationMode)
        assertEquals(normal.showPageButtons, disabled.showPageButtons)
        val library = LibraryState(reader = disabled, bookSettings = mapOf("local/book" to book.withEInkMode(true)))
        val reloaded = appJson.decodeFromString<LibraryState>(appJson.encodeToString(library))
        assertEquals(library, reloaded)
        val restoredBook = reloaded.bookSettings.getValue("local/book").withEInkMode(false)
        assertEquals(book.paginationMode, restoredBook.paginationMode)
        assertEquals(book.showPageButtons, restoredBook.showPageButtons)
        assertEquals("scroll", reloaded.reader.paginationMode)
        assertEquals(enabled.paginationMode, reloaded.reader.withEInkMode(true).paginationMode)
    }

    @Test fun repeatedToggleValuesDoNotOverwriteSnapshotsAndLegacyModeReturnsToScrolling() {
        val normal = ReaderSettings()
        assertSame(normal, normal.withEInkMode(false))
        val enabled = normal.withEInkMode(true).copy(showPageButtons = false)
        assertSame(enabled, enabled.withEInkMode(true))
        val restored = enabled.withEInkMode(false)
        assertFalse(restored.staticPagination)
        assertFalse(restored.showPageButtons)
        assertSame(restored, restored.withEInkMode(false))
        val legacy = appJson.decodeFromString<ReaderSettings>("""{"eInkMode":true,"paginationMode":"auto","showPageButtons":true,"volumeKeys":true}""")
        val legacyOff = legacy.withEInkMode(false)
        assertFalse(legacyOff.staticPagination)
        assertFalse(legacyOff.volumeKeys)
        assertTrue(legacyOff.withEInkMode(true).staticPagination)
        assertTrue(legacyOff.withEInkMode(true).showPageButtons)
    }

    @Test fun enablingEInkPreservesThemeAndEnablesBothGestures() {
        val settings = ReaderSettings(theme = "dark").withEInkMode(true)
        assertEquals("dark", settings.theme)
        assertFalse(settings.monochrome)
        assertTrue(settings.scrollPageTurn)
        assertTrue(settings.horizontalPageTurn)
        assertTrue(settings.staticPagination)
        assertTrue(settings.showPageButtons)
        assertTrue(settings.copy(scrollPageTurn = false, horizontalPageTurn = false).staticPagination)
        assertTrue(ReaderSettings(monochrome = true).withEInkMode(true).monochrome)
    }

    @Test fun paginationModeIsIndependentOfGesturesAndButtons() {
        val flowing = ReaderSettings()
        assertFalse(flowing.staticPagination)
        assertFalse(flowing.showPageButtons)
        val automatic = flowing.withPaginationMode("auto")
        assertTrue(automatic.staticPagination)
        assertTrue(automatic.scrollPageTurn && automatic.horizontalPageTurn)
        assertFalse(automatic.eInkMode)
        val buttonsOnly = automatic.copy(scrollPageTurn = false, horizontalPageTurn = false, showPageButtons = true)
        assertTrue(buttonsOnly.staticPagination)
        assertTrue(buttonsOnly.withPaginationMode("scroll").showPageButtons)
        assertFalse(automatic.withPaginationMode("scroll").staticPagination)
        assertTrue(automatic.withPaginationMode("scroll").horizontalPageTurn)
        assertFalse(ReaderSettings(eInkMode = true).withPaginationMode("scroll").staticPagination)
        assertEquals(buttonsOnly, appJson.decodeFromString<ReaderSettings>(appJson.encodeToString(buttonsOnly)))
    }

    @Test fun olderEInkSettingsKeepGesturesAndNewChoicesRoundTrip() {
        val migrated = appJson.decodeFromString<ReaderSettings>("""{"eInkMode":true,"theme":"paper"}""")
        assertTrue(migrated.scrollPageTurn)
        assertTrue(migrated.horizontalPageTurn)
        assertFalse(migrated.monochrome)
        assertTrue(migrated.staticPagination)
        assertTrue(migrated.showPageButtons)
        assertTrue(appJson.decodeFromString<ReaderSettings>("""{"paged":true}""").showPageButtons)
        assertTrue(appJson.decodeFromString<ReaderSettings>("""{"horizontalPageTurn":true,"paged":false}""").showPageButtons)
        val hidden = migrated.copy(showPageButtons = false).withPaginationMode("scroll")
        assertEquals(hidden, appJson.decodeFromString<ReaderSettings>(appJson.encodeToString(hidden)))
        assertFalse(hidden.staticPagination)
        assertFalse(hidden.showPageButtons)
        val backup = SettingsBackup(reader = migrated.copy(scrollPageTurn = false, monochrome = true), autoCollapseCloudFilters = false)
        assertEquals(backup, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)))
        val legacy = appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup))
        assertEquals("monochrome", legacy.reader.resolvedTheme)
        for (theme in listOf("system", "paper", "light", "dark", "monochrome")) {
            val selected = legacy.copy(reader = legacy.reader.withTheme(theme))
            assertEquals(theme, selected.reader.resolvedTheme)
            assertFalse(selected.reader.monochrome)
            assertEquals(selected, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(selected)))
        }
        assertTrue(appJson.decodeFromString<SettingsBackup>("{}").autoCollapseCloudFilters)
    }
}

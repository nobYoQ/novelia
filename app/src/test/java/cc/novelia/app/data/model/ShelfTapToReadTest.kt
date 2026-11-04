package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.SyncDomain
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ShelfTapToReadTest {
    @Test fun newAndLegacySettingsKeepTapToReadDisabled() {
        assertFalse(LibraryState().shelfTapToRead)
        assertFalse(SettingsBackup().shelfTapToRead)
        assertFalse(appJson.decodeFromString<LibraryState>("{}").shelfTapToRead)
        assertFalse(appJson.decodeFromString<SettingsBackup>("{}").shelfTapToRead)
    }

    @Test fun preferenceSurvivesLibraryAndSettingsBackupRoundTrips() {
        listOf(false, true).forEach { enabled ->
            val state = LibraryState(shelfTapToRead = enabled)
            assertEquals(enabled, appJson.decodeFromString<LibraryState>(appJson.encodeToString(state)).shelfTapToRead)
            val backup = SettingsBackup(shelfTapToRead = enabled)
            assertEquals(enabled, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)).shelfTapToRead)
        }
    }

    @Test fun tapToReadRemainsLocalToEachDevice() {
        val before = LibraryState()
        val after = before.copy(shelfTapToRead = true)
        assertTrue(WebDavProjection.affectedLibraryDomains(before, after).isEmpty())
        assertEquals(WebDavProjection.library(before, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true),
            WebDavProjection.library(after, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true))
    }
}

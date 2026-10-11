package cc.novelia.app.data.appupdate

import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SettingsBackup
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.SyncDomain
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class AppUpdateSettingsTest {
    @Test fun oldLibraryAndSettingsEnableStartupChecksByDefault() {
        assertTrue(appJson.decodeFromString<LibraryState>("{}").autoCheckAppUpdates)
        assertTrue(appJson.decodeFromString<SettingsBackup>("{}").autoCheckAppUpdates)
    }

    @Test fun disabledPreferenceSurvivesLibraryAndSettingsBackupRoundTrips() {
        val state = LibraryState(autoCheckAppUpdates = false)
        assertFalse(appJson.decodeFromString<LibraryState>(appJson.encodeToString(state)).autoCheckAppUpdates)
        val backup = SettingsBackup(autoCheckAppUpdates = false)
        assertFalse(appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)).autoCheckAppUpdates)
    }

    @Test fun startupChecksRemainLocalToEachDevice() {
        val before = LibraryState()
        val after = before.copy(autoCheckAppUpdates = false)
        assertTrue(WebDavProjection.affectedLibraryDomains(before, after).isEmpty())
        assertEquals(WebDavProjection.library(before, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true),
            WebDavProjection.library(after, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true))
    }
}

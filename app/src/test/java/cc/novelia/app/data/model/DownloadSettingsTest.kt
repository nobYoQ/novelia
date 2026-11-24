package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DownloadSettingsTest {
    @Test fun oldSettingsDefaultToTwoAndSelectedLimitSurvivesBackupAndRestore() {
        assertEquals(2, appJson.decodeFromString<SettingsBackup>("{}").downloadConcurrency)
        val selected = LibraryState(downloadConcurrency = 5)
        val saved = appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(selected.settingsBackup()))
        saved.requireValid()
        assertEquals(5, saved.applyTo(LibraryState()).downloadConcurrency)
        assertThrows(IllegalArgumentException::class.java) { saved.copy(downloadConcurrency = 0).requireValid() }
    }
}

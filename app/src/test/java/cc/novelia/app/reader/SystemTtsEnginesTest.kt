package cc.novelia.app.reader

import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SettingsBackup
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SystemTtsEnginesTest {
    private val engines = listOf(SystemTtsEngine("test.system", "系统语音"), SystemTtsEngine("test.multi", "MultiTTS"))

    @Test fun engineSelectionUsesInstalledPackagesAndFallsBackToSystemDefault() {
        assertNull(selectedSystemTtsEngine(null, engines))
        assertEquals("test.multi", selectedSystemTtsEngine("test.multi", engines))
        assertNull(selectedSystemTtsEngine("test.removed", engines))
        assertNull(selectedSystemTtsEngine("test.multi", emptyList()))
    }

    @Test fun deviceEnginePreferenceSurvivesBackupWithoutChangingWebDavSettings() {
        val legacy = appJson.decodeFromString<LibraryState>("{}")
        assertNull(legacy.ttsEnginePackage)
        assertNull(appJson.decodeFromString<SettingsBackup>("{}").ttsEnginePackage)
        val selected = legacy.copy(ttsEnginePackage = "test.multi")
        assertEquals("test.multi", appJson.decodeFromString<LibraryState>(appJson.encodeToString(selected)).ttsEnginePackage)
        val backup = SettingsBackup(ttsEnginePackage = "test.multi")
        assertEquals(backup, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)))
        assertTrue(WebDavProjection.affectedLibraryDomains(legacy, selected).isEmpty())
        assertEquals(WebDavProjection.library(legacy), WebDavProjection.library(selected))
    }
}

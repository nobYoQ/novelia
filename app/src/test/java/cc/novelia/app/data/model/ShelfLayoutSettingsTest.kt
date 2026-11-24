package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ShelfLayoutSettingsTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    @Test fun oldLibrariesKeepListsAndTheThreeLayoutsRoundTripIndependently() {
        val legacy = json.decodeFromString<LibraryState>("{}")
        assertFalse(legacy.shelfGrid || legacy.localFilesGrid || legacy.cloudShelfGrid)
        for(mask in 0..7) {
            val state = legacy.copy(shelfGrid = mask and 1 != 0, localFilesGrid = mask and 2 != 0, cloudShelfGrid = mask and 4 != 0)
            val restored = json.decodeFromString<LibraryState>(json.encodeToString(state))
            assertEquals(state, restored)
            val backup = SettingsBackup(shelfGrid = state.shelfGrid, localFilesGrid = state.localFilesGrid, cloudShelfGrid = state.cloudShelfGrid)
            assertEquals(backup, json.decodeFromString<SettingsBackup>(json.encodeToString(backup)))
            assertTrue(WebDavProjection.affectedLibraryDomains(legacy, state).isEmpty())
            assertEquals(WebDavProjection.library(legacy), WebDavProjection.library(state))
        }
    }

    @Test fun gridColumnPreferenceDefaultsToAutomaticAndRoundTripsLocally() {
        val legacy = json.decodeFromString<LibraryState>("{}")
        assertEquals(ShelfGridDensity.Auto, legacy.shelfGridDensity)
        assertEquals(ShelfGridDensity.Auto, json.decodeFromString<SettingsBackup>("{}").shelfGridDensity)
        for(density in ShelfGridDensity.entries) {
            val state = legacy.copy(shelfGridDensity = density)
            assertEquals(density, json.decodeFromString<LibraryState>(json.encodeToString(state)).shelfGridDensity)
            val backup = json.decodeFromString<SettingsBackup>(json.encodeToString(state.settingsBackup()))
            assertEquals(density, backup.applyTo(legacy).shelfGridDensity)
            assertEquals(WebDavProjection.library(legacy), WebDavProjection.library(state))
        }
    }

    @Test fun gridStylesDefaultToClassicAndRoundTripThroughSettingsBackup() {
        val legacy = appJson.decodeFromString<LibraryState>("{}")
        assertEquals(ShelfGridStyle.Classic, legacy.shelfGridStyle)
        val full = legacy.copy(shelfGridStyle = ShelfGridStyle.FullCover)
        assertEquals(ShelfGridStyle.FullCover, appJson.decodeFromString<LibraryState>(appJson.encodeToString(full)).shelfGridStyle)
        val backup = appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(full.settingsBackup()))
        assertEquals(ShelfGridStyle.FullCover, backup.applyTo(legacy).shelfGridStyle)
        assertEquals(ShelfGridStyle.Classic, appJson.decodeFromString<SettingsBackup>("{}").applyTo(full).shelfGridStyle)
        assertTrue(WebDavProjection.affectedLibraryDomains(legacy, full).isEmpty())
    }

    @Test fun oldLibrariesAndBackupsIgnoreTheRemovedShelfScrollSetting() {
        val legacy = """{"shelfControlsFollowScroll":false,"shelfGrid":true}"""
        val state = appJson.decodeFromString<LibraryState>(legacy)
        val backup = appJson.decodeFromString<SettingsBackup>(legacy)
        assertTrue(state.shelfGrid)
        assertEquals(SettingsBackup(shelfGrid = true), backup)
        assertFalse(appJson.encodeToString(state).contains("shelfControlsFollowScroll"))
        assertFalse(appJson.encodeToString(state.settingsBackup()).contains("shelfControlsFollowScroll"))
    }
}

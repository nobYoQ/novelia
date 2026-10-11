package cc.novelia.app.data.model

import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SquareCornersSettingsTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun oldDataKeepsRoundedCornersAndNewPreferenceSurvivesSerialization() {
        assertFalse(json.decodeFromString<LibraryState>("{}").squareCorners)
        assertFalse(json.decodeFromString<SettingsBackup>("{}").squareCorners)
        for(enabled in listOf(true, false)) {
            val state = LibraryState(squareCorners = enabled)
            assertEquals(enabled, json.decodeFromString<LibraryState>(json.encodeToString(state)).squareCorners)
            val backup = SettingsBackup(squareCorners = enabled)
            assertEquals(enabled, json.decodeFromString<SettingsBackup>(json.encodeToString(backup)).squareCorners)
        }
    }

    @Test fun localEasterEggDoesNotChangeTheWebDavProtocol() {
        val before = LibraryState()
        val after = before.copy(squareCorners = true)
        assertTrue(WebDavProjection.affectedLibraryDomains(before, after).isEmpty())
        assertEquals(WebDavProjection.library(before), WebDavProjection.library(after))
    }
}

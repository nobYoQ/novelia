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
        val after = before.copy(squareCorners = true, squareCornerTargets = setOf(SquareCornerTarget.Covers))
        assertTrue(WebDavProjection.affectedLibraryDomains(before, after).isEmpty())
        assertEquals(WebDavProjection.library(before), WebDavProjection.library(after))
    }

    @Test fun olderEnabledSettingsSelectEveryTargetAndCustomSelectionsSurviveBackupAndRestore() {
        val oldExport = """{"squareCorners":true}"""
        val legacy = json.decodeFromString<LibraryState>(oldExport)
        assertEquals(SquareCornerTarget.defaults, legacy.squareCornerTargets)
        assertEquals(SquareCornerTarget.defaults, json.decodeFromString<SettingsBackup>(oldExport).squareCornerTargets)
        for(targets in listOf(emptySet(), SquareCornerTarget.defaults - SquareCornerTarget.Covers)) {
            val state = legacy.copy(squareCornerTargets = targets)
            assertEquals(targets, json.decodeFromString<LibraryState>(json.encodeToString(state)).squareCornerTargets)
            val backup = json.decodeFromString<SettingsBackup>(json.encodeToString(state.settingsBackup()))
            assertEquals(targets, backup.applyTo(legacy).squareCornerTargets)
        }
    }
}

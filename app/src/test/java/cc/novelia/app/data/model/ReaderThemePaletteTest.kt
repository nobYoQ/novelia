package cc.novelia.app.data.model

import cc.novelia.app.data.backup.mergeLibraryBackup
import cc.novelia.app.data.storage.appJson
import org.junit.Assert.*
import org.junit.Test

class ReaderThemePaletteTest {
    private val amber = ReaderThemePalette(ReaderCustomColors(0x331100, 0xFFCC88, 0xDD9933), .4f)
    private val green = ReaderThemePalette(ReaderCustomColors(0x102010, 0xCCEECC, 0x66AA77), .15f)
    private val library = LibraryState(readerThemePresets = mapOf("琥珀" to amber, "森林" to green))

    @Test fun selectionOnlyUpdatesTheCurrentAppearance() {
        val reader = ReaderSettings(monochrome = true, fontSize = 28f)
        val ordinary = amber.applyTo(reader)
        assertEquals(reader.copy(theme = "custom", monochrome = false, customColors = amber.colors,
            toolbarTransparency = amber.toolbarTransparency), ordinary)
        val electronic = green.applyTo(ordinary.withEInkMode(true))
        assertEquals(amber, ReaderThemePalette.from(electronic.withEInkMode(false)))
        val night = ordinary.copy(nightReading = true)
        val edited = night.withReadingAppearance(green.applyTo(night.readingAppearance()))
        assertEquals(green, ReaderThemePalette.from(edited.readingAppearance()))
        assertEquals(amber, ReaderThemePalette.from(edited.copy(nightReading = false)))
        assertEquals(night.nightBrightness, edited.nightBrightness)
    }

    @Test fun settingsBackupsRoundTripAndLegacyFilesKeepSavedPalettes() {
        val json = appJson.encodeToString(SettingsBackup.serializer(), library.settingsBackup())
        val backup = appJson.decodeFromString<SettingsBackup>(json)
        backup.requireValid()
        assertEquals(library.readerThemePresets, backup.applyTo(LibraryState()).readerThemePresets)
        assertEquals(library.readerThemePresets, appJson.decodeFromString<SettingsBackup>("{}").applyTo(library).readerThemePresets)
        assertTrue(appJson.decodeFromString<LibraryState>("{}").readerThemePresets.isEmpty())
        assertTrue(SettingsBackup(readerThemePresets = emptyMap()).applyTo(library).readerThemePresets.isEmpty())
    }

    @Test fun archiveRestoreKeepsLocalNamesAndAddsMissingThemes() {
        val local = LibraryState(readerThemePresets = mapOf("琥珀" to green))
        val merged = mergeLibraryBackup(local, library)
        assertEquals(mapOf("琥珀" to green, "森林" to green), merged.readerThemePresets)
        assertEquals(merged.readerThemePresets, mergeLibraryBackup(merged, library).readerThemePresets)
    }

    @Test fun importRejectsInvalidNamesColorsAndTransparency() {
        listOf(mapOf("  " to amber), mapOf("a".repeat(41) to amber),
            mapOf("无效" to amber.copy(colors = amber.colors.copy(toolbar = -1))),
            mapOf("无效" to green.copy(toolbarTransparency = 1.1f))).forEach {
            assertThrows(IllegalArgumentException::class.java) { SettingsBackup(readerThemePresets = it).requireValid() }
        }
    }
}

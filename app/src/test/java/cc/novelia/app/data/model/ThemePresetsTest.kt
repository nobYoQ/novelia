package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.SyncDomain
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ThemePresetsTest {
    private val image = "a".repeat(64)
    private val forest = ThemePreset("forest", "雨林", accent = 0x007766, image = image, blur = 65)

    @Test fun monochromeIsAPortableAppModeAndPreservesPersonalThemes() {
        val presets = ThemePresets().saveAndSelect(forest)
        val state = LibraryState(theme = MONOCHROME_APP_THEME, themePresets = presets)
        val backup = appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(SettingsBackup.serializer(), state.settingsBackup()))
        backup.requireValid()
        val restored = backup.applyTo(LibraryState())
        assertEquals(MONOCHROME_APP_THEME, restored.theme)
        assertEquals(presets, restored.themePresets)
        WebDavProjection.validateField(SyncDomain.SETTINGS, "@global", "theme", JsonPrimitive(MONOCHROME_APP_THEME))
        assertEquals("system", restored.useAppPresets(presets).theme)
        assertEquals("dark", restored.copy(theme = "dark").useAppPresets(presets).theme)
    }

    @Test fun editCopyAndDeleteKeepIndependentSelectionsAndShareOnlyTheImage() {
        val copy = forest.copy(id = "evening", name = "傍晚", blur = 10)
        val both = ThemePresets().saveAndSelect(forest).saveAndSelect(copy)
        val edited = both.saveAndSelect(forest.copy(overlay = 45))
        assertEquals(listOf("forest", "evening"), edited.items.map { it.id })
        assertEquals(45, edited.active?.overlay)
        assertEquals(10, edited.items.last().blur)
        val remaining = edited.remove("forest")
        assertNull(remaining.activeId)
        assertEquals(setOf(image), remaining.images)
        assertTrue(remaining.remove("evening").images.isEmpty())
    }

    @Test fun ordinarySettingsRoundTripAndLegacyImportKeepsLocalPresets() {
        val state = LibraryState(themePresets = ThemePresets().saveAndSelect(forest), comicShelfGrid = false)
        val serialized = appJson.encodeToString(SettingsBackup.serializer(), state.settingsBackup())
        val backup = appJson.decodeFromString<SettingsBackup>(serialized)
        backup.requireValid()
        assertEquals(state.themePresets, backup.applyTo(LibraryState()).themePresets)
        assertFalse(backup.applyTo(LibraryState()).comicShelfGrid)
        assertEquals(state.themePresets, appJson.decodeFromString<SettingsBackup>("{}").applyTo(state).themePresets)
        assertNull(appJson.decodeFromString<LibraryState>("{}").themePresets.activeId)
    }

    @Test fun malformedReferencesAndUnselectedPresetsAreRejectedBeforeApplying() {
        val malformed = listOf(
            ThemePresets(listOf(forest), "absent"), ThemePresets(listOf(forest, forest)),
            ThemePresets(listOf(forest.copy(image = "../outside"))), ThemePresets(listOf(forest.copy(blur = 101))),
            ThemePresets(listOf(forest.copy(lightBackground = -1))), ThemePresets(listOf(forest.copy(cardOpacity = 0))),
            ThemePresets(listOf(forest.copy(topBar = ThemeBarGlass(blur = 101)))),
            ThemePresets(listOf(forest.copy(bottomBar = ThemeBarGlass(opacity = -1)))),
        )
        malformed.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { SettingsBackup(themePresets = invalid).requireValid() }
        }
    }

    @Test fun oldPresetsKeepSolidBarsAndNewSettingsRoundTripIndependently() {
        val old = appJson.decodeFromString<ThemePreset>("""{"id":"before","name":"原主题"}""")
        assertFalse(old.topBar.enabled)
        assertFalse(old.bottomBar.enabled)
        val edited = old.copy(topBar = ThemeBarGlass(true, 95, 20), bottomBar = ThemeBarGlass(true, 15, 70))
        val state = LibraryState(themePresets = ThemePresets().saveAndSelect(edited))
        val json = appJson.encodeToString(SettingsBackup.serializer(), state.settingsBackup())
        assertEquals(edited, appJson.decodeFromString<SettingsBackup>(json).applyTo(LibraryState()).themePresets.active)
    }
}

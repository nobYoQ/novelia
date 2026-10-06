package cc.novelia.app

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import cc.novelia.app.data.model.ReaderCustomColors
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.SettingsBackup
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.reader.readerSettingsDifferences
import cc.novelia.app.ui.theme.readerColors
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReaderCustomColorsTest {
    @Test fun oldSettingsGetSafeDefaultsAndCustomColorsRoundTripThroughSettingsBackup() {
        assertEquals(ReaderCustomColors(), appJson.decodeFromString<ReaderSettings>("{}").customColors)
        val reader = ReaderSettings(theme = "custom", customColors = ReaderCustomColors(0xABCDEF, 0x123456, 0x000000))
        val backup = SettingsBackup(reader = reader)
        assertEquals(backup, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)))
        assertEquals(reader.customColors, reader.withEInkMode(true).withEInkMode(false).customColors)
        assertEquals("custom", reader.withEInkMode(true).withEInkMode(false).resolvedTheme)
        assertEquals(reader.customColors, reader.withTheme("paper").withTheme("custom").customColors)
    }

    @Test fun renderingUsesOpaqueCustomColorsAndIgnoresThemForOtherThemes() {
        val reader = ReaderSettings(theme = "custom", customColors = ReaderCustomColors(0xABCDEF, 0x123456, 0))
        val colors = readerColors(reader, lightColorScheme())
        assertEquals(Color(0xFFABCDEFL), colors.foreground)
        assertEquals(Color(0xFF123456L), colors.background)
        assertEquals(Color.Black, colors.toolbar)
        assertEquals(readerColors("paper", lightColorScheme()), readerColors(reader.withTheme("paper"), lightColorScheme()))
        val invalid = reader.copy(customColors = reader.customColors.copy(text = -1))
        assertFalse(invalid.customColors.valid)
        assertEquals(ReaderCustomColors(), invalid.resolvedCustomColors)
        assertFalse(ReaderCustomColors(toolbar = 0x1000000).valid)
    }

    @Test fun perBookDifferenceShowsColorsOnlyWhenTheyAreActive() {
        val defaults = ReaderSettings(theme = "custom")
        val changed = defaults.copy(customColors = defaults.customColors.copy(text = 0x000000))
        assertEquals(listOf("自定义配色"), readerSettingsDifferences(changed, defaults).map { it.label })
        assertTrue(readerSettingsDifferences(changed.withTheme("paper"), defaults.withTheme("paper")).isEmpty())
    }
}

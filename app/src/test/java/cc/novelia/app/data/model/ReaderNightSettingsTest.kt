package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReaderNightSettingsTest {
    @Test fun nightEditsAndResetNeverOverwriteOrdinaryReadingConfiguration() {
        val ordinary = ReaderSettings(theme = "paper", fontSize = 23f, lineHeight = 1.7f, letterSpacing = .01f,
            weight = true, fontFamily = "serif", brightness = .65f, toolbarTransparency = .4f)
        val enabled = ordinary.copy(nightReading = true)
        val edited = enabled.withReadingAppearance(enabled.readingAppearance().copy(fontSize = 26f, lineHeight = 2.2f,
            fontFamily = "monospace", letterSpacing = .08f, customColors = ReaderCustomColors(0xBBBBBB, 0x222222, 0x333333)))
            .withBrightness(ReaderBrightness.night().copy(level = .15f, adjustment = -.1f))
        assertEquals(26f, edited.readingAppearance().fontSize, 0f)
        assertEquals("monospace", edited.readingAppearance().fontFamily)
        assertEquals(ordinary, edited.copy(nightReading = false, nightPreset = ReaderNightPreset(), nightBrightness = ReaderBrightness.night()))
        val disabled = edited.copy(nightReading = false)
        assertEquals(ordinary.fontSize, disabled.readingAppearance().fontSize, 0f)
        assertEquals(ordinary.fontFamily, disabled.readingAppearance().fontFamily)
        assertEquals(.65f, disabled.activeBrightness.target(null), 0f)
        assertEquals(edited.readingAppearance(), disabled.copy(nightReading = true).readingAppearance())
        val reset = edited.resetNightPreset()
        assertTrue(reset.nightReading)
        assertEquals(enabled, reset)
    }

    @Test fun unrelatedEditsDoNotFreezeInheritedNightFontOrSpacing() {
        val night = ReaderSettings(nightReading = true, fontFamily = "serif", fontSize = 22f)
        val edited = night.withReadingAppearance(night.readingAppearance().copy(showProgressBar = false))
        assertFalse(edited.showProgressBar)
        assertNull(edited.nightPreset.fontSize)
        assertNull(edited.nightPreset.fontFamily)
        assertNull(edited.nightPreset.lineHeight)
        assertEquals(1.9f, edited.readingAppearance().lineHeight, .001f)
    }

    @Test fun backupAndModeSwitchesRetainSeparateNightAndOrdinarySettings() {
        val normal = ReaderSettings(nightReading = true, theme = "paper",
            nightPreset = ReaderNightPreset(lineHeight = 2.1f, fontFamily = "serif"),
            brightnessPreferences = ReaderBrightness(mode = "manual", level = .7f))
        val ink = normal.withEInkMode(true).copy(nightReading = false, nightPreset = ReaderNightPreset(letterSpacing = .1f))
        val state = LibraryState(reader = ink, bookSettings = mapOf("local/test" to normal))
        val saved = appJson.decodeFromString<LibraryState>(appJson.encodeToString(state))
        assertEquals(state, saved)
        assertEquals(normal, saved.reader.withEInkMode(false).copy(eInkPreferences = null))
        assertEquals(ink.copy(beforeEInk = null, eInkPreferences = null),
            saved.reader.withEInkMode(false).withEInkMode(true).copy(beforeEInk = null, eInkPreferences = null))
        val legacy = appJson.decodeFromString<ReaderSettings>("""{"brightness":0.3,"lineHeight":1.4}""")
        assertFalse(legacy.nightReading)
        assertEquals(.3f, legacy.activeBrightness.target(null), 0f)
        assertEquals(legacy, legacy.readingAppearance())
        assertEquals(.01f, ReaderSettings(brightness = 0f).activeBrightness.target(null), 0f)
    }

    @Test fun adaptiveBrightnessHonorsRangePreferenceAndSensorFallback() {
        val preset = ReaderBrightness.night()
        assertEquals(.05f, preset.target(0f), .0001f)
        assertEquals(.25f, preset.target(50_000f), .0001f)
        assertTrue(preset.target(40f) > preset.target(4f))
        assertTrue(preset.copy(adjustment = .05f).target(10f) > preset.target(10f))
        assertEquals(.1f, preset.target(null), 0f)
        assertEquals(.1f, preset.target(Float.NaN), 0f)
        assertEquals(-1f, ReaderBrightness().target(10f), 0f)
        assertFalse(preset.copy(minimum = .5f, maximum = .2f).valid)
        assertFalse(preset.copy(level = Float.NaN).valid)
        assertFalse(ReaderNightPreset(letterSpacing = Float.POSITIVE_INFINITY).valid)
    }
}

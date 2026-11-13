package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReaderTypographyDpTest {
    @Test fun legacyUnitsMigrateUsingDisplayedSizeOnceAndKeepOriginalFields() {
        val old = ReaderSettings(fontSize = 20f, lineHeight = 1.8f, letterSpacing = .05f)
        val migrated = old.withDpTypography { it * 1.3f }
        assertEquals(ReaderTypographyDp(26f, 20.8f, 1.3f), migrated.typographyDp)
        assertEquals(old, migrated.copy(typographyDp = null, nightPreset = old.nightPreset))
        assertEquals(migrated, migrated.withDpTypography { error("已迁移的 dp 不应再次按系统字号换算") })
        assertEquals(migrated, appJson.decodeFromString<ReaderSettings>(appJson.encodeToString(migrated)))
    }

    @Test fun overlappingLegacyLinesMigrateToZeroExtraSpace() {
        val migrated = ReaderSettings(fontSize = 20f, lineHeight = .5f).withDpTypography { it }
        assertEquals(0f, migrated.typographyDp!!.lineSpacing, 0f)
        assertEquals(0f, migrated.copy(nightReading = true).readingAppearance().typographyDp!!.lineSpacing, 0f)
    }

    @Test fun nightInheritancePreservesExistingGapAndThenUsesFixedDp() {
        val ordinary = ReaderSettings(fontSize = 20f, lineHeight = 1.6f).withDpTypography { it * 1.5f }
        val night = ordinary.copy(nightReading = true)
        assertEquals(ReaderTypographyDp(30f, 21f, .6f), night.readingAppearance().typographyDp)
        val largerFont = night.copy(typographyDp = ordinary.typographyDp!!.copy(fontSize = 60f))
        assertEquals(ReaderTypographyDp(60f, 21f, .6f), largerFont.readingAppearance().typographyDp)
        assertEquals(ordinary, night.copy(nightReading = false).readingAppearance())
    }

    @Test fun editingNightFontAndGapDoesNotChangeOrdinaryValuesOrOtherInheritedFields() {
        val night = ReaderSettings(nightReading = true).withDpTypography { it }
        val shown = night.readingAppearance()
        val fontOnly = night.withReadingAppearance(shown.copy(typographyDp = shown.typographyDp!!.copy(fontSize = 64f)))
        assertEquals(night.typographyDp, fontOnly.typographyDp)
        assertEquals(64f, fontOnly.nightPreset.typographyDp!!.fontSize!!, 0f)
        assertNull(fontOnly.nightPreset.typographyDp!!.lineSpacing)
        val edited = fontOnly.withReadingAppearance(fontOnly.readingAppearance().copy(typographyDp = ReaderTypographyDp(64f, 32f, 2f)))
        assertEquals(ReaderTypographyDp(64f, 32f, 2f), edited.readingAppearance().typographyDp)
        assertEquals(night.typographyDp, edited.copy(nightReading = false).readingAppearance().typographyDp)
    }

    @Test fun explicitLegacyNightOverridesMigrateUsingTheirOwnFontSize() {
        val migrated = ReaderSettings(fontSize = 20f, nightReading = true,
            nightPreset = ReaderNightPreset(fontSize = 24f, lineHeight = 2f, letterSpacing = .1f)).withDpTypography { it * 1.5f }
        assertEquals(ReaderTypographyDp(36f, 36f, 3.6f), migrated.readingAppearance().typographyDp)
    }

    @Test fun regularAndEInkProfilesKeepIndependentDpValues() {
        val normal = ReaderSettings().withDpTypography { it }
        val ink = normal.withEInkMode(true).copy(typographyDp = ReaderTypographyDp(64f, 32f, 2f))
        val restored = appJson.decodeFromString<ReaderSettings>(appJson.encodeToString(ink))
        assertEquals(normal.typographyDp, restored.withEInkMode(false).typographyDp)
        assertEquals(ink.typographyDp, restored.withEInkMode(false).withEInkMode(true).typographyDp)
    }

    @Test fun oldProfileMigratesIndependentlyWhenFirstSelected() {
        val normal = ReaderSettings().withDpTypography { it }
        val legacyInk = normal.copy(eInkPreferences = ReaderPagingState(fontSize = 30f, lineHeight = 2f, letterSpacing = .1f))
        val selected = legacyInk.withEInkMode(true)
        assertNull(selected.typographyDp)
        assertEquals(ReaderTypographyDp(45f, 45f, 4.5f), selected.withDpTypography { it * 1.5f }.typographyDp)
        assertEquals(normal.typographyDp, selected.withDpTypography { it * 1.5f }.withEInkMode(false).typographyDp)
    }

    @Test fun dpBoundsRejectNegativeSpacingNonFiniteValuesAndOversizedDormantPresets() {
        assertTrue(ReaderTypographyDp(128f, 256f, 32f).valid)
        assertTrue(ReaderTypographyDp(8f, 0f, 0f).valid)
        listOf(ReaderTypographyDp(129f), ReaderTypographyDp(lineSpacing = -1f), ReaderTypographyDp(letterSpacing = 33f),
            ReaderTypographyDp(Float.NaN), ReaderTypographyDp(lineSpacing = Float.POSITIVE_INFINITY)).forEach { assertFalse(it.valid) }
        assertFalse(ReaderNightTypographyDp(inheritedLineSpacingExtra = Float.NaN).valid)
        val invalidDormant = ReaderSettings().withEInkMode(true).copy(beforeEInk = ReaderPagingState(typographyDp = ReaderTypographyDp(129f)))
        assertFalse(invalidDormant.modeConfigurations().all { it.validNightReading })
    }
}

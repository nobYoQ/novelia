package cc.novelia.app.reader

import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SpeechSleepTimerTest {
    @Test fun timerPreferencesKeepLegacyDefaultsAndSurviveBackupsAndIndependentModes() {
        assertEquals(30, appJson.decodeFromString<ReaderSettings>("{}").speechMinutes)
        val normal = ReaderSettings(speechMinutes = 0)
        val ink = normal.withEInkMode(true).copy(speechMinutes = 60)
        val restored = appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(SettingsBackup(reader = ink))).reader
        assertEquals(60, restored.speechMinutes)
        assertEquals(0, restored.withEInkMode(false).speechMinutes)
        assertEquals(60, restored.withEInkMode(false).withEInkMode(true).speechMinutes)
        val state = LibraryState(reader = normal, bookSettings = mapOf("local/book" to ink))
        assertEquals(state, appJson.decodeFromString<LibraryState>(appJson.encodeToString(state)))
        assertEquals("不限时", readerSettingsDifferences(normal, ReaderSettings()).single().book)
    }

    @Test fun unlimitedPlaybackNeverExpiresAndCancelsAnExistingDeadline() {
        val timer = SpeechSleepTimer()
        assertEquals(15 * 60_000L, timer.reset(15, 1_000))
        assertFalse(timer.expired(900_999))
        assertTrue(timer.expired(901_000))
        assertNull(timer.reset(0, 500_000))
        assertFalse(timer.expired(901_000))
        assertFalse(timer.expired(Long.MAX_VALUE))
        assertNull(timer.reset(0, 0))
        assertFalse(timer.expired(Long.MAX_VALUE))
    }

    @Test fun selectingATimedDurationAfterUnlimitedStartsANewDeadline() {
        val timer = SpeechSleepTimer()
        timer.reset(0, 0)
        timer.reset(30, 50_000)
        // 暂停、续章与重试只检查同一个期限，不重新计时。
        assertFalse(timer.expired(1_849_999))
        assertTrue(timer.expired(1_850_000))
        timer.reset(15, 1_000_000)
        assertFalse(timer.expired(1_899_999))
        assertTrue(timer.expired(1_900_000))
    }

    @Test fun invalidValuesDoNotAccidentallyDisableTheTimerOrOverflow() {
        val timer = SpeechSleepTimer()
        assertEquals(30 * 60_000L, timer.reset(-1, 0))
        assertEquals(ReaderSettings.MAX_SPEECH_MINUTES * 60_000L, timer.reset(Int.MAX_VALUE, 0))
        assertEquals("不限时", ReaderSettings.speechTimerLabel(0))
    }
}

package cc.novelia.app.reader

import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class AutoReadPaceTest {
    @Test fun scrollDistanceUsesRealTimeAndBoundsDelayedFrames() {
        val settings = ReaderSettings(autoReadSpeed = 20f)
        assertEquals(1.28f, autoReadDistance(settings, 2f, 32), .001f)
        assertEquals(4f, autoReadDistance(settings, 2f, 60_000), .001f)
        assertEquals(0f, autoReadDistance(settings, 2f, -20), .001f)
        assertEquals(0f, autoReadDistance(settings, -1f, 32), .001f)
    }

    @Test fun oldSettingsAndInvalidRuntimeValuesHaveSafeDefaults() {
        assertEquals(20f, appJson.decodeFromString<ReaderSettings>("{}").resolvedAutoReadSpeed)
        assertEquals(20f, ReaderSettings(autoReadSpeed = Float.NaN).resolvedAutoReadSpeed)
        assertEquals(80f, ReaderSettings(autoReadSpeed = 999f).resolvedAutoReadSpeed)
        assertEquals(5, ReaderSettings(autoReadIntervalSeconds = -1).resolvedAutoReadIntervalSeconds)
        assertFalse(ReaderSettings(autoReadSpeed = Float.POSITIVE_INFINITY).validAutoRead)
        assertFalse(ReaderSettings(autoReadIntervalSeconds = 121).validAutoRead)
        val settings = ReaderSettings(autoReadSpeed = 35f, autoReadIntervalSeconds = 40)
        assertEquals(settings, appJson.decodeFromString<ReaderSettings>(appJson.encodeToString(settings)))
    }
}

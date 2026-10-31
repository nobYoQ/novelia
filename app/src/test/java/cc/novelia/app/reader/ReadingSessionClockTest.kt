package cc.novelia.app.reader

import org.junit.Assert.*
import org.junit.Test

class ReadingSessionClockTest {
    @Test fun backgroundAndRepeatedFlushDoNotAddTime() {
        val clock = ReadingSessionClock()
        assertNull(clock.update(1_000, 1_000_000, true))
        assertEquals(30_000L, clock.update(31_000, 1_030_000, true)?.millis)
        assertEquals(10_000L, clock.update(41_000, 1_040_000, false)?.millis)
        assertNull(clock.update(41_000, 1_040_000, false))
        assertNull(clock.update(81_000, 1_080_000, true))
        assertEquals(10_000L, clock.update(91_000, 1_090_000, false)?.millis)
    }

    @Test fun idleTimeStopsAtFiveMinutesAndInteractionRestartsWithoutBackfill() {
        val clock = ReadingSessionClock()
        clock.update(0, 1_000_000, true)
        assertEquals(300_000L, clock.update(600_000, 1_600_000, true)?.millis)
        assertNull(clock.update(700_000, 1_700_000, true, interacted = true))
        assertEquals(5_000L, clock.update(705_000, 1_705_000, true)?.millis)
        clock.update(705_000, 1_705_000, true, automatic = true)
        assertEquals(900_000L, clock.update(1_605_000, 2_605_000, false)?.millis)
    }

    @Test fun wallClockChangesDoNotChangeElapsedReadingTime() {
        val clock = ReadingSessionClock()
        clock.update(0, 1_000_000, true)
        assertEquals(1_000L, clock.update(1_000, 500_000, true)?.millis)
        assertEquals(1_000L, clock.update(2_000, 9_000_000, false)?.millis)
    }
}

package cc.novelia.app

import cc.novelia.app.ui.ReaderChapterOverscrollGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChapterOverscrollTest {
    @Test fun onlyTheUnconsumedDistanceAtTheEndCounts() {
        val gesture = ReaderChapterOverscrollGesture(72f)
        gesture.begin()
        gesture.pull(-300f, atEnd = false)
        gesture.pull(-36f, atEnd = true)
        gesture.pull(-35f, atEnd = true)
        assertFalse(gesture.release())
        gesture.begin()
        gesture.pull(-36f, atEnd = true)
        gesture.pull(-36f, atEnd = true)
        assertTrue(gesture.release())
        assertFalse(gesture.release())
    }

    @Test fun flingingOrProgrammaticScrollingWithoutATouchCannotTurnTheChapter() {
        val gesture = ReaderChapterOverscrollGesture(72f)
        gesture.pull(-300f, atEnd = true)
        assertFalse(gesture.release())
    }

    @Test fun cancellationCannotBeRearmedByRemainingMotion() {
        val gesture = ReaderChapterOverscrollGesture(72f)
        gesture.begin()
        gesture.pull(-100f, atEnd = true)
        gesture.cancel()
        gesture.pull(-100f, atEnd = true)
        assertFalse(gesture.release())
    }

    @Test fun leavingTheBottomDiscardsPreviousPull() {
        val gesture = ReaderChapterOverscrollGesture(72f)
        gesture.begin()
        gesture.pull(-100f, atEnd = true)
        gesture.pull(0f, atEnd = false)
        gesture.pull(-10f, atEnd = true)
        assertFalse(gesture.release())
    }

    @Test fun aNewTouchStartsWithNoPreviousDistance() {
        val gesture = ReaderChapterOverscrollGesture(72f)
        gesture.begin()
        gesture.pull(-60f, atEnd = true)
        assertFalse(gesture.release())
        gesture.begin()
        gesture.pull(-60f, atEnd = true)
        assertFalse(gesture.release())
    }

    @Test fun pullShowsProgressAndFollowsTheFingerBeforeItCanBeReleased() {
        val gesture = ReaderChapterOverscrollGesture(56f)
        gesture.begin()
        assertEquals(-28f, gesture.pull(-28f, atEnd = true), 0f)
        assertEquals(.5f, gesture.progress, .001f)
        assertEquals(22.4f, gesture.offset, .001f)
        assertFalse(gesture.ready)
        gesture.pull(-28f, atEnd = true)
        assertTrue(gesture.ready)
        assertEquals(1f, gesture.progress, 0f)
        assertTrue(gesture.active)
        assertTrue(gesture.release())
        assertEquals(0f, gesture.offset, 0f)
        assertFalse(gesture.release())
    }

    @Test fun withdrawingBelowTheThresholdDisarmsWithoutScrollingTheBodyBackwards() {
        val gesture = ReaderChapterOverscrollGesture(56f)
        gesture.begin()
        gesture.pull(-72f, atEnd = true)
        assertTrue(gesture.ready)
        assertEquals(24f, gesture.withdraw(24f), 0f)
        assertFalse(gesture.ready)
        assertFalse(gesture.release())
        gesture.begin()
        gesture.pull(-20f, atEnd = true)
        assertEquals(20f, gesture.withdraw(30f), 0f)
        assertEquals(0f, gesture.distance, 0f)
    }

    @Test fun longPullIsBoundedAndDoesNotStartAMomentumScrollOnRelease() {
        val gesture = ReaderChapterOverscrollGesture(56f)
        gesture.begin()
        assertEquals(-1000f, gesture.pull(-1000f, atEnd = true), 0f)
        assertEquals(112f, gesture.distance, 0f)
        assertEquals(1f, gesture.progress, 0f)
        assertTrue(gesture.release())
        assertTrue(gesture.suppressFling)
        gesture.begin()
        assertFalse(gesture.suppressFling)
    }

    @Test fun returningThePullThenScrollingBackIntoTheChapterRestoresNormalFling() {
        val gesture = ReaderChapterOverscrollGesture(56f)
        gesture.begin()
        gesture.pull(-40f, atEnd = true)
        assertEquals(40f, gesture.withdraw(64f), 0f)
        assertFalse(gesture.suppressFling)
        assertFalse(gesture.release())
        assertFalse(gesture.suppressFling)
    }

    @Test fun leavingTheEndClearsFlingSuppressionButReleasingAPullKeepsIt() {
        val gesture = ReaderChapterOverscrollGesture(56f)
        gesture.begin()
        gesture.pull(-40f, atEnd = true)
        gesture.withdraw(40f)
        gesture.pull(0f, atEnd = false)
        assertFalse(gesture.suppressFling)
        gesture.pull(-24f, atEnd = true)
        assertTrue(gesture.suppressFling)
        assertFalse(gesture.release())
        assertTrue(gesture.suppressFling)
    }
}

package cc.novelia.app

import cc.novelia.app.ui.ReaderChapterOverscrollGesture
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
}

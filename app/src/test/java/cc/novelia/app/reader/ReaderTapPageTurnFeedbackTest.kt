package cc.novelia.app.reader

import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.storage.appJson
import org.junit.Assert.*
import org.junit.Test

class ReaderTapPageTurnFeedbackTest {
    @Test fun legacyModesUseTheirOwnDefaults() {
        val normal = appJson.decodeFromString<ReaderSettings>("""{"tapPageTurn":true,"eInkPreferences":{}}""")
        assertTrue(normal.tapPageTurnFeedbackEnabled())
        assertFalse(normal.tapPageTurnFeedbackEnabled(eInk = true))
        val ink = normal.copy(tapPageTurnFeedback = true).withEInkMode(true)
        assertTrue(ink.tapPageTurn)
        assertFalse(ink.tapPageTurnFeedbackEnabled())
        assertTrue(ink.withEInkMode(false).tapPageTurnFeedbackEnabled())
    }

    @Test fun explicitChoicesSurviveSerializationAndModeChanges() {
        val normal = ReaderSettings(tapPageTurn = true, tapPageTurnFeedback = false)
        val ink = normal.withEInkMode(true).copy(tapPageTurnFeedback = true)
        val restored = appJson.decodeFromString<ReaderSettings>(appJson.encodeToString(ReaderSettings.serializer(), ink))
        assertTrue(restored.tapPageTurnFeedbackEnabled())
        val restoredNormal = restored.withEInkMode(false)
        assertFalse(restoredNormal.tapPageTurnFeedbackEnabled())
        assertTrue(restoredNormal.withEInkMode(true).tapPageTurnFeedbackEnabled())
    }
}

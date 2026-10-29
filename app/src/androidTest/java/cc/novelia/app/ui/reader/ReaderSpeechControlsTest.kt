package cc.novelia.app.ui.reader

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.SpeechPlayback
import cc.novelia.app.reader.SpeechSegment
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderSpeechControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sentenceControlsAndFollowSwitchReflectPlayback() {
        var playback by mutableStateOf(SpeechPlayback("test", "local/book", "one", SpeechSegment("当前句。", 0, "original"), 0, 3, true))
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            ReaderSpeechSheet(ReaderSettings(), "正在朗读：测试章节", {}, {}, {}, {}, {}, {}, playback,
                onPrevious = { playback = playback.copy(index = playback.index - 1) },
                onNext = { playback = playback.copy(index = playback.index + 1) },
                onFollow = { playback = playback.copy(following = it) })
        } } }
        compose.onNodeWithTag("reader-speech-sentence").assertTextContains("第 1 / 3 句")
        compose.onNodeWithText("上一句").assertIsNotEnabled()
        compose.onNodeWithText("下一句").performScrollTo().performClick()
        compose.onNodeWithTag("reader-speech-sentence").performScrollTo().assertTextContains("第 2 / 3 句")
        compose.onNodeWithText("跟随朗读位置").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(playback.following) }
        compose.onNodeWithText("上一句").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, playback.index) }
    }
}

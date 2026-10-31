package cc.novelia.app.ui.reader

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.SpeechPlayback
import cc.novelia.app.reader.SpeechSegment
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.saveTestScreenshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderSpeechControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sentenceControlsAndFollowSwitchReflectPlayback() {
        var playback by mutableStateOf(SpeechPlayback("test", "local/book", "one", SpeechSegment("当前句。", 0, "original"), 0, 3, true))
        var settings by mutableStateOf(ReaderSettings())
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            ReaderSpeechSheet(settings, "正在朗读：测试章节", {}, {}, {}, {}, {}, {}, playback,
                onPrevious = { playback = playback.copy(index = playback.index - 1) },
                onNext = { playback = playback.copy(index = playback.index + 1) },
                onFollow = { playback = playback.copy(following = it) },
                onRate = { settings = settings.copy(speechRate = it) }, onTimer = { settings = settings.copy(speechMinutes = it) })
        } } }
        compose.onNodeWithTag("reader-speech-sentence").assertTextContains("第 1 / 3 句")
        saveTestScreenshot("speech-options.png")
        compose.onNodeWithContentDescription("上一句").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下一句").performScrollTo().performClick()
        compose.onNodeWithTag("reader-speech-sentence").performScrollTo().assertTextContains("第 2 / 3 句")
        compose.onNodeWithText("跟随朗读位置").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(playback.following) }
        compose.onNodeWithContentDescription("上一句").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, playback.index) }
        compose.onNodeWithText("1.25×").performScrollTo().performClick()
        compose.onNodeWithText("15 分钟").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1.25f, settings.speechRate); assertEquals(15, settings.speechMinutes) }
    }

    @Test fun dockKeepsSentenceControlsOutsideTheBodyAtLargeFontSizes() {
        var playback by mutableStateOf(SpeechPlayback("test", "local/book", "one", SpeechSegment("山间的旅人走向图书馆。", 0, "original"), 0, 3, true))
        var fontScale by mutableFloatStateOf(1f)
        var theme by mutableStateOf("light")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NoveliaTheme(theme) { AppInteractionMode(false, true) {
                    Column(Modifier.width(360.dp).fillMaxHeight()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("正文仍然可见") }
                        ReaderSpeechDock(playback, "正在朗读", { playback = playback.copy(index = playback.index - 1) },
                            { playback = playback.copy(index = playback.index + 1) }, { playback = playback.copy(playing = false) },
                            { playback = playback.copy(playing = true) }, {}, { playback = playback.copy(following = it) }, {})
                    }
                } }
            }
        }
        compose.onNodeWithContentDescription("下一句").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("暂停").performClick()
        compose.onNodeWithContentDescription("上一句").performClick()
        compose.runOnIdle { assertEquals(0, playback.index); assertFalse(playback.playing); assertTrue(playback.following) }
        compose.onNodeWithText("正文仍然可见").assertIsDisplayed()
        saveTestScreenshot("speech-dock.png")
        compose.runOnIdle { fontScale = 2f; theme = "dark" }
        compose.onNodeWithContentDescription("下一句").assertIsDisplayed().performClick()
        compose.onNodeWithText("继续").performClick()
        compose.runOnIdle { assertEquals(1, playback.index); assertTrue(playback.playing) }
        saveTestScreenshot("speech-dock-large.png")
    }
}

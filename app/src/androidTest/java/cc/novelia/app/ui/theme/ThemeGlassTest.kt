package cc.novelia.app.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.ThemeBarGlass
import cc.novelia.app.data.model.ThemePreset
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThemeGlassTest {
    @get:Rule val compose = createComposeRule()

    @Test fun barsSampleTheirOwnPartOfTheWallpaperWithoutBlurringTextOrTheBody() {
        val file = File.createTempFile("glass-", ".png", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val pixels = IntArray(128 * 128) { i ->
            val left = i % 128 < 64
            when (i / 128) {
                in 0..39 -> if (left) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                in 88..127 -> if (left) android.graphics.Color.BLUE else android.graphics.Color.YELLOW
                else -> if (left) android.graphics.Color.GREEN else android.graphics.Color.MAGENTA
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, 128, 128, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val clear = ThemeBarGlass(true, blur = 0, opacity = 0)
        var top by mutableStateOf(clear.copy(blur = 100))
        var bottom by mutableStateOf(clear.copy(enabled = false))
        try {
            compose.setContent {
                val theme = ThemePreset("glass", "玻璃", image = "b".repeat(64), overlay = 0, topBar = top, bottomBar = bottom)
                NoveliaTheme("light", preset = theme, imageFile = file) {
                    // 覆盖窗口内有偏移的预览视口，以及长宽比不同的壁纸。
                    Box(Modifier.padding(start = 26.dp, top = 18.dp)) {
                        Column(Modifier.size(160.dp, 180.dp).testTag("sample")
                            .themePaper(MaterialTheme.colorScheme.background, LocalThemeWallpaper.current, anchorBars = true)) {
                            val upper = themeBarBackground(ThemeBar.Top, Color.Gray)
                            Box(Modifier.fillMaxWidth().height(42.dp).then(upper.modifier).background(upper.color)) {
                                Box(Modifier.align(Alignment.TopStart).size(12.dp).background(Color.Red))
                            }
                            Spacer(Modifier.weight(1f))
                            val lower = themeBarBackground(ThemeBar.Bottom, Color.Gray)
                            Box(Modifier.fillMaxWidth().height(42.dp).then(lower.modifier).background(lower.color))
                        }
                    }
                }
            }
            fun sample(x: Float, y: Float): Color {
                val image = compose.onNodeWithTag("sample").captureToImage().toPixelMap()
                return image[(x * image.width).toInt(), (y * image.height).toInt()]
            }
            compose.waitUntil(10_000) { sample(.48f, .12f).red in .12f.. .7f }
            assertEquals(Color.Black, sample(.18f, .12f))
            assertEquals(Color.Red, sample(.03f, .03f))
            assertEquals(Color.Green, sample(.48f, .50f))
            assertEquals(Color.Gray, sample(.18f, .88f))
            compose.runOnIdle { top = top.copy(opacity = 100); bottom = clear }
            compose.waitUntil(10_000) { sample(.18f, .88f) == Color.Blue && sample(.80f, .88f) == Color.Yellow }
            assertEquals(Color.Gray, sample(.18f, .12f))
            compose.runOnIdle { bottom = clear.copy(blur = 100, opacity = 0) }
            compose.waitUntil(10_000) { sample(.48f, .88f).red in .12f.. .7f }
            assertEquals(Color.Green, sample(.48f, .50f))
        } finally { file.delete() }
    }
}

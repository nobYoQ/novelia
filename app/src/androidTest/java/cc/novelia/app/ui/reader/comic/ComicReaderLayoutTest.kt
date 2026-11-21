package cc.novelia.app.ui.reader.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.ComicPreferences
import cc.novelia.app.data.model.ComicReadingMode
import cc.novelia.app.reader.ComicPage
import cc.novelia.app.ui.saveTestScreenshot
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 使用专用测试图片；独立窗口的字号和密度跟随测试设备的系统配置。 */
class ComicReaderLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val sections = List(3) { ComicSection("chapter-$it", "第 ${it + 1} 话 · 河岸边的日常", it * 8, 8) }

    @Test fun panelsAndDirectionChangesKeepViewportAndReadingPosition() {
        val pages = fixturePages()
        var preferences by mutableStateOf(ComicPreferences())
        var default by mutableStateOf(ComicReadingMode.RightToLeft)
        var page = -1
        compose.setContent {
            AppInteractionMode(false, true) { NoveliaTheme("light") {
                ComicReader("河岸边的日常", pages, preferences.mode ?: default, 10, 0, preferences, sections, default,
                    { default = it }, { preferences = it }, { index, _, _ -> page = index }, {}, {})
            } }
        }
        compose.waitUntil { page == 10 }
        val viewport = compose.onNodeWithTag("comic-reader").getUnclippedBoundsInRoot()
        compose.onNodeWithContentDescription("第 3 页，共 8 页，显示阅读工具").performClick()
        compose.onNodeWithTag("comic-bottom-toolbar").assertIsDisplayed()
        screenshot("comic-toolbar-light.png")
        compose.onNodeWithContentDescription("漫画显示设置").performClick()
        compose.onNodeWithText("裁去白边").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(preferences.cropBorders); assertEquals(10, page) }
        compose.onNodeWithContentDescription("关闭漫画显示设置").performClick()
        assertEquals(viewport, compose.onNodeWithTag("comic-reader").getUnclippedBoundsInRoot())
        compose.onNodeWithContentDescription("切换阅读方向").performClick()
        compose.onNodeWithText("从左向右").performClick()
        compose.runOnIdle { assertEquals(ComicReadingMode.LeftToRight, preferences.mode); assertEquals(10, page) }
        compose.onNodeWithText("目录").performClick()
        compose.onNode(hasText(sections[1].title) and isSelected()).assertIsDisplayed()
        screenshot("comic-chapters-light.png")
        compose.onNodeWithContentDescription("关闭章节目录").performClick()
        compose.onNodeWithContentDescription("页缩略图").performClick()
        compose.onNodeWithContentDescription("跳到第 11 页").assertIsSelected().assertIsDisplayed()
        screenshot("comic-overview-light.png")
        compose.onNodeWithContentDescription("跳到第 12 页").performClick()
        compose.waitUntil { page == 11 }
        assertEquals(viewport, compose.onNodeWithTag("comic-reader").getUnclippedBoundsInRoot())
    }

    @Test fun bookDirectionAndGlobalDefaultStayIndependent() {
        var preferences by mutableStateOf(ComicPreferences(mode = ComicReadingMode.RightToLeft))
        var default by mutableStateOf(ComicReadingMode.LeftToRight)
        compose.setContent {
            AppInteractionMode(false, true) { NoveliaTheme("light") {
                ComicDisplaySettings(preferences, default, { default = it }, { preferences = it }, {}, {})
            } }
        }
        compose.onAllNodesWithText("纵向条漫")[1].performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ComicReadingMode.Webtoon, default); assertEquals(ComicReadingMode.RightToLeft, preferences.mode) }
        compose.onNodeWithText("跟随默认").performScrollTo().performClick()
        compose.runOnIdle { assertNull(preferences.mode) }
        compose.onNodeWithText("当前 · 纵向条漫").assertExists()
        compose.onNodeWithText("适合宽度").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(preferences.fitWidth); assertFalse(preferences.cropBorders); assertFalse(preferences.splitWide) }
    }

    @Test fun panelsRemainUsableAcrossAppThemes() {
        var theme by mutableStateOf("light")
        var panel by mutableIntStateOf(0)
        var preferences by mutableStateOf(ComicPreferences())
        var default by mutableStateOf(ComicReadingMode.RightToLeft)
        var square by mutableStateOf(false)
        var static by mutableStateOf(false)
        val pages = fixturePages()
        compose.setContent {
            AppInteractionMode(square, static) { NoveliaTheme(theme, squareCorners = square, amoledBlack = theme == "dark") {
                Surface(Modifier.fillMaxSize()) {
                    when (panel) {
                        0 -> ComicDisplaySettings(preferences, default, { default = it }, { preferences = it }, {}, {})
                        1 -> ComicChapterSheet(sections, 1, {}, {})
                        2 -> ComicOverview(pages, sections, 10, {}, {})
                    }
                }
            } }
        }
        for (appearance in listOf("light", "dark", "square-static")) {
            compose.runOnIdle {
                panel = 0
                theme = if (appearance == "dark") "dark" else "light"
                square = appearance == "square-static"
                static = square
            }
            compose.onNodeWithText("跟随默认").assertIsDisplayed()
            screenshot("comic-settings-$appearance.png")
            compose.onNodeWithText("拆分横跨页").performScrollTo().performClick()
            compose.onNodeWithText("书籍详情与目录").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { panel = 1 }
            compose.onNode(hasText(sections[1].title) and isSelected()).assertIsDisplayed()
            screenshot("comic-chapters-$appearance.png")
            compose.runOnIdle { panel = 2 }
            compose.onNodeWithContentDescription("跳到第 11 页").assertIsSelected().assertIsDisplayed()
            screenshot("comic-overview-$appearance.png")
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // 独立窗口的合成晚于语义树更新，等待真实绘制后再保存主题切换截图。
        Thread.sleep(1_000)
        saveTestScreenshot(name)
    }

    private fun fixturePages(): List<ComicPage> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "comic-layout-fixture.png")
        val bitmap = Bitmap.createBitmap(600, 840, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(245, 245, 241))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.DKGRAY
        paint.textSize = 26f
        canvas.drawText("河岸边的日常", 24f, 48f, paint)
        for (row in 0..1) for (column in 0..1) {
            val left = 24f + column * 284f
            val top = 76f + row * 366f
            paint.color = Color.rgb(211 + row * 12, 215 + row * 12, 211 + row * 12)
            canvas.drawRect(left, top, left + 268f, top + 350f, paint)
            paint.color = Color.DKGRAY
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawRect(left, top, left + 268f, top + 350f, paint)
            canvas.drawCircle(left + 134f, top + 160f, 42f, paint)
            canvas.drawLine(left + 134f, top + 202f, left + 134f, top + 285f, paint)
            canvas.drawLine(left + 80f, top + 240f, left + 188f, top + 240f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawOval(left + 24f, top + 24f, left + 244f, top + 88f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return List(24) { ComicPage(file, 600, 840) }
    }
}

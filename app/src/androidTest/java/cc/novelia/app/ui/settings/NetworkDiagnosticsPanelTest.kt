@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.settings

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.network.NetworkDiagnosisState
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NetworkDiagnosticsPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun diagnosisCanBeCancelledAfterSwitchingTabsAndPartialReportIsPreserved() {
        var diagnosis by mutableStateOf(NetworkDiagnosisState())
        compose.setContent {
            AppInteractionMode(false, false) { NoveliaTheme("light") {
                val panel = rememberNetworkDiagnosticsPanelState()
                AppSheet({}) {
                    NetworkDiagnosticsPanel(panel, false, diagnosis, false, false, "",
                        onRecordingChange = {},
                        onStart = { diagnosis = NetworkDiagnosisState(true, 3, 14, "小说连接 · 直连\n连接建立失败") },
                        onCancel = { diagnosis = diagnosis.copy(running = false) },
                        onDismiss = {}, onCopy = {}, onExport = {}, onClear = {})
                }
            } }
        }
        compose.onNodeWithText("运行网络诊断").assertIsDisplayed()
        screenshot("diagnosis-light")
        compose.onNodeWithText("运行网络诊断").performClick()
        compose.onNodeWithText("已完成 3 / 14 项").assertIsDisplayed()
        compose.onNodeWithText("问题日志").performClick()
        screenshot("logs-light")
        compose.onNodeWithText("清空网络日志").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("连接诊断").performClick()
        compose.onNodeWithText("取消诊断").performScrollTo().performClick()
        compose.onNodeWithText("检测已停止").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("检测已结束").assertDoesNotExist()
        compose.onNodeWithText("启用 ECH").assertDoesNotExist()
        compose.onNodeWithText("小说连接 · 直连\n连接建立失败").assertDoesNotExist()
        compose.onNodeWithText("检测明细").performScrollTo().performClick()
        compose.onNodeWithText("小说连接 · 直连\n连接建立失败").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("复制诊断结果").performScrollTo().assertIsEnabled()
        screenshot("diagnosis-partial-result")
    }

    @Test fun recordingGuideSurvivesRestorationAndLargeTextKeepsActionsReachable() {
        var recording by mutableStateOf(false)
        var dismissed = false
        var exports = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppInteractionMode(false, true) { NoveliaTheme("dark") {
                val panel = rememberNetworkDiagnosticsPanelState()
                AppSheet({ dismissed = true }) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                        NetworkDiagnosticsPanel(panel, recording, NetworkDiagnosisState(), false, false, "",
                            onRecordingChange = { recording = it }, onStart = {}, onCancel = {},
                            onDismiss = { dismissed = true }, onCopy = {}, onExport = { exports++ }, onClear = {})
                    }
                }
            } }
        }
        compose.onNodeWithText("问题日志").performClick()
        compose.onNodeWithText("返回页面复现").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("记录问题复现过程").performScrollTo().performClick()
        assertTrue(recording)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("问题日志").assertIsSelected()
        screenshot("logs-dark-large-text-tabs")
        assertTextFits("问题日志")
        compose.onNodeWithText("正在记录，请复现问题").performScrollTo().assertIsDisplayed()
        screenshot("logs-dark-large-text")
        compose.onNodeWithText("返回页面复现").performScrollTo().performClick()
        assertTrue(dismissed)
        assertTrue("关闭面板不会停止记录", recording)
        compose.onNodeWithText("清空网络日志").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("已复现，停止记录").performScrollTo().performClick()
        assertFalse(recording)
        compose.onNodeWithText("记录已停止，可以导出").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("导出网络日志").performScrollTo().performClick()
        assertEquals(1, exports)
        screenshot("logs-dark-large-text-export")
        assertTextFits("导出网络日志")
        compose.onNodeWithText("清空网络日志").performScrollTo().assertIsEnabled()
    }

    @Test fun activeRecordingReopensOnLogsAndElectronicPaperKeepsGuideVisible() {
        compose.setContent {
            AppInteractionMode(true, true) { NoveliaTheme("light") {
                val panel = rememberNetworkDiagnosticsPanelState(recording = true)
                AppSheet({}) {
                    NetworkDiagnosticsPanel(panel, true, NetworkDiagnosisState(), false, false, "",
                        onRecordingChange = {}, onStart = {}, onCancel = {},
                        onDismiss = {}, onCopy = {}, onExport = {}, onClear = {})
                }
            } }
        }
        compose.onNodeWithText("问题日志").assertIsSelected()
        compose.onNodeWithText("正在记录，请复现问题").assertIsDisplayed()
        screenshot("logs-eink")
        compose.onNodeWithText("返回页面复现").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("导出网络日志").performScrollTo().assertIsEnabled()
    }

    private fun assertTextFits(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        // Paragraph 可能保留比实际文字更宽的测量空间；按每行实际边界检查裁切。
        assertFalse("文字被截断：$text", layouts.any { layout ->
            layout.multiParagraph.didExceedMaxLines || (0 until layout.lineCount).any { line ->
                layout.isLineEllipsized(line) || layout.getLineLeft(line) < -1f ||
                    layout.getLineRight(line) > layout.size.width + 1f ||
                    layout.getLineBottom(line) > layout.size.height + 1f
            }
        })
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onNodeWithTag("network-diagnostics-panel").captureToImage().asAndroidBitmap()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "network-panel").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

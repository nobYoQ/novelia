package cc.novelia.app.ui.settings

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.webdav.SyncDomain
import cc.novelia.app.data.webdav.WebDavConfig
import cc.novelia.app.data.webdav.WebDavSyncStatus
import cc.novelia.app.data.webdav.WebDavRecovery
import cc.novelia.app.data.webdav.WebDavRecoveryReason
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 纯界面测试，不配置实际服务器或使用设备保存的凭据。 */
class WebDavLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = WebDavConfig(endpoint = "https://example.com/dav/", deviceName = "测试设备", enabled = true, datasetId = "layout-fixture")

    @Test fun mainPageKeepsChoicesIndependentAndServerFormSeparate() {
        var config by mutableStateOf(fixture)
        var openedServer = false
        var synced = false
        compose.setContent {
            AppInteractionMode(false, true) { NoveliaTheme("light") {
                WebDavSyncContent(config, WebDavSyncStatus(), false, false, WebDavSyncActions(
                    onServer = { openedServer = true }, onSync = { synced = true },
                    onDomain = { domain, checked -> config = config.copy(selected = if(checked) config.selected + domain else config.selected - domain) },
                ))
            } }
        }
        compose.onNodeWithText("多设备同步").assertIsDisplayed()
        compose.onNodeWithText("服务器地址").assertDoesNotExist()
        compose.onNodeWithText("立即同步").assertIsDisplayed()
        screenshot("main-light")
        compose.onNodeWithText("标签库").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("本地收藏").performScrollTo().assertIsOn()
        scrollTo("阅读历史").assertIsOn()
        assertFalse(SyncDomain.KEYWORDS in config.selected)
        assertTrue(SyncDomain.HISTORY in config.selected)
        compose.onNodeWithText("立即同步").assertIsDisplayed().performClick()
        assertTrue(synced)
        scrollTo("同步服务器").performClick()
        assertTrue(openedServer)
    }

    @Test fun darkMainPageShowsPendingJoinAndCanStopRunningSync() {
        var enabled = true
        var running by mutableStateOf(false)
        compose.setContent {
            AppInteractionMode(false, true) { NoveliaTheme("dark") {
                WebDavSyncContent(fixture, WebDavSyncStatus(running = running), true, running, WebDavSyncActions(onEnabled = { enabled = it }))
            } }
        }
        compose.onNodeWithText("查看资料并连接").assertIsDisplayed()
        screenshot("main-dark")
        compose.runOnIdle { running = true }
        compose.onNodeWithText("正在处理…").assertIsNotEnabled()
        compose.onNodeWithText("启用 WebDAV").assertIsEnabled().performClick()
        assertFalse(enabled)
        compose.onNodeWithText("标签库").performScrollTo().assertIsNotEnabled()
    }

    @Test fun serverPageMasksPasswordAndSavesCurrentInput() {
        var saved: WebDavConfig? = null
        var entered = ""
        compose.setContent {
            AppInteractionMode(false, true) { NoveliaTheme("light") {
                WebDavServerForm(fixture, false, onBack = {}, onSave = { value, password -> saved = value; entered = password }, onClearPassword = {})
            } }
        }
        compose.onNodeWithText("保存并测试").assertIsDisplayed()
        compose.onNodeWithText("同步目录").assertDoesNotExist()
        screenshot("server-light")
        compose.onNodeWithText("用户名").performTextReplacement("reader")
        val password = compose.onNode(hasSetTextAction() and hasText("密码或应用授权码"))
        password.performTextInput("sample-input")
        assertFalse(displayedText(password).contains("sample-input"))
        compose.onNodeWithContentDescription("显示密码").performClick()
        assertEquals("sample-input", displayedText(password))
        compose.onNodeWithContentDescription("隐藏密码").performClick()
        assertFalse(displayedText(password).contains("sample-input"))
        compose.onNodeWithText("保存并测试").assertIsDisplayed().performClick()
        assertEquals("reader", saved?.username)
        assertEquals("sample-input", entered)
    }

    @Test fun missingDirectoryExposesRecoveryInsteadOfRepeatingTheFailedSync() {
        val recovery = WebDavRecovery(fixture.generation, fixture.datasetId!!, WebDavRecoveryReason.MISSING_DATASET)
        var requested: WebDavRecovery? = null
        var synced = false
        var openedServer = false
        compose.setContent {
            AppInteractionMode(false, true) { NoveliaTheme("light") {
                WebDavSyncContent(fixture, WebDavSyncStatus(recovery = recovery), false, false,
                    WebDavSyncActions(onRecover = { requested = it }, onSync = { synced = true }, onServer = { openedServer = true }))
            } }
        }
        compose.onNodeWithText("处理同步目录").assertIsDisplayed().performClick()
        assertEquals(recovery, requested)
        assertFalse(synced)
        compose.onNodeWithText("同步目录需要重新连接").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("检查服务器和目录").performScrollTo().performClick()
        assertTrue(openedServer)
        compose.onNodeWithText("查看并合并同步资料").assertDoesNotExist()
        screenshot("missing-directory")
    }

    @Test fun reconnectRequiresConfirmationAndCanBeCancelled() {
        var visible by mutableStateOf(true)
        var working by mutableStateOf(false)
        var confirmed = false
        compose.setContent {
            NoveliaTheme("light") {
                if(visible) WebDavReconnectDialog(working, onDismiss = { visible = false }, onConfirm = { confirmed = true })
            }
        }
        compose.onNodeWithText("重新连接同步目录？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertFalse(confirmed)
        compose.runOnIdle { visible = true; working = true }
        compose.onNodeWithText("重新检查并预览").assertIsNotEnabled()
        compose.runOnIdle { working = false }
        compose.onNodeWithText("重新检查并预览").performClick()
        assertTrue(confirmed)
    }

    @Test fun darkServerPageKeepsAdvancedFieldsAccessible() {
        compose.setContent {
            AppInteractionMode(false, true) { NoveliaTheme("dark") {
                WebDavServerForm(fixture, false, onBack = {}, onSave = { _, _ -> }, onClearPassword = {})
            } }
        }
        screenshot("server-dark")
        compose.onNodeWithText("更多选项").performScrollTo().performClick()
        compose.onNodeWithText("同步目录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("此设备名称").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("清除已保存的密码").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存并测试").assertIsDisplayed()
    }

    @Test fun largeTextKeepsHistoryAndPrimaryActionReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppInteractionMode(false, true) { NoveliaTheme("light") {
                    WebDavSyncContent(fixture, WebDavSyncStatus(), false, false, WebDavSyncActions())
                } }
            }
        }
        scrollTo("阅读历史").assertIsDisplayed()
        compose.onNodeWithText("立即同步").assertIsDisplayed()
        screenshot("main-large-text")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "webdav-layout").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    private fun displayedText(node: SemanticsNodeInteraction): String {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.text.text
    }
}

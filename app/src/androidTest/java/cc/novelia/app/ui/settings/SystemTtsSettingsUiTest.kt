package cc.novelia.app.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.reader.SystemTtsCatalog
import cc.novelia.app.reader.SystemTtsEngine
import cc.novelia.app.reader.readSystemTtsCatalog
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SystemTtsSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val catalog = SystemTtsCatalog(listOf(SystemTtsEngine("test.system", "系统语音"),
        SystemTtsEngine("test.multi", "MultiTTS")), "test.system")

    @Test fun installedEngineCanBeSelectedConfiguredAndResetToSystemDefault() {
        var selected by mutableStateOf<String?>(null)
        var configured: String? = null
        var installed: String? = null
        var systemSettings = 0
        compose.setContent { NoveliaTheme("light") {
            SystemTtsSettingsContent(catalog, selected, onSelect = { selected = it },
                onSystemSettings = { systemSettings++ }, onConfigure = { configured = it }, onInstallVoice = { installed = it })
        } }
        option("跟随系统默认").assertIsSelected()
        option("MultiTTS").performClick().assertIsSelected()
        option("配置 MultiTTS").performClick()
        option("安装或管理语音数据").performClick()
        compose.runOnIdle { assertEquals("test.multi", selected); assertEquals(selected, configured); assertEquals(selected, installed) }
        option("系统文字转语音设置").performClick()
        compose.runOnIdle { assertEquals(1, systemSettings) }
        option("跟随系统默认").performClick().assertIsSelected()
        compose.runOnIdle { assertNull(selected) }
    }

    @Test fun removedEngineFallsBackAndEmptyDeviceStillOffersSetupHelp() {
        var available by mutableStateOf(catalog)
        compose.setContent { NoveliaTheme("dark") {
            SystemTtsSettingsContent(available, "test.removed", onSelect = {}, onSystemSettings = {}, onConfigure = {}, onInstallVoice = {})
        } }
        option("跟随系统默认").assertIsSelected()
        option("之前选择的引擎在本机不可用", substring = true).assertIsDisplayed()
        compose.runOnIdle { available = SystemTtsCatalog() }
        option("未检测到朗读引擎").assertIsDisplayed()
        option("系统文字转语音设置").assertIsDisplayed()
        option("使用第三方引擎").assertIsDisplayed()
        compose.onNodeWithText("可自行选择安装 MultiTTS", substring = true).assertExists()
    }

    @Test fun discoveryListsOnlyEnabledInstalledSystemTtsServices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engines = readSystemTtsCatalog(context).engines
        assertEquals(engines.size, engines.map { it.packageName }.distinct().size)
        engines.forEach { engine ->
            assertTrue(engine.label.isNotBlank())
            assertTrue(context.packageManager.getApplicationInfo(engine.packageName, 0).enabled)
        }
    }

    private fun option(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = substring))
        return compose.onNodeWithText(text, substring = substring)
    }
}

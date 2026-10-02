package cc.novelia.app.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NetworkDiagnosticsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recordingControlsAndExportArchiveWorkWithoutNetwork() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        assertNull("Use the anonymous dedicated test emulator", app.session.profile.value)
        app.ech.setRecording(false)
        app.ech.clearNetworkLogs()
        compose.setContent {
            AppInteractionMode(false, true) { NoveliaTheme("light") { EchSettings(app.ech) {} } }
        }
        compose.onNodeWithText("网络诊断与日志").assertIsDisplayed()
        compose.onNodeWithText("记录问题复现过程").performScrollTo().performClick()
        compose.waitUntil(3000) { app.ech.recording.value }
        compose.onNodeWithText("导出网络日志").performScrollTo().assertIsDisplayed()
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(app.ech.exportNetworkLogs())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                if (entry.name == "environment.json") {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    assertTrue(text.contains("androidApi")); assertTrue(text.contains("echAddressPolicy"))
                    assertFalse(text.contains("Authorization")); assertFalse(text.contains("Cookie"))
                }
            }
        }
        assertTrue(names.contains("environment.json")); assertTrue(names.contains("network-current.jsonl"))
        compose.onNodeWithText("记录问题复现过程").performScrollTo().performClick()
        compose.waitUntil(3000) { !app.ech.recording.value }
        compose.onNodeWithText("清空网络日志").performScrollTo().performClick()
        compose.waitUntil(3000) { app.ech.diagnosis.value.report.isEmpty() }
        app.ech.setRecording(false)
    }

    @Test fun exportCompletesAfterScreenRestorationWhileDocumentPickerIsOpen() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        assertNull(app.session.profile.value)
        val target = File(app.filesDir, "exports/network-diagnostic-ui-test.zip")
        target.parentFile!!.mkdirs()
        val registry = object : ActivityResultRegistry() {
            var pendingCode: Int? = null
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                val intent = contract.createIntent(app, input)
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals("application/zip", intent.type)
                pendingCode = requestCode
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                AppInteractionMode(false, true) { NoveliaTheme("light") { EchSettings(app.ech) {} } }
            }
        }
        try {
            compose.onNodeWithText("导出网络日志").performScrollTo().performClick()
            compose.waitUntil(5000) { registry.pendingCode != null }
            restoration.emulateSavedInstanceStateRestore()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", target)
            compose.runOnIdle { registry.dispatchResult(registry.pendingCode!!, Activity.RESULT_OK, Intent().setData(uri)) }
            compose.waitUntil(5000) { target.length() > 0 }
            val names = mutableListOf<String>()
            ZipInputStream(target.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; names += entry.name } }
            assertTrue(names.contains("environment.json")); assertTrue(names.contains("README.txt"))
        } finally { target.delete() }
    }
}

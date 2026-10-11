package cc.novelia.app.ui.about

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.updates.*
import cc.novelia.app.ui.feedback.AppReleaseDownloadDialog
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import cc.novelia.app.data.appupdate.APP_RELEASES_URL
import cc.novelia.app.data.appupdate.AppDownloadBackend
import cc.novelia.app.data.appupdate.AppDownloadCandidate
import cc.novelia.app.data.appupdate.AppDownloadRecord
import cc.novelia.app.data.appupdate.AppDownloadRecordStore
import cc.novelia.app.data.appupdate.AppReleaseAsset
import cc.novelia.app.data.appupdate.AppReleaseChannel
import cc.novelia.app.data.appupdate.AppReleaseDownloadCoordinator
import cc.novelia.app.data.appupdate.AppTransfer
import cc.novelia.app.data.appupdate.AppTransferStatus
import cc.novelia.app.data.appupdate.InstalledAppRelease

class AppReleaseDownloadUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewConsentProgressCompletionLaterAndNewBuildUseOneFlow() {
        var candidate = AppDownloadCandidate(AppReleaseChannel.Preview, "0.3.0",
            AppReleaseAsset("Novelia-universal.apk", "$APP_RELEASES_URL/download/preview/Novelia-universal.apk", 1000, "uploaded", id = 1),
            versionCode = 2, run = 11)
        var saved = emptyMap<AppReleaseChannel, AppDownloadRecord>()
        var transfer = AppTransfer(AppTransferStatus.Pending)
        var starts = 0L; var installs = 0
        val engine = AppReleaseDownloadCoordinator({ candidate }, { InstalledAppRelease("0.2.0", 1) },
            object : AppDownloadBackend {
                override suspend fun enqueue(candidate: AppDownloadCandidate) = AppDownloadRecord(candidate, ++starts, "$starts.apk")
                override suspend fun query(record: AppDownloadRecord) = transfer
                override suspend fun verify(record: AppDownloadRecord) { }
                override suspend fun remove(record: AppDownloadRecord) { }
            }, object : AppDownloadRecordStore {
                override suspend fun load() = saved
                override suspend fun save(records: Map<AppReleaseChannel, AppDownloadRecord>) { saved = records }
            })
        compose.setContent {
            NoveliaTheme("light") { AppInteractionMode(eInk = true, reducedMotion = true) {
                val states by engine.states.collectAsState()
                val dialog by engine.dialog.collectAsState()
                val scope = rememberCoroutineScope()
                TextButton(onClick = { scope.launch { if(engine.check(AppReleaseChannel.Preview) != null) { installs++; engine.dismiss() } } }) { Text("下载预览包") }
                dialog?.let { AppReleaseDownloadDialog(states.getValue(it), engine::dismiss,
                    onConfirmPreview = { scope.launch { engine.confirmPreview() } },
                    onInstall = { installs++ }, onRetry = { scope.launch { engine.check(it) } }) }
            } }
        }
        compose.onNodeWithText("下载预览包").performClick()
        compose.onNodeWithText("预览版本不稳定", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0L, starts) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("下载预览包").performClick()
        compose.onNodeWithText("了解风险，下载").performClick()
        compose.runOnIdle { transfer = AppTransfer(AppTransferStatus.Running, 400, 1000); runBlocking { engine.refresh() } }
        compose.onNodeWithTag("app-apk-progress").assertIsDisplayed().assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(.4f, 0f..1f))
        compose.onNodeWithText("40%", substring = true).assertIsDisplayed()
        compose.runOnIdle { transfer = AppTransfer(AppTransferStatus.Complete, 1000, 1000); runBlocking { engine.refresh() } }
        compose.onNodeWithText("下载完成").assertIsDisplayed()
        compose.onNodeWithText("立即安装").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, installs) }
        compose.onNodeWithText("稍后").performClick()
        compose.onNodeWithText("下载预览包").performClick()
        compose.runOnIdle { assertEquals(2, installs); assertEquals(1L, starts); candidate = candidate.copy(run = 12, apk = candidate.apk.copy(id = 2)) }
        compose.onNodeWithText("下载预览包").performClick()
        compose.onNodeWithText("了解风险，下载").performClick()
        compose.runOnIdle { assertEquals(2L, starts); assertEquals(2, installs) }
    }

    @Test fun aboutProvidesStablePreviewAndReleaseNotesInEInkMode() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        compose.setContent {
            NoveliaTheme("light") { AppInteractionMode(eInk = true, reducedMotion = true) {
                val nav = rememberNavController(); val scope = rememberCoroutineScope()
                val feedback = remember { SnackbarHostState() }
                val controller = remember { AppController(app, nav, scope, feedback) }
                AboutScreen(controller)
            } }
        }
        for(title in listOf("下载新版本", "下载预览包", "发行说明")) {
            for(page in 0..5) {
                if(compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() && compose.onNodeWithText(title).isDisplayed()) break
                compose.onNodeWithText("下一屏").assertIsEnabled().performClick()
            }
            compose.onNodeWithText(title).assertIsDisplayed().assertHasClickAction()
        }
    }
}

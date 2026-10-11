package cc.novelia.app.data.appupdate

import android.accessibilityservice.AccessibilityService
import android.app.DownloadManager
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.BuildConfig
import cc.novelia.app.data.updates.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 仅在专用测试设备运行，预先授予安装权限；打开安装确认页，不点击安装。 */
class AppApkInstallerTest {
    @Suppress("DEPRECATION")
    @Test fun verifiedApkIsReadableByTheSystemInstaller() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val backend = AndroidAppDownloadBackend(app)
        val source = File(app.applicationInfo.sourceDir)
        val candidate = AppDownloadCandidate(AppReleaseChannel.Stable, BuildConfig.VERSION_NAME,
            AppReleaseAsset("Novelia.apk", "$APP_RELEASES_URL/download/v${BuildConfig.VERSION_NAME}/Novelia.apk", source.length(), "uploaded"),
            BuildConfig.VERSION_CODE.toLong())
        var record = AppDownloadRecord(candidate, 0, "${UUID.randomUUID()}.apk")
        val file = backend.file(record)
        file.parentFile!!.mkdirs()
        source.copyTo(file)
        val manager = app.getSystemService(DownloadManager::class.java)
        try {
            val id = manager.addCompletedDownload("Novelia installer test", "Installer handoff test", false,
                "application/vnd.android.package-archive", file.path, file.length(), true)
            record = record.copy(downloadId = id)
            assertEquals(AppTransferStatus.Complete, backend.query(record).status)
            backend.verify(record)
            val intent = backend.installationIntent(record)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data!!.scheme)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            app.contentResolver.openInputStream(intent.data!!)!!.use { assertEquals('P'.code, it.read()); assertEquals('K'.code, it.read()) }
            assertTrue("请在专用测试设备预先允许 Novelia 安装应用", app.packageManager.canRequestPackageInstalls())
            app.startActivity(intent)
            var installerVisible = false
            for(attempt in 0..149) {
                val root = automation.rootInActiveWindow
                if(root?.packageName?.toString()?.contains("packageinstaller") == true && hasInstallButton(root)) {
                    installerVisible = true
                    break
                }
                Thread.sleep(100)
            }
            assertTrue("系统安装确认页应接收到可读取的 APK", installerVisible)
        } finally {
            automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            if(record.downloadId != 0L) backend.remove(record) else file.delete()
        }
    }

    private fun hasInstallButton(node: AccessibilityNodeInfo): Boolean {
        if(node.isClickable && node.text?.toString()?.lowercase() in setOf("install", "update", "安装", "更新")) return true
        return (0 until node.childCount).any { index -> node.getChild(index)?.let(::hasInstallButton) == true }
    }
}

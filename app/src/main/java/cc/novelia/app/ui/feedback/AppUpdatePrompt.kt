package cc.novelia.app.ui.feedback

import cc.novelia.app.ui.components.AppTextButton

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.BuildConfig
import cc.novelia.app.data.updates.AppRelease
import cc.novelia.app.data.updates.AppDownloadPhase
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.markdown.MarkdownText
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.downloadAppRelease

@Composable internal fun ObserveAppUpdates(c: AppController, show: Boolean) {
    val release by c.app.appUpdates.available.collectAsStateWithLifecycle()
    val downloadDialog by c.app.appReleaseDownloads.coordinator.dialog.collectAsStateWithLifecycle()
    ObserveAppReleaseDownloads(c, show)
    if(show && downloadDialog == null) release?.let { update ->
        AppUpdateDialog(c, update, c.app.appUpdates::later,
            onIgnore = { c.app.appUpdates.ignore(update) },
            onUpdate = { c.downloadAppRelease(onQueued = c.app.appUpdates::later) })
    }
}

@Composable internal fun AppUpdateDialog(c: AppController, release: AppRelease, onLater: () -> Unit, onIgnore: () -> Unit,
    onUpdate: () -> Unit, downloadError: String? = null) {
    val states by c.app.appReleaseDownloads.coordinator.states.collectAsStateWithLifecycle()
    val preparing = states.values.any { it.phase == AppDownloadPhase.Checking }
    AppAlertDialog(onDismissRequest = onLater, modifier = Modifier.testTag("app-update-dialog"),
        icon = { MidoriIllustration(MidoriSticker.Welcome, Modifier.size(96.dp)) },
        title = { Text("发现新版本 ${release.tag}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("app-update-error")) }
            AppScrollColumn(Modifier.weight(1f, fill = false), contentModifier = Modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前版本 ${BuildConfig.VERSION_NAME}，可以更新啦。")
            release.body?.takeIf(String::isNotBlank)?.let { MarkdownText(c, it, documentUrl = release.url) }
            Text("检查最新版本后下载适合当前设备的安装包，完成后可在应用内安装。", style = MaterialTheme.typography.bodySmall)
            AppTextButton(onClick = onIgnore) { Text("不再显示此版本") }
        } } },
        confirmButton = { AppTextButton(onClick = onUpdate, enabled = !preparing) { Text(if(preparing) "正在准备下载…" else "下载更新") } },
        dismissButton = { AppTextButton(onClick = onLater) { Text("稍后") } })
}

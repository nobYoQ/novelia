package cc.novelia.app.ui.feedback

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.BuildConfig
import cc.novelia.app.data.updates.AppRelease
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.markdown.MarkdownText
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.downloadAppRelease

@Composable internal fun ObserveAppUpdates(c: AppController, show: Boolean) {
    val release by c.app.appUpdates.available.collectAsStateWithLifecycle()
    if(show) release?.let { update ->
        var error by remember(update.tag) { mutableStateOf<String?>(null) }
        AppUpdateDialog(c, update, c.app.appUpdates::later,
            onIgnore = { c.app.appUpdates.ignore(update) },
            onUpdate = {
                error = null
                c.downloadAppRelease(update, onQueued = c.app.appUpdates::later, onError = { error = it })
            }, downloadError = error)
    }
}

@Composable internal fun AppUpdateDialog(c: AppController, release: AppRelease, onLater: () -> Unit, onIgnore: () -> Unit,
    onUpdate: () -> Unit, downloadError: String? = null) {
    val preparing by c.app.appReleaseDownloads.preparing.collectAsStateWithLifecycle()
    AppAlertDialog(onDismissRequest = onLater, modifier = Modifier.testTag("app-update-dialog"),
        icon = { MidoriIllustration(MidoriSticker.Welcome, Modifier.size(96.dp)) },
        title = { Text("发现新版本 ${release.tag}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("app-update-error")) }
            AppScrollColumn(Modifier.weight(1f, fill = false), contentModifier = Modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前版本 ${BuildConfig.VERSION_NAME}，可以更新啦。")
            release.body?.takeIf(String::isNotBlank)?.let { MarkdownText(c, it, documentUrl = release.url) }
            Text("直接下载适合当前设备的安装包，完成后点按系统下载通知安装。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onIgnore) { Text("不再显示此版本") }
        } } },
        confirmButton = { TextButton(onClick = onUpdate, enabled = !preparing) { Text(if(preparing) "正在准备下载…" else "下载更新") } },
        dismissButton = { TextButton(onClick = onLater) { Text("稍后") } })
}

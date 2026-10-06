package cc.novelia.app.ui.feedback

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.BuildConfig
import cc.novelia.app.data.updates.AppRelease
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.navigation.AppController

@Composable internal fun ObserveAppUpdates(c: AppController, show: Boolean) {
    val release by c.app.appUpdates.available.collectAsStateWithLifecycle()
    if(show) release?.let { update -> AppUpdateDialog(update, c.app.appUpdates::later,
        onIgnore = { c.app.appUpdates.ignore(update) },
        onUpdate = { c.external(update.url); c.app.appUpdates.later() }) }
}

@Composable internal fun AppUpdateDialog(release: AppRelease, onLater: () -> Unit, onIgnore: () -> Unit, onUpdate: () -> Unit) {
    AppAlertDialog(onDismissRequest = onLater, modifier = Modifier.testTag("app-update-dialog"),
        icon = { MidoriIllustration(MidoriSticker.Welcome, Modifier.size(96.dp)) },
        title = { Text("发现新版本 ${release.tag}") },
        text = { AppScrollColumn(contentModifier = Modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前版本 ${BuildConfig.VERSION_NAME}，可以更新啦。")
            release.body?.takeIf(String::isNotBlank)?.let { Text(it.take(1600), style = MaterialTheme.typography.bodySmall) }
            Text("直接更新将打开 GitHub Release 页面。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onIgnore) { Text("不再显示此版本") }
        } },
        confirmButton = { TextButton(onClick = onUpdate) { Text("直接更新") } },
        dismissButton = { TextButton(onClick = onLater) { Text("稍后") } })
}

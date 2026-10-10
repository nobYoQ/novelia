package cc.novelia.app.ui.feedback

import cc.novelia.app.ui.components.AppLinearProgressIndicator

import cc.novelia.app.ui.components.AppTextButton

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cc.novelia.app.data.updates.*
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.downloadAppRelease
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable internal fun ObserveAppReleaseDownloads(c: AppController, show: Boolean) {
    val service = c.app.appReleaseDownloads
    val coordinator = service.coordinator
    val states by coordinator.states.collectAsStateWithLifecycle()
    val channel by coordinator.dialog.collectAsStateWithLifecycle()
    val installRequest by service.installRequest.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var permissionChannel by rememberSaveable { mutableStateOf<AppReleaseChannel?>(null) }

    fun openInstaller(target: AppReleaseChannel) { c.action {
        try {
            c.app.startActivity(service.installationIntent(target))
            coordinator.dismiss()
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { coordinator.reportError(target, error) }
        finally { service.finishInstallRequest() }
    } }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val target = permissionChannel
        permissionChannel = null
        if(target != null) {
            if(c.app.packageManager.canRequestPackageInstalls()) openInstaller(target)
            else {
                service.finishInstallRequest()
                coordinator.reportError(target, AppReleaseException("尚未允许安装应用，安装包已保留，可稍后重试"))
            }
        }
    }
    LaunchedEffect(coordinator, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) { coordinator.refresh(); delay(750) }
        }
    }
    LaunchedEffect(installRequest) {
        installRequest?.let { target ->
            service.consumeInstallRequest()
            if(c.app.packageManager.canRequestPackageInstalls()) openInstaller(target)
            else {
                permissionChannel = target
                try { permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.app.packageName}"))) }
                catch(error: Exception) { permissionChannel = null; service.finishInstallRequest(); coordinator.reportError(target, error) }
            }
        }
    }
    if(show) channel?.let { target ->
        AppReleaseDownloadDialog(states.getValue(target),
            onDismiss = coordinator::dismiss,
            onConfirmPreview = { c.action { coordinator.confirmPreview() } },
            onInstall = { service.requestInstall(target) },
            onRetry = { c.downloadAppRelease(preview = target == AppReleaseChannel.Preview) })
    }
}

internal fun appDownloadDescription(state: AppDownloadState, fallback: String): String = when(state.phase) {
    AppDownloadPhase.Checking -> "正在检查最新版本…"
    AppDownloadPhase.Downloading -> "正在下载 ${state.progress?.let { "${(it * 100).toInt()}%" }.orEmpty()} · 点击查看"
    AppDownloadPhase.Ready -> "下载完成 · 点击检查更新并安装"
    AppDownloadPhase.Current -> state.message ?: "当前已是最新版本"
    AppDownloadPhase.Failed -> "下载未完成 · 点击重试"
    else -> fallback
}

@Composable internal fun AppReleaseDownloadDialog(state: AppDownloadState, onDismiss: () -> Unit,
    onConfirmPreview: () -> Unit, onInstall: () -> Unit, onRetry: () -> Unit) {
    val static = appReducedMotion()
    AppAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("app-apk-dialog"),
        title = { Text(when(state.phase) {
            AppDownloadPhase.Checking -> "检查应用更新"
            AppDownloadPhase.PreviewConsent -> "下载预览版本？"
            AppDownloadPhase.Downloading -> "正在下载安装包"
            AppDownloadPhase.Ready -> "下载完成"
            AppDownloadPhase.Current -> "已是最新版本"
            AppDownloadPhase.Failed -> "更新未完成"
            else -> "应用更新"
        }) },
        text = { AppScrollColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.candidate?.let { Text(it.label) }
            when(state.phase) {
                AppDownloadPhase.PreviewConsent -> Text("预览版本不稳定，可能存在功能异常或数据问题。建议先备份阅读资料；测试签名可能无法覆盖正式版。确定后开始下载。")
                AppDownloadPhase.Ready -> Text("安装包已下载并校验完成。点击“立即安装”打开系统安装界面；选择“稍后”会保留安装包，再次点击下载按钮将先检查更新，有更新则下载新包，否则安装已下载版本。")
                AppDownloadPhase.Checking -> Text("正在检查当前版本和最新发行信息…")
                AppDownloadPhase.Downloading -> {
                    if(state.progress != null) AppLinearProgressIndicator(progress = { state.progress!! }, modifier = Modifier.fillMaxWidth().testTag("app-apk-progress"))
                    else if(!static) AppLinearProgressIndicator(Modifier.fillMaxWidth().testTag("app-apk-progress"))
                    Text("${state.progress?.let { "${(it * 100).toInt()}% · " }.orEmpty()}${apkSize(state.bytes)} / ${if(state.total > 0) apkSize(state.total) else "未知大小"}")
                    Text("可在后台继续下载，完成后会提示安装。")
                }
                else -> Unit
            }
            state.message?.let { Text(it, modifier = Modifier.testTag("app-apk-message"),
                color = if(state.phase == AppDownloadPhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        } },
        confirmButton = { when(state.phase) {
            AppDownloadPhase.PreviewConsent -> AppTextButton(onClick = onConfirmPreview) { Text("了解风险，下载") }
            AppDownloadPhase.Ready -> AppTextButton(onClick = onInstall) { Text("立即安装") }
            AppDownloadPhase.Failed -> AppTextButton(onClick = onRetry) { Text("重试") }
            AppDownloadPhase.Current, AppDownloadPhase.Idle -> AppTextButton(onClick = onDismiss) { Text("知道了") }
            else -> Unit
        } },
        dismissButton = { if(state.phase !in listOf(AppDownloadPhase.Current, AppDownloadPhase.Idle)) AppTextButton(onClick = onDismiss) {
            Text(when(state.phase) { AppDownloadPhase.Downloading -> "后台下载"; AppDownloadPhase.PreviewConsent -> "取消"; else -> "稍后" })
        } })
}

private fun apkSize(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f MB", bytes.coerceAtLeast(0) / (1024.0 * 1024.0))

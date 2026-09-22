@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, coil.annotation.ExperimentalCoilApi::class)

package cc.novelia.app.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.SettingsBackup
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.updates.UpdateWorker
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.MetaParagraph
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.TogglePreference
import cc.novelia.app.ui.components.readDocument
import cc.novelia.app.ui.markdown.format
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.reader.ReaderPreferences
import cc.novelia.app.ui.reader.rememberReaderPreferencesState
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

@Composable fun SettingsScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); var reader by remember { mutableStateOf(false) }; var clear by remember { mutableStateOf(false) }; var size by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val preferenceState = rememberReaderPreferencesState()
    val keywordTransfer = rememberKeywordTransfer(c)
    LaunchedEffect(c) { size = withContext(Dispatchers.IO) { c.store.cacheSize() + (c.app.imageLoader.diskCache?.size ?: 0L) } }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> c.message(if(granted) "已允许通知" else "可在系统设置中开启通知") }
    val exportSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { c.action("设置已导出") { val s = c.store.state.value; val backup = SettingsBackup(reader = s.reader, theme = s.theme, reducedMotion = s.reducedMotion, blockedBooks = s.blockedBooks, blockedTags = s.blockedTags, blockedUsers = s.blockedUsers, hideNovelComments = s.hideNovelComments, wifiOnly = s.wifiOnly, autoCollapseCloudFilters = s.autoCollapseCloudFilters); withContext(Dispatchers.IO) { c.app.contentResolver.openOutputStream(it)?.use { output -> output.write(appJson.encodeToString(backup).toByteArray()) } ?: error("无法写入文件") } } } }
    val importSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { c.action("设置已导入") { val backup = withContext(Dispatchers.IO) { appJson.decodeFromString<SettingsBackup>(readDocument(c, it).second.toString(Charsets.UTF_8)) }; require(backup.version == 1 && backup.theme in listOf("system", "light", "dark") && backup.reader.fontSize in 14f..32f && backup.reader.lineHeight in ReaderSettings.LINE_HEIGHT_RANGE && backup.reader.paragraphSpacing in 0f..32f && backup.reader.width in 300f..900f && backup.reader.engines.toSet() == setOf("sakura", "gpt", "youdao")); c.store.update { s -> s.copy(reader = backup.reader, theme = backup.theme, reducedMotion = backup.reducedMotion, blockedBooks = backup.blockedBooks, blockedTags = backup.blockedTags, blockedUsers = backup.blockedUsers, hideNovelComments = backup.hideNovelComments, wifiOnly = backup.wifiOnly, autoCollapseCloudFilters = backup.autoCollapseCloudFilters) } } } }
    Screen("阅读与外观", c::back) { padding -> AppLazyColumn(Modifier.padding(padding)) {
        item { TogglePreference("电子纸阅读模式", "全应用按屏翻动，关闭滚动惯性和动画，使用按钮调整分卷顺序", state.reader.eInkMode) { value -> c.store.update { it.copy(reader = it.reader.withEInkMode(value)) } } }
        item { ChoiceRow("应用主题", listOf("跟随系统", "浅色", "深色"), listOf("system", "light", "dark").indexOf(state.theme)) { index -> c.store.update { it.copy(theme = listOf("system", "light", "dark")[index]) } } }
        item { TogglePreference("减少动态效果", "", state.reducedMotion) { value -> c.store.update { it.copy(reducedMotion = value) } } }
        item { TogglePreference("滚动时自动收起筛选", "云端收藏和网络小说辅助搜索：向下浏览列表时收起，点击摘要展开", state.autoCollapseCloudFilters) { value -> c.store.update { it.copy(autoCollapseCloudFilters = value) } } }
        item { MenuRow("默认阅读偏好", "调整所有小说的阅读体验", Icons.Outlined.TextFields, { reader = true }) }
        item { MenuRow("朗读通知", "允许在通知栏控制朗读", Icons.Outlined.Notifications, { if(Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) else c.message("当前系统无需申请通知权限") }) }
        item { TogglePreference("仅在 Wi-Fi 下载", "新建下载任务等待非计费网络", state.wifiOnly) { value -> c.store.update { it.copy(wifiOnly = value) } } }
        item { TogglePreference("书架更新提醒", "约每 6 小时检查，系统调度可能延后", state.updateNotifications) { value -> c.store.update { it.copy(updateNotifications = value) }; UpdateWorker.schedule(c.app, value); if(value && Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }
        item { TogglePreference("隐藏小说评论", "论坛文章评论仍然显示", state.hideNovelComments) { value -> c.store.update { it.copy(hideNovelComments = value) } } }
        item { MenuRow("阅读与图片缓存", if(clearing) "正在清理…" else size?.let { "已使用 ${"%.1f".format(it / 1024.0 / 1024.0)} MB · 点击清理" } ?: "正在计算缓存大小…", Icons.Outlined.Storage, { if (!clearing) clear = true }) }
        item { MenuRow("导出普通设置", "阅读偏好、外观与屏蔽名单，不含账号会话", Icons.Outlined.IosShare, { exportSettings.launch("novelia-settings.json") }) }
        item { MenuRow("备份与恢复阅读资料", "迁移书架、阅读进度、笔记和本地小说", Icons.Outlined.Backup, { c.go("backup") }) }
        item { MenuRow("导入普通设置", "从 Novelia 设置文件恢复偏好", Icons.Outlined.FileOpen, { importSettings.launch(arrayOf("application/json", "*/*")) }) }
        item { MenuRow("标签库与分类", "搜索全部本地标签，管理译名与分类", Icons.Outlined.Label, { c.go("keywords") }) }
        item { KeywordTransferControls(keywordTransfer) }
        item { MetaParagraph("本地数据", "小说文件、书签和偏好保存在此设备。系统文件选择器负责导入和导出，无需申请全部存储空间权限。卸载应用会删除这些数据，请先导出需要保留的文件。") }
    } }
    if(reader) AppSheet(onDismissRequest = { reader = false }) { ReaderPreferences(state.reader, state = preferenceState) { value -> c.store.update { it.copy(reader = value) } } }
    if(clear) ConfirmDialog("清理阅读与图片缓存？", "已缓存的网络章节、详情和图片会被删除，之后需要联网加载。本地导入的小说和下载文件不受影响。", { clear = false }, confirmLabel = "清理缓存") {
        clearing = true
        c.action("缓存已清理") {
            try {
                size = withContext(Dispatchers.IO) {
                    c.store.clearCache()
                    c.app.imageLoader.memoryCache?.clear()
                    c.app.imageLoader.diskCache?.clear()
                    c.store.cacheSize() + (c.app.imageLoader.diskCache?.size ?: 0L)
                }
            } finally { clearing = false }
        }
    }
}

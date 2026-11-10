@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, coil.annotation.ExperimentalCoilApi::class)

package cc.novelia.app.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import cc.novelia.app.ui.components.documents.DocumentPickerFallback
import cc.novelia.app.ui.components.documents.DocumentPickerTypes
import cc.novelia.app.ui.components.documents.rememberOpenDocumentPicker
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.validFont
import cc.novelia.app.data.model.validNightReading
import cc.novelia.app.data.model.SettingsBackup
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.updates.UpdateWorker
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.account.ProfileChoiceRow
import cc.novelia.app.ui.components.base.ConfirmDialog
import cc.novelia.app.ui.account.ProfileMenuRow
import cc.novelia.app.ui.account.ProfileSummary
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.account.ProfileSectionTitle
import cc.novelia.app.ui.account.ProfileToggle
import cc.novelia.app.ui.components.documents.readDocument
import cc.novelia.app.ui.markdown.format
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.reader.ReaderPreferences
import cc.novelia.app.ui.reader.rememberReaderPreferencesState
import cc.novelia.app.ui.theme.appPageEnterTransition
import cc.novelia.app.ui.theme.appPageExitTransition
import cc.novelia.app.ui.theme.appReducedMotion
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

private enum class SettingsSection(val title: String, val summary: String) {
    READING("阅读与朗读", "排版、配色与电子纸"),
    APPEARANCE("外观与操作", "主题、图标与动效"),
    LIBRARY("书架与下载", "收藏、更新与文件清理"),
    NETWORK("网络与同步", "同步状态、WebDAV 与书源线路"),
    CONTENT("内容与屏蔽", "评论显示、收藏过滤与屏蔽名单"),
    KEYWORDS("标签与词库", "标签分类、容量与词表迁移"),
    STORAGE("存储与备份", "缓存清理、设置迁移与资料备份")
}

@Composable fun SettingsScreen(c: AppController, initialSection: String? = null) {
    var sectionName by rememberSaveable { mutableStateOf(initialSection?.takeIf { name -> SettingsSection.entries.any { it.name == name } }) }
    val selectedSection = SettingsSection.entries.firstOrNull { it.name == sectionName }
    BackHandler(selectedSection != null) { sectionName = null }
    val reducedMotion = appReducedMotion()
    val travel = with(LocalDensity.current) { 24.dp.roundToPx() }
    val pageState = remember { SeekableTransitionState(selectedSection) }
    val pageTransition = rememberTransition(pageState, label = "settings page")
    LaunchedEffect(selectedSection, reducedMotion) {
        // 动画途中切换电子纸/减少动效时直接结束，保留当前页的记忆状态。
        if(reducedMotion) pageState.snapTo(selectedSection) else pageState.animateTo(selectedSection)
    }
    val state by c.store.state.collectAsStateWithLifecycle(); var reader by remember { mutableStateOf(false) }; var clear by remember { mutableStateOf(false) }; var size by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val preferenceState = rememberReaderPreferencesState(reader)
    val keywordTransfer = rememberKeywordTransfer(c)
    val keywordLibrary by c.app.keywords.state.collectAsStateWithLifecycle()
    val cacheRevision by c.store.chapterCacheRevision.collectAsStateWithLifecycle()
    var storageRefresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { storageRefresh++ }
    LaunchedEffect(c, selectedSection, cacheRevision, storageRefresh) { if(selectedSection == SettingsSection.STORAGE) size = withContext(Dispatchers.IO) { c.store.cacheSize() + (c.app.imageLoader.diskCache?.size ?: 0L) } }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> c.message(if(granted) "已允许通知" else "可在系统设置中开启通知") }
    val exportSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { c.action("设置已导出") { val s = c.store.state.value; val backup = SettingsBackup(reader = s.reader, theme = s.theme, reducedMotion = s.reducedMotion, squareCorners = s.squareCorners, amoledBlack = s.amoledBlack, stickerPackId = s.stickerPackId, discoverWenkuGrid = s.discoverWenkuGrid, shelfGrid = s.shelfGrid, localFilesGrid = s.localFilesGrid, cloudShelfGrid = s.cloudShelfGrid, shelfTapToRead = s.shelfTapToRead, rememberLastShelfFolder = s.rememberLastShelfFolder, edgeToolbar = s.edgeToolbar.normalized(), blockedBooks = s.blockedBooks, blockedTags = s.blockedTags, blockedAuthors = s.blockedAuthors, blockedUsers = s.blockedUsers, hideNovelComments = s.hideNovelComments, hideUnavailableComments = s.hideUnavailableComments, hideLocalFavorites = s.hideLocalFavorites, hideCloudFavorites = s.hideCloudFavorites, wifiOnly = s.wifiOnly, autoCollapseCloudFilters = s.autoCollapseCloudFilters, autoSaveCloudFavoritesLocally = s.autoSaveCloudFavoritesLocally, clipboardLinkHints = s.clipboardLinkHints, autoCheckAppUpdates = s.autoCheckAppUpdates, keywordLimit = s.keywordLimit, deleteDownloadAfterImport = s.deleteDownloadAfterImport, deleteLocalCopyOnShelfRemoval = s.deleteLocalCopyOnShelfRemoval); withContext(Dispatchers.IO) { c.app.contentResolver.openOutputStream(it)?.use { output -> output.write(appJson.encodeToString(backup).toByteArray()) } ?: error("无法写入文件") } } } }
    val importSettings = rememberOpenDocumentPicker(DocumentPickerTypes.Json) { uri -> uri?.let { c.action("设置已导入") { val backup = withContext(Dispatchers.IO) { appJson.decodeFromString<SettingsBackup>(readDocument(c, it).second.toString(Charsets.UTF_8)) }; require(backup.edgeToolbar.valid && backup.version == 1 && backup.theme in listOf("system", "light", "dark") && (backup.keywordLimit == null || backup.keywordLimit > 0) && backup.reader.modeConfigurations().all { reader -> reader.fontSize in 14f..32f && reader.lineHeight in ReaderSettings.LINE_HEIGHT_RANGE && reader.paragraphSpacing in 0f..32f && reader.width in 300f..900f && reader.engines.toSet() == setOf("sakura", "gpt", "youdao") && reader.theme in ReaderSettings.THEMES && reader.customColors.valid && reader.validFont && reader.validNightReading && reader.horizontalMargin in 0f..64f && reader.verticalMargin in 0f..64f && reader.orientation in ReaderSettings.ORIENTATIONS && reader.validAutoRead && reader.validIndentSize && reader.validSpeechTimer && reader.validTextReplacements && reader.validReadingStatusItems && reader.validToolbarTransparency }); c.store.update { s -> s.copy(reader = backup.reader, theme = backup.theme, reducedMotion = backup.reducedMotion, squareCorners = backup.squareCorners, amoledBlack = backup.amoledBlack, stickerPackId = backup.stickerPackId, discoverWenkuGrid = backup.discoverWenkuGrid, shelfGrid = backup.shelfGrid, localFilesGrid = backup.localFilesGrid, cloudShelfGrid = backup.cloudShelfGrid, shelfTapToRead = backup.shelfTapToRead, rememberLastShelfFolder = backup.rememberLastShelfFolder, edgeToolbar = backup.edgeToolbar, blockedBooks = backup.blockedBooks, blockedTags = backup.blockedTags, blockedAuthors = backup.blockedAuthors, blockedUsers = backup.blockedUsers, hideNovelComments = backup.hideNovelComments, hideUnavailableComments = backup.hideUnavailableComments, hideLocalFavorites = backup.hideLocalFavorites, hideCloudFavorites = backup.hideCloudFavorites, wifiOnly = backup.wifiOnly, autoCollapseCloudFilters = backup.autoCollapseCloudFilters, autoSaveCloudFavoritesLocally = backup.autoSaveCloudFavoritesLocally, clipboardLinkHints = backup.clipboardLinkHints, autoCheckAppUpdates = backup.autoCheckAppUpdates, keywordLimit = backup.keywordLimit, deleteDownloadAfterImport = backup.deleteDownloadAfterImport, deleteLocalCopyOnShelfRemoval = backup.deleteLocalCopyOnShelfRemoval) } } } }
    pageTransition.AnimatedContent(Modifier.fillMaxSize().clipToBounds(), transitionSpec = {
        val back = targetState == null
        val enter = if(reducedMotion) EnterTransition.None else appPageEnterTransition(travel, back)
        val exit = if(reducedMotion) ExitTransition.None else appPageExitTransition(travel, back)
        (enter togetherWith exit).using(null)
    }) { section ->
    Box(Modifier.fillMaxSize().testTag("settings-page-${section?.name ?: "index"}")
        .then(if(section == selectedSection) Modifier else Modifier.clearAndSetSemantics { })) {
    if(section == SettingsSection.NETWORK) NetworkSyncScreen(c, onBack = { sectionName = null })
    else ProfileDetailScreen(section?.title ?: "设置", { if(section != null) sectionName = null else c.back() }) { padding -> key(section) { ProfileDetailList(Modifier.padding(padding)) {
        if(section == null) {
            SettingsSection.entries.forEach { group -> item {
                val icon = when(group) {
                    SettingsSection.READING -> Icons.Outlined.TextFields
                    SettingsSection.APPEARANCE -> Icons.Outlined.Palette
                    SettingsSection.LIBRARY -> Icons.Outlined.LibraryBooks
                    SettingsSection.NETWORK -> Icons.Outlined.Sync
                    SettingsSection.CONTENT -> Icons.Outlined.FilterAlt
                    SettingsSection.KEYWORDS -> Icons.Outlined.Label
                    SettingsSection.STORAGE -> Icons.Outlined.Backup
                }
                ProfileMenuRow(group.title, group.summary, icon, { sectionName = group.name }, trailing = { Icon(Icons.Outlined.ChevronRight, null) })
            } }
        }
        if(section == SettingsSection.READING) {
        item { ProfileSectionTitle("阅读体验") }
        item { ProfileMenuRow("默认阅读偏好", "字号、排版、自定义配色、翻译与朗读", Icons.Outlined.TextFields, { reader = true }) }
        item { ProfileToggle("电子纸阅读模式", "全应用按屏翻动，关闭滚动惯性和动画，使用按钮调整分卷顺序", state.reader.eInkMode) { value -> c.store.update { it.copy(reader = it.reader.withEInkMode(value)) } } }
        item { ProfileToggle("列表与面板翻屏按钮", "电子纸模式下显示上一屏、下一屏，关闭后仍可滑动翻屏", state.reader.showEInkScreenButtons) { value -> c.store.update { it.copy(reader = it.reader.copy(showEInkScreenButtons = value)) } } }
        item { ProfileToggle("阅读时隐藏状态栏", "阅读时自动隐藏顶部系统状态栏，退出后恢复", state.reader.hideStatusBar) { value -> c.store.update { it.copy(reader = it.reader.copy(hideStatusBar = value)) } } }
        item { ProfileSectionTitle("朗读") }
        item { ProfileMenuRow("朗读通知", "允许在通知栏控制朗读", Icons.Outlined.Notifications, { if(Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) else c.message("当前系统无需申请通知权限") }) }
        }
        if(section == SettingsSection.APPEARANCE) {
        item { ProfileSectionTitle("应用外观") }
        item { ProfileChoiceRow("应用主题", listOf("跟随系统", "浅色", "深色"), listOf("system", "light", "dark").indexOf(state.theme)) { index -> c.store.update { it.copy(theme = listOf("system", "light", "dark")[index]) } } }
        item { ProfileToggle("纯黑显示模式", "深色主题使用纯黑底色，卡片与弹层保留层次", state.amoledBlack) { value -> c.store.update { it.copy(amoledBlack = value) } } }
        item { ProfileToggle("fuck 圆角", "让整个应用变成直角", state.squareCorners) { value -> c.store.update { it.copy(squareCorners = value) } } }
        item { LauncherIconPreference(c.app.launcherIcons) }
        item { StickerPackPreference(state.stickerPackId) { id -> c.store.update { it.copy(stickerPackId = id) } } }
        item { ProfileSectionTitle("交互与提示") }
        item { ProfileMenuRow("边缘工具栏", "快捷入口、圆点位置与闲置半隐藏", Icons.Outlined.Widgets, { c.go("edge-toolbar", replaceTop = true) }) }
        item { ProfileToggle("减少动态效果", "关闭页面转场与连续动画反馈", state.reducedMotion) { value -> c.store.update { it.copy(reducedMotion = value) } } }
        item { ProfileToggle("剪贴板链接提示", "返回应用时识别原站链接，点击提示后打开", state.clipboardLinkHints) { value -> c.store.update { it.copy(clipboardLinkHints = value) } } }
        }
        if(section == SettingsSection.LIBRARY) {
        item { ProfileSectionTitle("书架管理") }
        item { ProfileToggle("点击书架列表直接续读", "书架和云端收藏列表：点封面打开详情，点其余区域从本机进度续读；无进度或网格模式保持原行为", state.shelfTapToRead) { value -> c.store.update { it.copy(shelfTapToRead = value) } } }
        item { ProfileToggle("记住上次打开的收藏夹", "重新打开应用时恢复上次的书架分区和收藏夹", state.rememberLastShelfFolder) { value -> c.store.update { it.copy(rememberLastShelfFolder = value) } } }
        item { ProfileToggle("云端收藏同时保存到本地", "仅影响之后的云端收藏；关闭不会移除已有本地收藏", state.autoSaveCloudFavoritesLocally) { value -> c.store.update { it.copy(autoSaveCloudFavoritesLocally = value) } } }
        item { ProfileToggle("书架更新提醒", "约每 6 小时检查，系统调度可能延后", state.updateNotifications) { value -> c.store.update { it.copy(updateNotifications = value) }; UpdateWorker.schedule(c.app, value); if(value && Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }
        item { ProfileToggle("移出书架时删除本地副本", "仅删除本地小说或分卷的导入副本，原文件不受影响；删除后无法撤销", state.deleteLocalCopyOnShelfRemoval) { value -> c.store.update { it.copy(deleteLocalCopyOnShelfRemoval = value) } } }
        item { ProfileSectionTitle("下载与文件清理") }
        item { ProfileToggle("仅在 Wi-Fi 下载", "新建下载任务等待非计费网络", state.wifiOnly) { value -> c.store.update { it.copy(wifiOnly = value) } } }
        item { ProfileToggle("导入书架后删除下载", "成功导入并保存本地副本后，删除下载管理中的任务和下载文件", state.deleteDownloadAfterImport) { value -> c.store.update { it.copy(deleteDownloadAfterImport = value) } } }
        }
        if(section == SettingsSection.CONTENT) {
        item { ProfileSectionTitle("评论显示") }
        item { ProfileToggle("隐藏小说评论", "论坛文章评论仍然显示", state.hideNovelComments) { value -> c.store.update { it.copy(hideNovelComments = value) } } }
        item { ProfileToggle("不显示已删除或已隐藏的评论", "适用于小说与论坛讨论；一级评论被过滤后，其回复也不显示", state.hideUnavailableComments) { value -> c.store.update { it.copy(hideUnavailableComments = value) } } }
        item { ProfileSectionTitle("已收藏作品过滤") }
        item { ProfileSummary("自动屏蔽已收藏书籍", "在发现、搜索结果和排行榜中自动过滤已收藏作品，本地与云端分别控制。") }
        item { ProfileToggle("屏蔽本地已收藏书籍", "根据本地书架过滤，移出本地收藏后重新显示", state.hideLocalFavorites) { value -> c.store.update { it.copy(hideLocalFavorites = value) } } }
        item { ProfileToggle("屏蔽云端已收藏书籍", "根据当前登录账号的云端收藏过滤，包含待同步的收藏变更", state.hideCloudFavorites) { value -> c.store.update { it.copy(hideCloudFavorites = value) } } }
        item { ProfileSectionTitle("屏蔽名单") }
        item { ProfileMenuRow("屏蔽管理", "管理作品、标签、作者与用户屏蔽", Icons.Outlined.Block, { c.go("blocked") }) }
        }
        if(section == SettingsSection.KEYWORDS) {
        item { ProfileSectionTitle("标签管理") }
        item { ProfileMenuRow("标签库与分类", "搜索全部本地标签，管理译名与分类", Icons.Outlined.Label, { c.go("keywords") }) }
        item { KeywordLimitPreference(state.keywordLimit, keywordLibrary.entries.size) { value -> c.store.update { it.copy(keywordLimit = value) } } }
        item { ProfileSectionTitle("词表迁移") }
        item { KeywordTransferControls(keywordTransfer) }
        }
        if(section == SettingsSection.STORAGE) {
        item { ProfileSectionTitle("缓存管理") }
        item { ProfileMenuRow("书籍章节缓存", "查看每本书的缓存章节和占用空间，按书清理", Icons.Outlined.AutoStories, { c.go("chapter-cache") }) }
        item { ProfileMenuRow("阅读与图片缓存", if(clearing) "正在清理…" else size?.let { "已使用 ${"%.1f".format(it / 1024.0 / 1024.0)} MB · 点击清理" } ?: "正在计算缓存大小…", Icons.Outlined.Storage, { if (!clearing) clear = true }) }
        item { ProfileSectionTitle("普通设置迁移") }
        item { ProfileMenuRow("导出普通设置", "阅读偏好、外观与屏蔽名单，不含账号会话", Icons.Outlined.IosShare, { exportSettings.launch("novelia-settings.json") }) }
        item { ProfileMenuRow("导入普通设置", "从 Novelia 设置文件恢复偏好", Icons.Outlined.FileOpen, { importSettings.launch() }) }
        if(importSettings.showFallback) item { DocumentPickerFallback(importSettings) }
        item { ProfileSectionTitle("阅读资料与数据保留") }
        item { ProfileMenuRow("备份与恢复阅读资料", "迁移书架、阅读进度、笔记和本地小说", Icons.Outlined.Backup, { c.go("backup") }) }
        item { ProfileSummary("卸载时保留应用数据", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "已启用系统保留选项。卸载时请在系统确认框中勾选“保留应用数据”；不勾选会删除应用数据。选项是否显示及默认勾选状态由系统决定。重新安装相同签名的应用可继续使用保留的数据；卸载前仍建议导出阅读资料备份。"
            else "当前 Android 版本不支持卸载时保留应用数据。卸载会删除应用数据，请先导出阅读资料备份，重新安装后导入恢复。") }
        item { ProfileSummary("本地数据", "小说文件、书签和偏好保存在此设备。系统文件选择器负责导入和导出，无需申请全部存储空间权限。换机或更换安装包签名前，请先导出需要保留的阅读资料。") }
        }
    } } }
    }
    }
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

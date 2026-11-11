package cc.novelia.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.reader.*
import cc.novelia.app.ui.account.*
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppRadioButton
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.appRoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun SystemTtsSettingsScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val result by produceState<Result<SystemTtsCatalog>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { runCatching { readSystemTtsCatalog(c.app) } }
    }
    ProfileDetailScreen("系统朗读引擎", c::back, actions = {
        AppIconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "刷新朗读引擎") }
    }) { padding ->
        SystemTtsSettingsContent(result?.getOrNull(), state.ttsEnginePackage,
            loading = result == null, failed = result?.isFailure == true, modifier = Modifier.padding(padding),
            onSelect = { selected -> c.store.update { it.copy(ttsEnginePackage = selected) } },
            onSystemSettings = { if(!openSystemTtsSettings(c.app)) c.message("无法打开系统设置，请手动进入文字转语音设置") },
            onConfigure = { name -> if(!openTtsEngineApp(c.app, name)) c.message("该引擎没有可用的配置入口，请在系统文字转语音设置中配置") },
            onInstallVoice = { name -> if(!openTtsVoiceInstaller(c.app, name)) c.message("该引擎未提供语音安装入口，请打开引擎应用下载或导入语音") })
    }
}

@Composable internal fun SystemTtsSettingsContent(
    catalog: SystemTtsCatalog?, selectedPackage: String?, loading: Boolean = false, failed: Boolean = false,
    modifier: Modifier = Modifier, onSelect: (String?) -> Unit, onSystemSettings: () -> Unit,
    onConfigure: (String) -> Unit, onInstallVoice: (String) -> Unit,
) {
    val engines = catalog?.engines.orEmpty()
    val selected = selectedSystemTtsEngine(selectedPackage, engines)
    val current = engines.firstOrNull { it.packageName == (selected ?: catalog?.systemDefault) }
    val systemLabel = engines.firstOrNull { it.packageName == catalog?.systemDefault }?.label
    ProfileDetailList(modifier.testTag("system-tts-settings").selectableGroup()) {
        item { ProfileSummary("使用系统语音服务", "选择本应用朗读时使用的引擎，下次开始朗读时生效。语速、语言和连续听书仍在阅读偏好中设置。") }
        item { ProfileSectionTitle("已安装引擎") }
        if(loading) item { Text("正在读取系统朗读引擎…", style = MaterialTheme.typography.bodyMedium) }
        if(failed) item { Text("暂时无法读取引擎，请刷新或在系统设置中检查。", color = MaterialTheme.colorScheme.error) }
        item { TtsEngineChoice("跟随系统默认", systemLabel?.let { "当前：$it" } ?: "使用系统选择的朗读引擎", selected == null) { onSelect(null) } }
        if(catalog != null && selectedPackage != null && selected == null) item {
            Text("之前选择的引擎在本机不可用，朗读将使用系统默认引擎。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(engines, key = { it.packageName }) { engine ->
            TtsEngineChoice(engine.label, if(engine.packageName == catalog?.systemDefault) "系统默认引擎" else "已安装的系统 TTS 引擎",
                selected == engine.packageName) { onSelect(engine.packageName) }
        }
        if(catalog != null && engines.isEmpty()) item {
            ProfileSummary("未检测到朗读引擎", "请先安装并配置支持 Android 系统 TTS 的语音引擎，返回后会自动刷新列表。")
        }
        item { ProfileSectionTitle("语音与配置") }
        if(current != null) {
            item { ProfileMenuRow("配置 ${current.label}", "打开引擎应用，选择语言、发音人并试听", Icons.Outlined.Settings,
                { onConfigure(current.packageName) }) }
            item { ProfileMenuRow("安装或管理语音数据", "由当前引擎提供下载或安装界面", Icons.Outlined.Download,
                { onInstallVoice(current.packageName) }) }
        }
        item { ProfileMenuRow("系统文字转语音设置", "查看系统默认引擎与语音设置", Icons.Outlined.RecordVoiceOver, onSystemSettings) }
        item { ProfileSummary("使用第三方引擎", "可自行选择安装 MultiTTS 等支持系统 TTS 的第三方引擎。先在引擎应用中下载或导入语音、配置发音人并试听，再回到这里选择。\n\n只安装引擎应用可能还没有可用语音；中文、日文和音色支持取决于引擎及其语音数据。") }
    }
}

@Composable private fun TtsEngineChoice(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = appRoundedCornerShape(20.dp),
        color = if(selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppRadioButton(selected, onClick = null)
        }
    }
}

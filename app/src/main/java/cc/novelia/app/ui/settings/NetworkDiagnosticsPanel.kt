package cc.novelia.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import cc.novelia.app.data.network.NetworkDiagnosisState
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalSquareCorners
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.appRoundedCornerShape

internal class NetworkDiagnosticsPanelState(
    val tab: MutableState<Int>,
    val reportExpanded: MutableState<Boolean>,
    val connectionExpanded: MutableState<Boolean>,
    val privacyExpanded: MutableState<Boolean>,
    val hasRecorded: MutableState<Boolean>,
)

@Composable
internal fun rememberNetworkDiagnosticsPanelState(recording: Boolean = false) = NetworkDiagnosticsPanelState(
    tab = rememberSaveable { mutableStateOf(if (recording) 1 else 0) },
    reportExpanded = rememberSaveable { mutableStateOf(false) },
    connectionExpanded = rememberSaveable { mutableStateOf(false) },
    privacyExpanded = rememberSaveable { mutableStateOf(false) },
    hasRecorded = rememberSaveable { mutableStateOf(recording) },
)

/** 纯展示层，检测与记录由应用级 transport 持有，离开面板后仍会继续。 */
@Composable
internal fun NetworkDiagnosticsPanel(
    state: NetworkDiagnosticsPanelState,
    recording: Boolean,
    diagnosis: NetworkDiagnosisState,
    exporting: Boolean,
    clearing: Boolean,
    notice: String,
    onRecordingChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    val diagnosticScroll = rememberScrollState()
    val logScroll = rememberScrollState()
    val hasReport = diagnosis.report.isNotBlank()
    LaunchedEffect(recording) { if (recording) state.hasRecorded.value = true }
    val readyToExport = state.hasRecorded.value && !recording
    Column(Modifier.fillMaxWidth().fillMaxHeight(if (appReducedMotion()) 1f else .92f).testTag("network-diagnostics-panel")) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("网络诊断与日志", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text("连接不畅时，从这里排查", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!appReducedMotion()) AppIconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭面板") }
        }
        // 不设固定高度，系统大字号可撑开分段按钮。
        Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = appRoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Row(Modifier.padding(4.dp).height(IntrinsicSize.Min).selectableGroup()) {
                DiagnosticTab("连接诊断", selected = state.tab.value == 0, onClick = { state.tab.value = 0 },
                    modifier = Modifier.weight(1f).fillMaxHeight())
                DiagnosticTab("问题日志", selected = state.tab.value == 1, onClick = { state.tab.value = 1 }, recording = recording,
                    modifier = Modifier.weight(1f).fillMaxHeight().semantics { stateDescription = if (recording) "正在记录" else "未记录" })
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(if (state.tab.value == 0) diagnosticScroll else logScroll)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.tab.value == 0) {
                DiagnosticCard(highlight = true) {
                    DiagnosticHeading(
                        icon = if (diagnosis.running) Icons.Outlined.NetworkCheck else Icons.Outlined.Wifi,
                        title = when {
                            diagnosis.running -> "正在检查连接"
                            hasReport && diagnosis.completed >= diagnosis.total -> "检测已结束"
                            hasReport -> "检测已停止"
                            else -> "先检查连接"
                        },
                        description = when {
                            diagnosis.running -> "最长约 3 分钟，关闭面板后仍会继续。"
                            hasReport -> "查看检测明细；若仍无法使用，可记录一次出错过程。"
                            else -> "对比原站直连与 ECH，检查小说、论坛和认证服务。"
                        },
                    )
                    if (diagnosis.running) {
                        Text("已完成 ${diagnosis.completed} / ${diagnosis.total} 项", style = MaterialTheme.typography.labelLarge)
                        AppLinearProgressIndicator(
                            progress = { (diagnosis.completed.toFloat() / diagnosis.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AppOutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消诊断") }
                    } else {
                        if (!hasReport) Text("约 3 分钟内完成 · 不会更改 ECH 开关", style = MaterialTheme.typography.bodySmall)
                        AppButton(onClick = onStart, enabled = !clearing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.NetworkCheck, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (hasReport) "重新运行诊断" else "运行网络诊断")
                        }
                    }
                }
                if (hasReport) {
                    DiagnosticCard {
                        DiagnosticDisclosure(
                            title = "检测明细",
                            description = "已完成 ${diagnosis.completed} / ${diagnosis.total} 项 · 直连与 ECH 对照",
                            expanded = state.reportExpanded.value,
                            onClick = { state.reportExpanded.value = !state.reportExpanded.value },
                        )
                        if (state.reportExpanded.value) {
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    diagnosis.report.trim().split("\n\n").forEachIndexed { index, block ->
                                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        Text(block, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        AppOutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("复制诊断结果")
                        }
                    }
                }
                DiagnosticCard {
                    DiagnosticDisclosure("检测范围与代理说明", expanded = state.connectionExpanded.value,
                        onClick = { state.connectionExpanded.value = !state.connectionExpanded.value })
                    if (state.connectionExpanded.value) {
                        DiagnosticNote("检测仅针对原站小说、论坛和认证接口，会分别测试直连和 ECH。ECH 开关位于“网络与同步”页面，镜像线路使用普通 HTTPS。")
                        DiagnosticNote("ECH 和加密 DNS 遵循系统 HTTP 代理及绕过规则；VPN 是否接管由 VPN 设置决定。代理不可用时不会自动改走直连。")
                    }
                }
                AppTextButton(onClick = { state.tab.value = 1 }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (recording) "正在记录问题，前往日志" else "某个页面仍出错？记录问题日志")
                }
            } else {
                DiagnosticCard(highlight = true) {
                    DiagnosticHeading(Icons.AutoMirrored.Outlined.ReceiptLong,
                        when { recording -> "正在记录，请复现问题"; readyToExport -> "记录已停止，可以导出"; else -> "收集一次出错过程" },
                        when {
                            recording -> "返回出错页面重试一次，再回到这里停止记录并导出。"
                            readyToExport -> "点击下方“导出网络日志”，保存 ZIP 文件后附在问题反馈中。"
                            else -> "适合页面加载、阅读或下载异常。按下面三步操作即可。"
                        })
                    Text(if (recording) "记录最多持续 30 分钟 · 关闭面板仍会继续" else "日志仅保存在本机，不会自动上传",
                        style = MaterialTheme.typography.bodySmall)
                }
                DiagnosticCard {
                    DiagnosticStep("1", "开启记录", "先保持出错时的网络和 VPN 状态。", active = !recording && !readyToExport)
                    DiagnosticToggle("记录问题复现过程", if (recording) "正在记录 · 复现完成后关闭此开关" else "开启后记录连接过程，30 分钟后自动停止", recording, onRecordingChange)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DiagnosticStep("2", "返回页面，重试一次", "关闭此面板，回到出错的页面执行刚才失败的操作。", active = recording)
                    AppOutlinedButton(onClick = onDismiss, enabled = recording, modifier = Modifier.fillMaxWidth()) { Text("返回页面复现") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DiagnosticStep("3", "回来导出日志", "复现后先停止记录，再保存 ZIP 文件，用于反馈问题。", active = readyToExport)
                    if (recording) AppOutlinedButton(onClick = { onRecordingChange(false) }, modifier = Modifier.fillMaxWidth()) { Text("已复现，停止记录") }
                    AppButton(onClick = onExport, enabled = !exporting && !clearing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.FileDownload, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (exporting) "正在导出…" else "导出网络日志")
                    }
                    DiagnosticNote(when {
                        diagnosis.running -> "诊断仍在运行，现在导出只包含已完成的结果。"
                        recording -> "现在也可导出已有日志，导出不会停止记录。"
                        else -> "已有诊断结果可直接导出，无需重新开启记录。"
                    })
                }
                DiagnosticCard {
                    DiagnosticDisclosure("日志包含什么？", expanded = state.privacyExpanded.value,
                        onClick = { state.privacyExpanded.value = !state.privacyExpanded.value })
                    if (state.privacyExpanded.value) DiagnosticNote("请求日志最多约 1 MiB，包含连接阶段、耗时、接口类别、服务器地址和设备网络概况；不含账号、令牌、Cookie、搜索词及正文。导出包还包含最近的诊断结果。")
                    AppTextButton(onClick = onClear, enabled = !diagnosis.running && !recording && !exporting && !clearing, modifier = Modifier.fillMaxWidth()) {
                        Text(if (clearing) "正在清空…" else "清空网络日志")
                    }
                    if (diagnosis.running || recording) DiagnosticNote("停止诊断和记录后可清空日志。")
                }
            }
        }
        if (notice.isNotBlank()) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(notice, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun DiagnosticCard(highlight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = appRoundedCornerShape(24.dp),
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (LocalEInkMode.current) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
    ) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
private fun DiagnosticTab(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, recording: Boolean = false) {
    Row(
        modifier.clip(appRoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        if (recording) AppBadge()
    }
}

@Composable
private fun DiagnosticHeading(icon: ImageVector, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(28.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
    }
    Text(description, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun DiagnosticStep(number: String, title: String, description: String, active: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = appRoundedCornerShape(10.dp),
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Text(number, style = MaterialTheme.typography.titleSmall) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            DiagnosticNote(description)
        }
    }
}

@Composable
private fun DiagnosticDisclosure(title: String, expanded: Boolean, onClick: () -> Unit, description: String? = null) {
    AppTextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = appRoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (description != null) DiagnosticNote(description)
        }
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起" else "展开")
    }
}

@Composable
private fun DiagnosticToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(appRoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            DiagnosticNote(subtitle)
        }
        if (LocalEInkMode.current && !LocalSquareCorners.current) Icon(
            if (checked) Icons.Outlined.ToggleOn else Icons.Outlined.ToggleOff, null, Modifier.size(48.dp),
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        ) else AppSwitch(checked, onCheckedChange = null)
    }
}

@Composable
private fun DiagnosticNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

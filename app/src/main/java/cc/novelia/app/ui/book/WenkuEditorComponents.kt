package cc.novelia.app.ui.book

import cc.novelia.app.ui.theme.appPanelShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cc.novelia.app.data.model.WenkuVolume
import cc.novelia.app.ui.account.ProfileDetailCard
import cc.novelia.app.ui.account.ProfileSectionTitle
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.appRoundedCornerShape
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
internal fun WenkuEditorPage(
    title: String,
    onBack: () -> Unit,
    saving: Boolean,
    canSave: Boolean,
    saveHint: String,
    onSave: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Screen(title, onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("wenku-editor")) {
                AppScrollColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("wenku-editor-scroll"),
                    contentModifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = appRoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.AutoStories, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("完善作品资料", style = MaterialTheme.typography.titleLarge)
                            Text("草稿自动保存在此设备", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    content()
                }
                Surface {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(saveHint, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AppButton(onSave, enabled = canSave && !saving,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("wenku-save")) {
                                if(saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.CloudUpload, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if(saving) "保存中…" else "保存到原站")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WenkuEditorSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ProfileDetailCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
internal fun WenkuEditorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    minLines: Int = 1,
    singleLine: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
) {
    AppTextField(value, onValueChange, label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        minLines = minLines, singleLine = singleLine, isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = hint?.let { { Text(it) } })
}

/** 宽屏并列短字段，窄屏或大字号时保持完整标签和输入空间。 */
@Composable
internal fun WenkuEditorFieldPair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 改变每行数量时保留输入节点，以便正在进行的焦点和配色动画继续使用同一字段。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = if (maxWidth / fontScale >= 520.dp) 2 else 1,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.weight(1f)) { first() }
            Box(Modifier.weight(1f)) { second() }
        }
    }
}

@Composable
internal fun WenkuEditorVolumes(
    volumes: List<WenkuVolume>,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProfileSectionTitle("出版分卷 · ${volumes.size}", "添加分卷", onAdd)
        ProfileDetailCard {
            if(volumes.isEmpty()) Column(Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.AutoMirrored.Outlined.LibraryBooks, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("暂无出版分卷", style = MaterialTheme.typography.titleSmall)
                Text("可添加已核实的卷名、ASIN 和出版信息，也可以稍后补充。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else volumes.forEachIndexed { index, volume ->
                if(index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(volume.titleZh ?: volume.title, style = MaterialTheme.typography.titleSmall,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(volume.asin, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    AppIconButton(onClick = { onEdit(index) }) { Icon(Icons.Outlined.Edit, "编辑分卷") }
                    AppIconButton(onClick = { onRemove(index) }) { Icon(Icons.Outlined.DeleteOutline, "移除分卷信息") }
                }
            }
        }
        Text("本页支持手动填写出版资料；日亚资料导入需使用原站扩展。", Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun WenkuVolumeEditorDialog(existing: WenkuVolume, onDismiss: () -> Unit, onSave: (WenkuVolume) -> Unit) {
    var asin by rememberSaveable { mutableStateOf(existing.asin) }
    var name by rememberSaveable { mutableStateOf(existing.title) }
    var translated by rememberSaveable { mutableStateOf(existing.titleZh.orEmpty()) }
    var image by rememberSaveable { mutableStateOf(existing.cover.orEmpty()) }
    var publisher by rememberSaveable { mutableStateOf(existing.publisher.orEmpty()) }
    var imprint by rememberSaveable { mutableStateOf(existing.imprint.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(existing.publishAt?.let {
        Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).toLocalDate().toString()
    }.orEmpty()) }
    val timestamp = date.takeIf(String::isNotBlank)?.let {
        runCatching { LocalDate.parse(it).atStartOfDay().toEpochSecond(ZoneOffset.UTC) }.getOrNull()
    }
    val invalidDate = date.isNotBlank() && timestamp == null
    val invalidCover = image.isNotBlank() && !image.startsWith("https://")
    AppDialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center) {
            val compact = maxHeight / LocalDensity.current.fontScale.coerceAtLeast(1f) < 360.dp
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = appPanelShape(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = if(compact) 8.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if(compact) 8.dp else 16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if(!compact) Icon(Icons.AutoMirrored.Outlined.LibraryBooks, null, tint = MaterialTheme.colorScheme.primary)
                        Text("出版分卷", style = if(compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                    }
                    AppScrollColumn(modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                        contentModifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ASIN 和原文卷名为必填项，其余资料可稍后补充。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        WenkuEditorField(asin, { asin = it.trim() }, "ASIN", Modifier.testTag("wenku-volume-asin"), singleLine = true)
                        WenkuEditorField(name, { name = it }, "原文卷名", Modifier.testTag("wenku-volume-title"))
                        WenkuEditorField(translated, { translated = it }, "中文卷名")
                        WenkuEditorField(image, { image = it }, "封面链接", keyboardType = KeyboardType.Uri,
                            isError = invalidCover, hint = if(invalidCover) "请使用 HTTPS 链接" else "选填，支持 HTTPS 链接")
                        WenkuEditorField(publisher, { publisher = it }, "出版社")
                        WenkuEditorField(imprint, { imprint = it }, "文库品牌")
                        WenkuEditorField(date, { date = it }, "出版日期", singleLine = true,
                            modifier = Modifier.testTag("wenku-volume-date"), isError = invalidDate,
                            hint = if(invalidDate) "请填写有效日期，例如 2026-01-01" else "选填，格式为 YYYY-MM-DD")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        AppTextButton(onClick = onDismiss) { Text("取消") }
                        AppTextButton(onClick = {
                            onSave(existing.copy(asin = asin, title = name, titleZh = translated.ifBlank { null },
                                cover = image.ifBlank { null }, publisher = publisher.ifBlank { null },
                                imprint = imprint.ifBlank { null }, publishAt = timestamp))
                        }, enabled = asin.isNotBlank() && name.isNotBlank() && !invalidCover && !invalidDate) { Text("保存分卷") }
                    }
                }
            }
        }
    }
}

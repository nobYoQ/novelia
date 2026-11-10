package cc.novelia.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.account.*
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.feedback.Sticker
import cc.novelia.app.ui.feedback.StickerIllustration
import cc.novelia.app.ui.feedback.StickerPack
import cc.novelia.app.ui.theme.appRoundedCornerShape

@Composable
internal fun StickerPackDetailScreen(pack: StickerPack, active: Boolean, busy: Boolean, error: String?, onBack: () -> Unit,
    onUse: () -> Unit, onEdit: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    var menu by rememberSaveable(pack.id) { mutableStateOf(false) }
    BackHandler { if(!busy) onBack() }
    ProfileDetailScreen("贴纸预览", { if(!busy) onBack() }, actions = {
        if(pack.custom) Box {
            AppIconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Outlined.MoreVert, "贴纸集更多操作") }
            AppDropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("导出贴纸包") }, leadingIcon = { Icon(Icons.Outlined.IosShare, null) },
                    onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("删除贴纸集", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val columns = stickerGridColumns(maxWidth)
                ProfileDetailList {
                    item(key = "overview") { ProfileDetailCard {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StickerBadge(if(pack.custom) "自定义贴纸集" else "内置贴纸集")
                                if(active) StickerBadge("正在使用", highlighted = true)
                            }
                            Text(pack.title, style = MaterialTheme.typography.titleLarge)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                StickerIllustration(Sticker.Neutral, Modifier.size(88.dp), pack)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(pack.companionName, style = MaterialTheme.typography.titleMedium)
                                    Text("已配置 ${pack.expressionCount} 个表情",
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("用于动态icon、默认封面与操作提示", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } }
                    item(key = "expressions-title") { ProfileSectionTitle("全部表情") }
                    items(Sticker.entries.chunked(columns), key = { "expressions-${it.first().key}" }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { slot ->
                                Surface(Modifier.weight(1f), shape = appRoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow, border = profileCardBorder()) {
                                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        StickerIllustration(slot, Modifier.fillMaxWidth().height(80.dp), pack)
                                        Text(slot.title, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                                        if(pack.custom) Text(if(pack.contains(slot)) "独立图片" else "使用常态图", style = MaterialTheme.typography.labelSmall,
                                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if(pack.expressionCount < Sticker.entries.size) item(key = "fallback") {
                        Text("未单独设置的表情会沿用常态图片。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            StickerPageFooter(if(active) "正在使用" else "使用这套贴纸", onUse, enabled = !active,
                busy = busy, error = error, secondary = if(pack.custom) "编辑贴纸集" else null, onSecondary = onEdit)
        }
    }
}

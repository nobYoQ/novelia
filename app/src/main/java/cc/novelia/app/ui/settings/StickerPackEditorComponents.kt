@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.account.*
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.feedback.Sticker
import cc.novelia.app.ui.theme.appRoundedCornerShape
import coil.compose.AsyncImage
import java.io.File

@Composable
internal fun StickerEditorSummary(title: String, neutral: File?, count: Int, onDetails: () -> Unit) {
    ProfileDetailCard {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val imageSize = if(maxWidth / LocalDensity.current.fontScale < 330.dp) 56.dp else 76.dp
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = appRoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.size(imageSize).padding(8.dp), contentAlignment = Alignment.Center) {
                        if(neutral != null) AsyncImage(neutral, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else Icon(Icons.Outlined.EmojiEmotions, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title.ifBlank { "未命名贴纸集" }, style = MaterialTheme.typography.titleMedium)
                    Text("$count / 13 个表情", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppIconButton(onClick = onDetails) { Icon(Icons.Outlined.Edit, "编辑名称与配色") }
            }
        }
    }
}

@Composable
internal fun StickerEditorTile(slot: Sticker, file: File?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.testTag("sticker-choose-${slot.key}"),
        shape = appRoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if(slot == Sticker.Neutral && file == null) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else profileCardBorder()) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                if(file != null) AsyncImage(file, "${slot.title}预览", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(slot.title, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Text(if(slot == Sticker.Neutral) if(file == null) "必需" else "必需 · 已添加" else if(file == null) "可选" else "已添加",
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                color = if(slot == Sticker.Neutral) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun StickerImageSlotSheet(slot: Sticker, file: File?, onDismiss: () -> Unit, onChoose: () -> Unit, onRemove: () -> Unit) {
    AppSheet(onDismissRequest = onDismiss) {
        AppScrollColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentModifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${slot.title}表情", style = MaterialTheme.typography.titleLarge)
            Surface(Modifier.fillMaxWidth(), shape = appRoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(Modifier.fillMaxWidth().height(180.dp).padding(16.dp), contentAlignment = Alignment.Center) {
                    if(file != null) AsyncImage(file, "${slot.title}大图预览", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(if(slot == Sticker.Neutral) "这张图片是整套贴纸的基础，未单独设置的表情也会使用它。"
                else "可以添加独立图片，也可以留空并使用常态图。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppButton(onChoose, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if(file == null) "选择图片" else "替换图片") }
            if(file != null) AppTextButton(onRemove, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sticker-remove-${slot.key}"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("移除这张图片") }
        }
    }
}

@Composable
internal fun StickerPackNameFields(title: String, onTitle: (String) -> Unit, companionName: String, onCompanion: (String) -> Unit, enabled: Boolean) {
    ProfileDetailCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(title, onTitle, enabled = enabled, label = { Text("贴纸集名称") }, singleLine = true,
                supportingText = { Text("${title.length} / 40") }, modifier = Modifier.fillMaxWidth().testTag("sticker-pack-title"))
            OutlinedTextField(companionName, onCompanion, enabled = enabled, label = { Text("动态icon名称") }, singleLine = true,
                placeholder = { Text("留空时使用贴纸集名称") }, supportingText = { Text("动态icon的称呼 · 最多 24 字") },
                modifier = Modifier.fillMaxWidth().testTag("sticker-pack-companion-name"))
        }
    }
}

@Composable
internal fun StickerHeartColorPreference(colorCode: String, enabled: Boolean, onPick: () -> Unit) {
    val color = colorCode.removePrefix("#").toLongOrNull(16)?.takeIf { colorCode.matches(Regex("#[0-9a-fA-F]{6}")) }
        ?.let { Color(0xFF000000L or it) } ?: Color(0xFFE15C86)
    Surface(onClick = onPick, enabled = enabled, shape = appRoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, border = profileCardBorder(),
        modifier = Modifier.fillMaxWidth().testTag("sticker-pack-heart-color")) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.Favorite, null, Modifier.size(32.dp), tint = color)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("爱心颜色", style = MaterialTheme.typography.titleMedium)
                Text("互动时的爱心 · 点击选色", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun StickerImportHelp(onDismiss: () -> Unit) {
    AppSheet(onDismissRequest = onDismiss) {
        AppScrollColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp), contentModifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("制作小提示", style = MaterialTheme.typography.titleLarge)
            Text("先添加常态图", style = MaterialTheme.typography.titleMedium)
            Text("只需一张常态图就能保存。其他 12 个表情按需添加，留空时会沿用常态图片。")
            Text("图片要求", style = MaterialTheme.typography.titleMedium)
            Text("支持 PNG、WebP、JPG，推荐透明背景。单张最多 8 MB，边长最多 4096 像素；整套最多 64 MB。")
            Text("批量添加", style = MaterialTheme.typography.titleMedium)
            Text("优先匹配英文或中文文件名，例如 neutral.png 对应常态，开心.webp 对应开心。其余图片会按选择顺序填入空位，可再逐个调整。")
            Text("草稿与保存", style = MaterialTheme.typography.titleMedium)
            Text("返回会保留草稿，保存前不会改变已启用的贴纸集。保存后可以在预览页导出贴纸包，方便迁移到其他设备。")
        }
    }
}

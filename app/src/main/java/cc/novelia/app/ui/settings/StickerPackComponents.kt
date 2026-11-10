package cc.novelia.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.account.profileCardBorder
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.feedback.Sticker
import cc.novelia.app.ui.feedback.StickerIllustration
import cc.novelia.app.ui.feedback.StickerPack
import cc.novelia.app.ui.theme.appRoundedCornerShape

@Composable
internal fun StickerBadge(text: String, highlighted: Boolean = false) {
    Surface(shape = appRoundedCornerShape(8.dp),
        color = if(highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if(highlighted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun stickerGridColumns(width: Dp): Int =
    ((width - 40.dp) / (104.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))).toInt().coerceIn(2, 5)

@Composable
internal fun StickerPackCard(pack: StickerPack, active: Boolean, enabled: Boolean, onSelect: () -> Unit, onPreview: (() -> Unit)?) {
    Surface(shape = appRoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if(active) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else profileCardBorder(), modifier = Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.fillMaxWidth().testTag("sticker-pack-${pack.id}")
                .selectable(active, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pack.title, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StickerBadge(if(active) "正在使用" else if(pack.custom) "自定义" else "内置", active)
                            Text("${pack.expressionCount} 个表情", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    AppRadioButton(active, onClick = null, enabled = enabled)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(Sticker.Neutral, Sticker.Reading, Sticker.Celebrate).forEach { slot ->
                        Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surface,
                            shape = appRoundedCornerShape(18.dp)) {
                            StickerIllustration(slot, Modifier.fillMaxWidth().height(82.dp).padding(8.dp), pack)
                        }
                    }
                }
            }
            if(onPreview != null) {
                HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) {
                    AppTextButton(onClick = onPreview, enabled = enabled, modifier = Modifier.testTag("sticker-preview-${pack.id}")) {
                        Text("预览与管理")
                    }
                }
            }
        }
    }
}

@Composable
internal fun StickerActionButtons(primary: String, onPrimary: () -> Unit, enabled: Boolean = true,
    secondary: String? = null, onSecondary: () -> Unit = {}, secondaryEnabled: Boolean = enabled,
    primaryModifier: Modifier = Modifier, secondaryModifier: Modifier = Modifier,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth / LocalDensity.current.fontScale < 300.dp
        val primaryButton: @Composable (Modifier) -> Unit = { modifier ->
            AppButton(onPrimary, modifier.then(primaryModifier).heightIn(min = 48.dp), enabled = enabled) { Text(primary, textAlign = TextAlign.Center) }
        }
        val secondaryButton: @Composable (Modifier) -> Unit = { modifier ->
            AppOutlinedButton(onSecondary, modifier.then(secondaryModifier).heightIn(min = 48.dp), enabled = secondaryEnabled) { Text(secondary.orEmpty(), textAlign = TextAlign.Center) }
        }
        if(secondary == null) primaryButton(Modifier.fillMaxWidth())
        else if(stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            primaryButton(Modifier.fillMaxWidth())
            secondaryButton(Modifier.fillMaxWidth())
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            primaryButton(Modifier.weight(1f))
            secondaryButton(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun StickerPageFooter(primary: String, onPrimary: () -> Unit, enabled: Boolean, busy: Boolean = false,
    note: String? = null, error: String? = null, secondary: String? = null, onSecondary: () -> Unit = {},
    modifier: Modifier = Modifier, primaryModifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                note?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                if(busy) AppLinearProgressIndicator(Modifier.fillMaxWidth())
                StickerActionButtons(primary, onPrimary, enabled && !busy, secondary, onSecondary, !busy, primaryModifier)
            }
        }
    }
}

package cc.novelia.app.ui.components.base

import androidx.compose.foundation.layout.*
import cc.novelia.app.ui.theme.appRoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.feedback.MidoriSticker
import cc.novelia.app.ui.feedback.StickerAccent
import cc.novelia.app.ui.theme.MotionContent

@Composable fun EmptyState(title: String, message: String, icon: ImageVector = Icons.Outlined.AutoStories, action: String? = null, onAction: () -> Unit = {}, sticker: MidoriSticker? = null) {
    MotionContent(Unit, Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(sticker != null) StickerAccent(sticker, modifier = Modifier.size(112.dp))
            else Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = appRoundedCornerShape(28.dp), modifier = Modifier.size(88.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) } }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(action != null) AppFilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

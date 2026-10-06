package cc.novelia.app.startup

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.AppScrollColumn

@Composable internal fun StartupScreen(progress: StartupProgress, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            AppScrollColumn(modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                contentModifier = Modifier.padding(28.dp)) {
                Icon(Icons.Outlined.LibraryBooks, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(20.dp))
                Text("正在打开 Novelia", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(if(progress.failed) "${progress.step.title}未完成，请重试。" else progress.step.description,
                    Modifier.testTag("startup-current-step"), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(24.dp))
                // 使用离散进度，不添加强制等待或持续旋转动画，电子纸也能明确显示状态。
                LinearProgressIndicator(progress = { progress.completed / StartupStep.READY.ordinal.toFloat() },
                    modifier = Modifier.fillMaxWidth().testTag("startup-progress"))
                Spacer(Modifier.height(16.dp))
                StartupStep.entries.filter { it != StartupStep.READY }.forEach { step ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if(step.ordinal < progress.completed) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        else Text("${step.ordinal + 1}", Modifier.width(20.dp), style = MaterialTheme.typography.labelLarge)
                        Text(step.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(when { step.ordinal < progress.completed -> "已完成"; step == progress.step -> if(progress.failed) "待重试" else "正在加载"; else -> "等待中" },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if(progress.failed) Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("重试加载") }
            }
        }
    }
}

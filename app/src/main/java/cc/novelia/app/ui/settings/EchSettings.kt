@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.network.EchTransport
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.TogglePreference
import kotlinx.coroutines.launch

@Composable internal fun EchSettings(transport: EchTransport, onDismiss: () -> Unit) {
    val enabled by transport.enabled.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    AppSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ECH 连接测试", style = MaterialTheme.typography.titleLarge)
            TogglePreference("启用 ECH", "对小说、论坛和认证接口使用加密握手", enabled, transport::setEnabled)
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        result = transport.diagnose()
                    } finally { busy = false }
                }
            }) { Text(if (busy) "正在诊断…" else "运行 ECH 连接诊断") }
            if (result.isNotEmpty()) {
                Text(result, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ECH 连接诊断", result))
                }) { Text("复制诊断结果") }
            }
        }
    }
}

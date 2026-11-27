package cc.novelia.app.ui.components.documents

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.theme.appRoundedCornerShape
import cc.novelia.app.ui.theme.monochromeBorder

internal class FilteredDocumentPicker(
    val launch: () -> Unit,
    val showFallback: Boolean,
    val launchAllFiles: () -> Unit,
    val dismissFallback: () -> Unit,
)

@Composable internal fun rememberOpenDocumentPicker(mimeTypes: List<String>, onResult: (Uri?) -> Unit): FilteredDocumentPicker =
    rememberFilteredDocumentPicker(ActivityResultContracts.OpenDocument(), mimeTypes, { it == null }, onResult)

@Composable internal fun rememberOpenMultipleDocumentsPicker(mimeTypes: List<String>, onResult: (List<Uri>) -> Unit): FilteredDocumentPicker =
    rememberFilteredDocumentPicker(ActivityResultContracts.OpenMultipleDocuments(), mimeTypes, { it.isEmpty() }, onResult)

@Composable private fun <T> rememberFilteredDocumentPicker(
    contract: ActivityResultContract<Array<String>, T>, mimeTypes: List<String>, isEmpty: (T) -> Boolean, onResult: (T) -> Unit,
): FilteredDocumentPicker {
    // 工具切换格式时清除旧提示；旋转或从系统选择器返回时保留当前提示。
    var showFallback by rememberSaveable(mimeTypes) { mutableStateOf(false) }
    val filtered = rememberLauncherForActivityResult(contract) { result ->
        showFallback = isEmpty(result)
        onResult(result)
    }
    val allFiles = rememberLauncherForActivityResult(contract, onResult)
    return FilteredDocumentPicker(
        launch = { showFallback = false; filtered.launch(mimeTypes.toTypedArray()) },
        showFallback = showFallback,
        launchAllFiles = { showFallback = false; allFiles.launch(arrayOf("*/*")) },
        dismissFallback = { showFallback = false },
    )
}

@Composable internal fun DocumentPickerFallback(picker: FilteredDocumentPicker, enabled: Boolean = true, modifier: Modifier = Modifier) {
    if(!picker.showFallback) return
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = appRoundedCornerShape(20.dp), color = colors.surfaceContainerHigh, border = monochromeBorder()) {
        Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(enabled = enabled, role = Role.Button, onClick = picker.launchAllFiles)
                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = appRoundedCornerShape(12.dp), color = colors.secondaryContainer) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(22.dp), tint = colors.onSecondaryContainer)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("未选择文件", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val tint = if(enabled) colors.primary else colors.onSurface.copy(alpha = .38f)
                        Text("显示所有文件", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, color = tint)
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp), tint = tint)
                    }
                }
            }
            AppIconButton(onClick = picker.dismissFallback) { Icon(Icons.Outlined.Close, "关闭导入提示", Modifier.size(20.dp)) }
        }
    }
}

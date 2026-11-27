package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.SearchExpression
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.theme.appRoundedCornerShape
import cc.novelia.app.ui.theme.monochromeBorder

/** 预览占据固定行数；完整表达式单独查看，避免条件增长挤动正在编辑的表单。 */
@Composable internal fun SearchAssistantPreview(query: String, generated: String, compact: Boolean, hasError: Boolean) {
    var detailsOpen by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("assistant-preview-bar"),
        shape = appRoundedCornerShape(16.dp), border = monochromeBorder(),
        color = if(hasError) colors.errorContainer else colors.surfaceContainerHigh) {
        Row(Modifier.heightIn(min = if(compact) 48.dp else 60.dp).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if(hasError) Icons.Outlined.ErrorOutline else Icons.Outlined.Code, if(hasError) "条件需调整" else "条件预览",
                Modifier.size(20.dp), tint = if(hasError) colors.error else colors.onSurfaceVariant)
            Text(generated.ifBlank { query.ifBlank { "暂无条件" } }, Modifier.weight(1f).testTag("assistant-preview"),
                style = MaterialTheme.typography.bodyMedium,
                color = if(hasError) colors.onErrorContainer else colors.onSurfaceVariant,
                maxLines = if(compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            AppIconButton(onClick = { focus.clearFocus(); keyboard?.hide(); detailsOpen = true }, enabled = generated.isNotBlank() || query.isNotBlank(),
                modifier = Modifier.testTag("assistant-preview-details")) {
                Icon(Icons.Outlined.UnfoldMore, "查看完整条件")
            }
        }
    }
    if(detailsOpen) AppAlertDialog(onDismissRequest = { detailsOpen = false }, title = { Text("条件预览") }, text = {
        AppScrollColumn(Modifier.heightIn(max = 360.dp)) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if(generated.isNotBlank()) {
                        Text("生成的条件", style = MaterialTheme.typography.labelLarge)
                        Text(generated, Modifier.testTag("assistant-preview-full"), style = MaterialTheme.typography.bodyMedium)
                    }
                    if(query.isNotBlank()) {
                        if(generated.isNotBlank()) HorizontalDivider()
                        Text(if(generated.isBlank()) "当前搜索" else "追加后的搜索", style = MaterialTheme.typography.labelLarge)
                        Text(SearchExpression.append(query, generated), Modifier.testTag("assistant-preview-appended"),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }, confirmButton = { AppTextButton(onClick = { detailsOpen = false }) { Text("返回编辑") } })
}

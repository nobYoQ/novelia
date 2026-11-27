@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import cc.novelia.app.ui.theme.DefaultShapes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.*

/** 三个书架分区共用紧凑面板，草稿由调用方保存，底部确认区始终可达。 */
@Composable internal fun ShelfFilterSheet(title: String, prefix: String, maxHeight: Dp, valid: Boolean = true,
    onDismiss: () -> Unit, onReset: () -> Unit, onApply: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AppSheet(onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = maxHeight).imePadding().testTag("$prefix-filter-sheet")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                AppIconButton(onDismiss, Modifier.testTag("$prefix-filter-close"), shape = DefaultShapes.medium) {
                    Icon(Icons.Outlined.Close, "关闭筛选", Modifier.size(20.dp))
                }
            }
            AppScrollColumn(Modifier.weight(1f, fill = false),
                contentModifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 16.dp), content = content)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppOutlinedButton(onReset, Modifier.weight(1f).heightIn(min = 48.dp).testTag("$prefix-filter-reset"),
                    shape = RoundedCornerShape(12.dp)) { Text("重置") }
                AppButton(onApply, Modifier.weight(2f).heightIn(min = 48.dp).testTag("$prefix-filter-apply"), enabled = valid,
                    shape = RoundedCornerShape(12.dp)) { Text("应用筛选") }
            }
        }
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.MotionContent

@Composable fun PageControls(page: Int, count: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onChange(page - 1) }, enabled = page > 0) { Text("上一页") }
        MotionContent(page, animateInitial = false) { Text("${page + 1} / ${count.coerceAtLeast(1)}", style = MaterialTheme.typography.labelLarge) }
        OutlinedButton(onClick = { onChange(page + 1) }, enabled = page + 1 < count) { Text("下一页") }
    }
}

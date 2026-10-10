@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.components

import cc.novelia.app.ui.components.AppTextButton
import cc.novelia.app.ui.components.AppIconButton

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/** 页码从 0 开始；null 表示两端页码与当前页窗口之间的省略区间。 */
internal fun paginationItems(page: Int, count: Int): List<Int?> {
    val total = count.coerceAtLeast(1)
    if (total <= 7) return (0 until total).toList()
    val current = page.coerceIn(0, total - 1)
    val start = (current - 1).coerceIn(1, total - 4)
    val end = start + 2
    return buildList {
        add(0)
        if (start > 1) add(null)
        addAll(start..end)
        if (end < total - 2) add(null)
        add(total - 1)
    }
}

@Composable fun PageControls(page: Int, count: Int, onChange: (Int) -> Unit) {
    val total = count.coerceAtLeast(1)
    val current = page.coerceIn(0, total - 1)
    var input by rememberSaveable(page, count) { mutableStateOf("") }
    val target = input.toIntOrNull()?.takeIf { it in 1..total }
    val invalid = input.isNotEmpty() && target == null
    val focus = LocalFocusManager.current
    fun jump() {
        target?.let {
            focus.clearFocus()
            input = ""
            if (it - 1 != current) onChange(it - 1)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            paginationItems(current, total).forEach { item ->
                if (item == null) Box(Modifier.width(20.dp).height(48.dp), contentAlignment = Alignment.Center) { Text("…") }
                else {
                    val active = item == current
                    AppTextButton(onClick = { if (!active) onChange(item) },
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics {
                            selected = active
                            contentDescription = "第 ${item + 1} 页"
                        },
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)) {
                        Text("${item + 1}")
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppIconButton(onClick = { onChange(current - 1) }, enabled = current > 0) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上一页")
            }
            Text("第 ${current + 1} / $total 页", style = MaterialTheme.typography.labelMedium)
            AppIconButton(onClick = { onChange(current + 1) }, enabled = current < total - 1) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一页")
            }
        }
        Row(Modifier.widthIn(max = 320.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), singleLine = true, label = { Text("跳转页码") },
                placeholder = { Text("1–$total") }, isError = invalid,
                supportingText = if (invalid) ({ Text("请输入 1–$total 的页码") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { jump() }))
            AppTextButton(onClick = { jump() }, enabled = target != null) { Text("跳转") }
        }
    }
}

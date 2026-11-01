package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.theme.appRoundedCornerShape
import cc.novelia.app.ui.theme.motionClickable

/** 设置列表只展示当前值；编辑窗口提交有效格数后才重新排版。 */
@Composable internal fun ReaderIndentPreference(value: Int, defaultValue: Int, onChange: (Int) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val label = if(value == 0) "不缩进" else "$value 格"
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("reader-indent-setting")
        .semantics { role = Role.Button; stateDescription = label }
        .motionClickable { editing = true }.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("首行缩进", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Surface(shape = appRoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp))
            }
        }
    }
    if(editing) {
        var input by rememberSaveable(value) { mutableStateOf(value.toString()) }
        val parsed = input.toIntOrNull()?.takeIf { it in ReaderSettings.INDENT_SIZE_RANGE }
        val focus = remember { FocusRequester() }
        fun save(size: Int) { onChange(size); editing = false }
        AppAlertDialog(onDismissRequest = { editing = false }, title = { Text("首行缩进") }, text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("每格相当于一个全角字符，0 表示不缩进。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(input, { input = it }, Modifier.width(128.dp).focusRequester(focus).testTag("reader-indent-input"),
                    label = { Text("格数") }, suffix = { Text("格") }, singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
                    isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { parsed?.let(::save) }))
                Text(if(parsed == null) "请输入 0–20 之间的整数" else "可设置 0–20 格",
                    style = MaterialTheme.typography.bodySmall,
                    color = if(parsed == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                AppTextButton(onClick = { save(defaultValue) }, modifier = Modifier.testTag("reader-indent-default")) {
                    Text("恢复默认：$defaultValue 格")
                }
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }, confirmButton = {
            AppTextButton(onClick = { parsed?.let(::save) }, enabled = parsed != null,
                modifier = Modifier.testTag("reader-indent-apply")) { Text("保存") }
        }, dismissButton = {
            AppTextButton(onClick = { editing = false }, modifier = Modifier.testTag("reader-indent-cancel")) { Text("取消") }
        })
    }
}

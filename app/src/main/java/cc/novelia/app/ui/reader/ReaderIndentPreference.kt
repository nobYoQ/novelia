package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.components.base.AppTextButton

/** 编辑过程中不改动正文；完整、有效的格数提交后才重新排版。 */
@Composable internal fun ReaderIndentPreference(value: Int, defaultValue: Int, onChange: (Int) -> Unit) {
    var input by rememberSaveable(value) { mutableStateOf(value.toString()) }
    val parsed = input.toIntOrNull()?.takeIf { it in ReaderSettings.INDENT_SIZE_RANGE }
    val focus = LocalFocusManager.current
    fun apply() {
        parsed?.let { onChange(it); focus.clearFocus() }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().testTag("reader-indent-input"),
            label = { Text("首行缩进（全角格）") }, singleLine = true,
            isError = parsed == null,
            supportingText = { Text("输入 0–20 之间的整数，0 表示不缩进") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { apply() }))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AppTextButton(onClick = { input = defaultValue.toString(); onChange(defaultValue); focus.clearFocus() },
                modifier = Modifier.weight(1f).testTag("reader-indent-default")) { Text("恢复默认：$defaultValue 格") }
            AppTextButton(onClick = { apply() }, enabled = parsed != null && parsed != value,
                modifier = Modifier.testTag("reader-indent-apply")) { Text("应用") }
        }
    }
}

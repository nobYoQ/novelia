package cc.novelia.app.ui.settings

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
import cc.novelia.app.ui.account.ProfileDetailCard
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppTextField
import cc.novelia.app.ui.components.base.ChoiceRow

@Composable internal fun KeywordLimitPreference(limit: Int?, entryCount: Int, onSave: (Int?) -> Unit) {
    var custom by rememberSaveable(limit) { mutableStateOf(limit != null) }
    var input by rememberSaveable(limit) { mutableStateOf(limit?.toString().orEmpty()) }
    val parsed = input.toIntOrNull()?.takeIf { it > 0 }
    val focus = LocalFocusManager.current
    fun saveLimit() {
        if(parsed == null) return
        if(parsed != limit) onSave(parsed)
        focus.clearFocus()
    }
    ProfileDetailCard {
        ChoiceRow("标签数量上限", listOf("不限", "自定义"), if(custom) 1 else 0) { index ->
            custom = index == 1
            if(!custom) {
                focus.clearFocus()
                input = ""
                if(limit != null) onSave(null)
            }
        }
        val count = "当前已有 $entryCount 个标签"
        if(custom) {
            val invalid = input.isNotEmpty() && parsed == null
            AppTextField(input, { input = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp).testTag("keyword-limit-input"),
                label = { Text("最多保存的标签数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveLimit() }),
                trailingIcon = { AppTextButton(onClick = ::saveLimit, enabled = parsed != null && parsed != limit) { Text("保存") } },
                isError = invalid,
                supportingText = { Text(when {
                    invalid -> "请输入有效的正整数"
                    parsed != null && parsed < entryCount -> "$count，设置此上限会保留已有标签并暂停新增。"
                    else -> count
                }) })
        } else {
            Text(count, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

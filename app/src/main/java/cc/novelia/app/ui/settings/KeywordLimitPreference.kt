package cc.novelia.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.MenuRow

@Composable internal fun KeywordLimitPreference(limit: Int?, entryCount: Int, onSave: (Int?) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    MenuRow("标签数量上限", "${limit?.let { "$it 个" } ?: "不限"} · 当前 $entryCount 个标签", Icons.Outlined.Tune, { editing = true })
    if(editing) KeywordLimitDialog(limit, entryCount, { editing = false }) { value -> onSave(value); editing = false }
}

@Composable internal fun KeywordLimitDialog(limit: Int?, entryCount: Int, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    var unlimited by rememberSaveable { mutableStateOf(limit == null) }
    var input by rememberSaveable { mutableStateOf(limit?.toString().orEmpty()) }
    val parsed = input.toIntOrNull()?.takeIf { it > 0 }
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text("标签数量上限") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceRow("收集数量", listOf("不限", "自定义"), if(unlimited) 0 else 1) { unlimited = it == 0 }
            if(!unlimited) OutlinedTextField(input, { input = it }, singleLine = true,
                label = { Text("最多保存的标签数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = input.isNotEmpty() && parsed == null,
                supportingText = { Text(if(input.isNotEmpty() && parsed == null) "请输入有效的正整数" else "当前已有 $entryCount 个标签") },
                modifier = Modifier.fillMaxWidth().testTag("keyword-limit-input"))
            Text("默认不限数量。设置上限后，达到上限时停止收集新标签，超限导入会提示调整上限。")
            Text(if(!unlimited && parsed != null && parsed < entryCount)
                "已有标签超过此上限，仍会全部保留，并可继续修改译名和分类。"
                else "调整上限不会删除已有标签。")
        } },
        confirmButton = { TextButton(enabled = unlimited || parsed != null, onClick = { onSave(if(unlimited) null else parsed) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

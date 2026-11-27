package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.appVerticalScroll
import cc.novelia.app.ui.theme.appRoundedCornerShape
import cc.novelia.app.ui.theme.monochromeBorder
import kotlin.math.ceil

@Composable internal fun SearchAssistantPreview(query: String, generated: String, appended: String, compact: Boolean, hasError: Boolean) {
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
            LiveSearchExpression(query.trim(), appended, compact, if(hasError) colors.onErrorContainer else colors.onSurfaceVariant, Modifier.weight(1f))
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
                        Text(appended, Modifier.testTag("assistant-preview-appended"),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }, confirmButton = { AppTextButton(onClick = { detailsOpen = false }) { Text("返回编辑") } })
}

@Composable private fun LiveSearchExpression(base: String, appended: String, compact: Boolean, color: Color, modifier: Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    val height = with(LocalDensity.current) { style.lineHeight.toDp() } * if(compact) 1 else 2
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val text = remember(base, appended) {
        buildAnnotatedString {
            append(appended.ifEmpty { "暂无条件" })
            if(base.isNotEmpty() && appended.length > base.length) {
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), base.length + 1, appended.length)
            }
        }
    }
    Text(text, modifier.heightIn(max = height).testTag("assistant-preview").appVerticalScroll(scroll),
        style = style, color = color, onTextLayout = { layout = it })

    // 关键词排在标签前，章节数排在标签后。跟随实际编辑位置，同时允许手动回看。
    LaunchedEffect(base, scroll) {
        var previous = base
        var offset = 0
        snapshotFlow { layout to scroll.viewportSize }.collect { (measured, viewport) ->
            if(measured == null || viewport == 0) return@collect
            val current = measured.layoutInput.text.text
            if(current != previous) {
                val prefix = current.commonPrefixWith(previous).length
                val suffix = current.substring(prefix).commonSuffixWith(previous.substring(prefix)).length
                offset = if(current == base) 0 else (current.lastIndex - suffix).coerceAtLeast(0)
                previous = current
            }
            val line = measured.getLineForOffset(offset)
            val top = measured.getLineTop(line).toInt()
            val bottom = ceil(measured.getLineBottom(line)).toInt()
            scroll.scrollTo(scroll.value.coerceIn((bottom - viewport).coerceAtMost(top), top))
        }
    }
}

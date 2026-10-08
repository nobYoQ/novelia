@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cc.novelia.app.ui.theme.appReducedMotion

/** 标记内容已在应用面板中，使内部组件能选择合适的滚动和翻屏处理。 */
internal val LocalInAppSheet = compositionLocalOf { false }

/**
 * 普通交互使用底部弹层，减少动画时改用静态对话框；每次挂载分配独立浏览会话。
 * 两种容器切换会重建内部组合，需要保留的表单状态应提升到调用方，滚动位置按会话管理。
 */
@Composable internal fun AppSheet(onDismissRequest: () -> Unit, sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content: @Composable ColumnScope.() -> Unit) {
    val session = remember { Any() }
    if (!appReducedMotion()) ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        // 顶部安全区放在可拖动面板外，保持内容测量高度和展开锚点稳定。
        // 默认的内容顶部 inset 随 offset 改变，临近全屏的面板会反复改高并重启动画。
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
    ) {
        CompositionLocalProvider(LocalInAppSheet provides true, LocalPanelSession provides session) { content() }
    }
    else AppDialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
            Column {
                TextButton(onClick = onDismissRequest, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("关闭面板") }
                HorizontalDivider()
                CompositionLocalProvider(LocalInAppSheet provides true, LocalPanelSession provides session) {
                    Column(Modifier.weight(1f), content = content)
                }
            }
        }
    }
}

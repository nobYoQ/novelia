@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.discover

import cc.novelia.app.ui.components.base.AppIconButton
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.rememberCloudFilterCollapse
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion

/** 搜索始终在标题栏内编辑；离开编辑状态后显示已生效的搜索词，保留输入草稿。 */
@Composable internal fun DiscoverSearchLayout(
    query: String,
    submitted: String,
    editing: Boolean,
    onQueryChange: (String) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (onPageTurn: (Int) -> Unit) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val reducedMotion = appReducedMotion()
    fun finishEditing() {
        onEditingChange(false)
        focusManager.clearFocus()
        keyboard?.hide()
    }
    fun submit() { onSearch(); finishEditing() }
    // 保存的搜索、辅助搜索等也可以从外部结束编辑。
    LaunchedEffect(editing) {
        if(!editing) { focusManager.clearFocus(); keyboard?.hide() }
    }
    BackHandler(editing) { finishEditing() }
    val scrollCollapse = rememberCloudFilterCollapse(true, editing, ::finishEditing)
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("发现", maxLines = 1)
                Surface(onClick = {
                    if(editing) { focus.requestFocus(); keyboard?.show() } else onEditingChange(true)
                }, modifier = Modifier.weight(1f).testTag("discover-search-bar")
                        .semantics { stateDescription = if(editing) "正在编辑搜索" else "当前搜索" },
                    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.heightIn(min = 48.dp).padding(start = 12.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp))
                        if(editing) {
                            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
                            BasicTextField(query, onQueryChange,
                                modifier = Modifier.weight(1f).focusRequester(focus).testTag("discover-search-input"),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { submit() }),
                                decorationBox = { input ->
                                    Box {
                                        if(query.isEmpty()) Text("书名、作者或链接", style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        input()
                                    }
                                })
                            AppIconButton(onClick = ::submit, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowForward, "搜索", Modifier.size(20.dp))
                            }
                        } else Text(submitted.ifBlank { "搜索小说" }, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }, actions = {
            // 动画动作区宽度，使标题栏重新测量时搜索框同步伸缩。
            Row(if(reducedMotion) Modifier else Modifier.animateContentSize(
                tween(AppMotion.Panel, easing = FastOutSlowInEasing)),
                verticalAlignment = Alignment.CenterVertically, content = actions)
        })
    }) { padding ->
        // 只监听结果区，输入及筛选弹层中的滚动不会结束编辑。
        Box(Modifier.fillMaxSize().padding(padding).nestedScroll(scrollCollapse)) {
            content { direction -> if(direction > 0) finishEditing() }
        }
    }
}

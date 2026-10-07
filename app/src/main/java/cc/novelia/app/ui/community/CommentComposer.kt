package cc.novelia.app.ui.community

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion

/** 默认仅显示浮动入口；离开编辑器不清理草稿，返回键优先收起编辑器。 */
@Composable internal fun CommentEditor(expanded: Boolean, onCollapse: () -> Unit, content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(expanded) {
        if(!expanded) { focus.clearFocus(); keyboard?.hide() }
    }
    BackHandler(expanded, onCollapse)
    CommentVisibility(expanded, editor = true) {
        Surface(shadowElevation = if(appReducedMotion()) 0.dp else 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().testTag("comment-editor")) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("评论", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = onCollapse) { Icon(Icons.Outlined.ExpandMore, "收起评论框") }
                }
                content()
            }
        }
    }
}

@Composable internal fun CommentButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CommentVisibility(visible, editor = false, modifier = modifier) {
        FloatingActionButton(onClick = onClick, shape = MaterialTheme.shapes.large,
            modifier = Modifier.testTag("comment-compose-button")) {
            Icon(Icons.Outlined.ChatBubbleOutline, "写评论")
        }
    }
}

@Composable private fun CommentVisibility(visible: Boolean, editor: Boolean, modifier: Modifier = Modifier,
    content: @Composable () -> Unit) {
    val reducedMotion = appReducedMotion()
    var transition by remember { mutableStateOf(MutableTransitionState(if(reducedMotion) visible else false)) }
    LaunchedEffect(reducedMotion, visible) {
        // 静态模式直接落到终态，重新开启动效也不重播已显示的控件。
        if(reducedMotion) transition = MutableTransitionState(visible)
    }
    transition.targetState = visible
    if(reducedMotion) {
        if(visible) Box(modifier) { content() }
    } else {
        AnimatedVisibility(transition, modifier,
            enter = fadeIn(tween(AppMotion.Quick)) + if(editor)
                expandVertically(tween(AppMotion.Panel), expandFrom = Alignment.Bottom) + slideInVertically(tween(AppMotion.Panel)) { it / 4 }
                else scaleIn(tween(AppMotion.Standard), initialScale = .8f),
            exit = fadeOut(tween(AppMotion.Exit)) + if(editor)
                shrinkVertically(tween(AppMotion.Standard), shrinkTowards = Alignment.Bottom)
                else scaleOut(tween(AppMotion.Exit), targetScale = .8f)) { content() }
    }
}

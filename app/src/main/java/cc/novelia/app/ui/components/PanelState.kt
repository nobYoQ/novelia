package cc.novelia.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/** 每次打开面板都有新的会话；表单内容可以保存，浏览位置不跨面板会话恢复。 */
internal val LocalPanelSession = compositionLocalOf<Any?> { null }

private class PanelScrollSession(var state: ScrollState = ScrollState(0))

@Composable internal fun rememberPanelScrollState(expanded: Boolean = true): ScrollState {
    val session = LocalPanelSession.current
    val scroll = remember(session) { PanelScrollSession() }
    return remember(scroll, expanded) {
        // 收起动效仍使用原位置；再次展开才换成顶部，避免退出时内容突然跳动。
        if(expanded) scroll.state = ScrollState(0)
        scroll.state
    }
}

@Composable internal fun rememberContentScrollState(): ScrollState =
    if(LocalPanelSession.current != null) rememberPanelScrollState() else rememberScrollState()

@Composable internal fun rememberContentLazyListState(): LazyListState {
    val session = LocalPanelSession.current
    return if(session != null) remember(session) { LazyListState() } else rememberLazyListState()
}

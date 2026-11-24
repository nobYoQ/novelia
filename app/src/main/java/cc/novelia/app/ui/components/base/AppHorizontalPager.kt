package cc.novelia.app.ui.components.base

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import cc.novelia.app.ui.theme.LocalEInkMode

@Composable internal fun AppHorizontalPager(
    state: PagerState, modifier: Modifier = Modifier, userScrollEnabled: Boolean = true,
    key: ((Int) -> Any)? = null, content: @Composable (Int) -> Unit,
) {
    val eInk = LocalEInkMode.current
    val direction = if(LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    fun turn(delta: Int): Boolean {
        val next = state.currentPage + direction * delta
        if(next !in 0 until state.pageCount) return false
        state.requestScrollToPage(next)
        return true
    }
    val swipe = if(eInk && userScrollEnabled) Modifier.screenPageSwipe(horizontal = true) { turn(it) }
        .semantics { pageLeft { turn(-1) }; pageRight { turn(1) } } else Modifier
    HorizontalPager(state, modifier.then(swipe), userScrollEnabled = userScrollEnabled && !eInk, key = key) { content(it) }
}

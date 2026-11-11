package cc.novelia.app.ui.community

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import cc.novelia.app.data.community.Article
import cc.novelia.app.ui.theme.appReducedMotion

/** 每次加载或开启选项只定位一次，恢复页面、切回分类和手动回看置顶时保留当前位置。 */
@Composable internal fun ForumPinnedPostScroll(
    articles: List<Article>, scroll: LazyListState, enabled: Boolean, active: Boolean, resetKey: Any,
) {
    var handled by rememberSaveable(resetKey, enabled) { mutableStateOf(false) }
    val reducedMotion = appReducedMotion()
    LaunchedEffect(scroll, articles, enabled, active, resetKey) {
        if(!enabled || !active || handled || articles.isEmpty()) return@LaunchedEffect
        handled = true
        val firstRegular = articles.indexOfFirst { !it.pinned }
        // 没有普通帖、首项已是普通帖或用户已在更下方时，均无需改变浏览位置。
        if(firstRegular <= 0 || scroll.firstVisibleItemIndex >= firstRegular || scroll.isScrollInProgress) return@LaunchedEffect
        if(reducedMotion) scroll.scrollToItem(firstRegular) else scroll.animateScrollToItem(firstRegular)
    }
}

package cc.novelia.app.ui.components.base

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.cache.UiResultCache
import cc.novelia.app.data.network.ApiException

/** 首帧复用结果；过期、手动刷新和写入失效均保留已有组合，错误交给 AsyncContent 展示。 */
@Composable internal fun <T> CachedAsyncContent(
    cache: UiResultCache<T>, key: Any, load: suspend () -> T,
    modifier: Modifier = Modifier, refreshKey: Any? = Unit,
    onLoaded: (T) -> Unit = {}, revealContent: Boolean = false,
    pullToRefreshEnabled: Boolean = false, observeInvalidation: Boolean = true,
    validate: () -> Unit = {},
    showRefreshIndicator: Boolean = true,
    content: @Composable (T, () -> Unit) -> Unit,
) {
    val revision by cache.revision.collectAsStateWithLifecycle()
    var invalidation by remember(cache, key) { mutableLongStateOf(revision) }
    LaunchedEffect(cache, key, revision, observeInvalidation) {
        // 同域内其他书目的写入不应触发当前作品重复加载。
        if(observeInvalidation && cache.snapshot(key)?.fresh != true) invalidation = revision
    }
    var resumeRefresh by remember(cache, key) { mutableIntStateOf(0) }
    // 初次进入由首帧策略处理；仅真正从后台回来时复核有效期。
    var resumed by remember(cache, key) { mutableStateOf(false) }
    key(cache, key) { LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if(resumed && cache.snapshot(key)?.fresh != true) resumeRefresh++
        resumed = true
    } }
    val snapshot = remember(cache, key, revision) { cache.snapshot(key) }
    val discard: (Exception) -> Boolean = { it is ApiException && it.status in setOf(401, 403, 404) }
    AsyncContent(key, load = {
        try { cache.load(key, forceNetwork = true) { validate(); load().also { validate() } } }
        catch(error: Exception) { if(discard(error)) cache.discard(key); throw error }
    },
        modifier = modifier, refreshKey = listOf(refreshKey, invalidation, resumeRefresh),
        initialResult = snapshot?.value, refreshOnEnter = snapshot?.fresh != true,
        discardContentOnError = discard,
        onLoaded = onLoaded, revealContent = revealContent, pullToRefreshEnabled = pullToRefreshEnabled,
        showRefreshIndicator = showRefreshIndicator,
        content = content)
}

/** 分页往返复用各页位置；当前页仍使用 Compose 的保存机制应对系统重建。 */
@Composable internal fun rememberCachedListState(cache: UiResultCache<*>, identity: Any, slot: String = "list"): LazyListState =
    key(identity, slot) {
        val position = remember { cache.position(identity, slot) }
        val state = rememberLazyListState(position.index, position.offset)
        DisposableEffect(state) {
            onDispose { cache.savePosition(identity, slot, UiResultCache.Position(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)) }
        }
        state
    }

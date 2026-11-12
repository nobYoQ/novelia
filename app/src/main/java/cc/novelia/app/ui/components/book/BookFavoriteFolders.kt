package cc.novelia.app.ui.components.book

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import cc.novelia.app.data.model.CloudFolders
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.CancellationException

/** 在根级收藏展示中统一加载名称；列表行和详情只查共享数据，不逐本发请求。 */
@Composable internal fun rememberCloudFavoriteFolders(c: AppController): CloudFolders? {
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val generation by c.store.cacheGeneration.collectAsStateWithLifecycle()
    val entry by c.nav.currentBackStackEntryAsState()
    val binding = c.session.capture()
    val cache = c.uiResults.folders
    val cacheKey = remember(c, binding, generation) { c.foldersKey(binding, generation) }
    val revision by cache.revision.collectAsStateWithLifecycle()
    var folders by remember(cache, cacheKey) {
        mutableStateOf(cache.snapshot(cacheKey)?.takeIf { it.fresh }?.value)
    }
    var resumeRefresh by remember(cache, cacheKey) { mutableIntStateOf(0) }
    var resumed by remember(cache, cacheKey) { mutableStateOf(false) }
    key(cache, cacheKey) { LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if(resumed && cache.snapshot(cacheKey)?.fresh != true) resumeRefresh++
        resumed = true
    } }
    LaunchedEffect(c, cacheKey, revision, entry, resumeRefresh) {
        if(profile == null || binding.account == null) { folders = null; return@LaunchedEffect }
        if(entry == null) return@LaunchedEffect
        folders = cache.snapshot(cacheKey)?.takeIf { it.fresh }?.value
        try {
            c.session.ensureCurrent(binding)
            val loaded = c.folders()
            c.session.ensureCurrent(binding)
            // 清缓存、收藏变更及换号前启动的请求不能发布到新的展示状态。
            if(cache.revision.value == revision && c.foldersKey() == cacheKey) folders = loaded
        } catch(error: CancellationException) {
            throw error
        } catch(_: Exception) {
            // 名称是辅助信息，离线或无权限时保留原收藏标记。
            folders = null
        }
    }
    return folders
}

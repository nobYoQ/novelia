package cc.novelia.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cc.novelia.app.data.auth.Session
import kotlinx.coroutines.CancellationException

/** 论坛页面进入前台时尝试复用同线路认证；失败仍可匿名浏览，主动登录入口会显示认证页。 */
@Composable internal fun ObserveForumLogin(main: Session, forum: Session) {
    val mainProfile by main.profile.collectAsStateWithLifecycle()
    val forumProfile by forum.profile.collectAsStateWithLifecycle()
    val mainBinding = main.capture()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(main, forum, mainBinding, mainProfile, forumProfile, lifecycle) {
        if(forumProfile == null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try { forum.loginFromSharedAuth(main) }
            catch(error: CancellationException) { throw error }
            catch(_: Exception) { }
        }
    }
}

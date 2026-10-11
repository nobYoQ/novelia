package cc.novelia.app.ui.community

import cc.novelia.app.ui.components.base.AppOutlinedButton
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion

/** 收藏状态只在服务端确认后改变，同一按钮的请求串行执行。 */
internal class ForumFavoriteState(initialSaved: Boolean) {
    var saved by mutableStateOf(initialSaved)
        private set
    var saving by mutableStateOf(false)
        private set

    suspend fun setSaved(value: Boolean, save: suspend (Boolean) -> Unit) {
        if(saving || saved == value) return
        saving = true
        try { save(value); saved = value }
        finally { saving = false }
    }
}

@Composable internal fun ForumFavoriteButton(favorite: ForumFavoriteState, onClick: () -> Unit) {
    val reducedMotion = appReducedMotion()
    AppOutlinedButton(enabled = !favorite.saving, onClick = onClick) {
        Crossfade(favorite.saved, animationSpec = tween(if(reducedMotion) 0 else AppMotion.Quick), label = "articleBookmark") { saved ->
            Icon(if(saved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(if(favorite.saving) "正在同步…" else if(favorite.saved) "取消收藏" else "收藏文章")
    }
}

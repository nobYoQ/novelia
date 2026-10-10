package cc.novelia.app.ui.book

import cc.novelia.app.ui.components.AppTextButton

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.shelf.BookFavoriteState
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion

/** 两个入口只展示各自的收藏状态，共用收藏面板的不同初始位置。 */
@Composable internal fun BookFavoriteActions(
    state: BookFavoriteState,
    onLocal: () -> Unit,
    onCloud: () -> Unit,
    modifier: Modifier = Modifier,
    onDownload: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = maxWidth < (240 * LocalDensity.current.fontScale).dp
        val actions: @Composable (Modifier) -> Unit = { actionModifier ->
            FavoriteAction(state.localLabel, if(state.local) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd,
                state.local, "book-local-favorite", "管理本地收藏", stacked, onLocal, actionModifier)
            FavoriteAction(state.cloudLabel, when {
                state.pendingMethod != null -> Icons.Outlined.CloudSync
                state.cloudFolder != null -> Icons.Outlined.CloudDone
                else -> Icons.Outlined.CloudQueue
            }, state.cloudFolder != null, "book-cloud-favorite", "管理云端收藏", stacked, onCloud, actionModifier)
            onDownload?.let { download ->
                FavoriteAction("下载", Icons.Outlined.Download, false, "book-download", "下载小说", stacked, download, actionModifier)
            }
        }
        if(stacked) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { actions(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            actions(Modifier.weight(1f))
        }
    }
}

@Composable private fun FavoriteAction(label: String, icon: ImageVector, saved: Boolean, tag: String,
    description: String, stacked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val background by animateColorAsState(
        if(saved) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        tween(if(appReducedMotion()) 0 else AppMotion.Standard), label = "$tag-container")
    AppTextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).testTag(tag).semantics {
        contentDescription = description
        stateDescription = label
    }, colors = ButtonDefaults.textButtonColors(containerColor = background), shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
        if(stacked) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

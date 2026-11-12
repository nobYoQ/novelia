package cc.novelia.app.ui.book

import cc.novelia.app.ui.components.base.AppTextButton
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    onRelatedWeb: (() -> Unit)? = null,
) {
    val reducedMotion = appReducedMotion()
    val sizeMotion = if(reducedMotion) Modifier else Modifier.animateContentSize(
        tween(AppMotion.Standard, easing = FastOutSlowInEasing), alignment = Alignment.TopStart)
    BoxWithConstraints(modifier.fillMaxWidth().testTag("book-favorite-actions").then(sizeMotion)) {
        val stacked = maxWidth < (240 * LocalDensity.current.fontScale).dp
        val showFolderLine = state.localFolderName != null || state.cloudFolderName != null
        val actions: @Composable (Modifier) -> Unit = { actionModifier ->
            FavoriteAction(state.localLabel, if(state.local) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd,
                state.local, "book-local-favorite", "管理本地收藏", stacked, onLocal, actionModifier, showFolderLine, state.localFolderName)
            FavoriteAction(state.cloudLabel, when {
                state.pendingMethod != null -> Icons.Outlined.CloudSync
                state.cloudFolder != null -> Icons.Outlined.CloudDone
                else -> Icons.Outlined.CloudQueue
            }, state.cloudFolder != null, "book-cloud-favorite", "管理云端收藏", stacked, onCloud, actionModifier, showFolderLine, state.cloudFolderName)
            onRelatedWeb?.let { relatedWeb ->
                FavoriteAction("相关网络版", Icons.Outlined.Language, false, "book-related-web", "查看相关网络小说", stacked, relatedWeb, actionModifier, showFolderLine)
            }
            onDownload?.let { download ->
                FavoriteAction("下载", Icons.Outlined.Download, false, "book-download", "下载小说", stacked, download, actionModifier, showFolderLine)
            }
        }
        if(stacked) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { actions(Modifier.fillMaxWidth()) }
        else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

private data class FavoriteActionContent(val label: String, val icon: ImageVector, val folderName: String?, val showFolderLine: Boolean)

@Composable private fun FavoriteAction(label: String, icon: ImageVector, saved: Boolean, tag: String,
    description: String, stacked: Boolean, onClick: () -> Unit, modifier: Modifier,
    showFolderLine: Boolean, folderName: String? = null) {
    val reducedMotion = appReducedMotion()
    val targetBackground = if(saved) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val background by animateColorAsState(targetBackground,
        tween(if(reducedMotion) 0 else AppMotion.Standard), label = "$tag-container")
    val content = FavoriteActionContent(label, icon, folderName, showFolderLine)
    // 按钮本身保持同一点击入口，仅过渡其内容，避免新旧按钮同时响应。
    AppTextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).testTag(tag).semantics {
        contentDescription = description
        stateDescription = label
    }, colors = ButtonDefaults.textButtonColors(containerColor = if(reducedMotion) targetBackground else background),
        shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
        if(reducedMotion) FavoriteActionBody(content, stacked, tag)
        else Crossfade(content, Modifier.fillMaxWidth(), animationSpec = tween(AppMotion.Quick), label = "$tag-content") { visible ->
            FavoriteActionBody(visible, stacked, tag, current = visible == content)
        }
    }
}

@Composable private fun FavoriteActionBody(content: FavoriteActionContent, stacked: Boolean, tag: String, current: Boolean = true) {
    val semantics = if(current) Modifier else Modifier.clearAndSetSemantics {}
    Column(Modifier.fillMaxWidth().then(semantics), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if(stacked) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(content.icon, null, Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(content.label, style = MaterialTheme.typography.labelLarge)
                if(content.showFolderLine) FavoriteFolderLabel(content.folderName, tag, TextAlign.Start, current)
            }
        } else {
            Icon(content.icon, null, Modifier.size(20.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(content.label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                if(content.showFolderLine) FavoriteFolderLabel(content.folderName, tag, TextAlign.Center, current)
            }
        }
    }
}

@Composable private fun FavoriteFolderLabel(name: String?, tag: String, alignment: TextAlign, current: Boolean) {
    val semantics = if(current && name != null) Modifier.testTag("$tag-folder") else Modifier.clearAndSetSemantics {}
    Text(name.orEmpty(), Modifier.fillMaxWidth().then(semantics),
        style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = .7f),
        textAlign = alignment, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

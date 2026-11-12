package cc.novelia.app.ui.components.book

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookCard

/** 放在书名下方，不覆盖封面；每个标记单行截断，窄屏可将两个标记分行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BookFavoriteIndicator(book: BookCard) {
    val favorite = LocalBookListPresentation.current.favoriteState(book)
    if(!favorite.isSaved) return
    FlowRow(Modifier.testTag("book-favorite-${book.ref.key}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if(favorite.local) FavoriteLabel(favorite.localIndicatorLabel, "本地收藏", Icons.Outlined.BookmarkAdded,
            "book-favorite-local-${book.ref.key}")
        if(favorite.cloudFolder != null) {
            val pending = favorite.pendingMethod == "PUT"
            val cloudName = favorite.cloudFolderName
            val suffix = if(pending && cloudName != null) "· 待同步" else null
            FavoriteLabel(cloudName ?: favorite.cloudIndicatorLabel, "云端收藏",
                if(pending) Icons.Outlined.CloudUpload else Icons.Outlined.CloudDone, "book-favorite-cloud-${book.ref.key}",
                suffix)
        }
    }
}

@Composable private fun FavoriteLabel(label: String, source: String, icon: ImageVector, tag: String,
    suffix: String? = null) {
    Row(Modifier.testTag(tag).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, source, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        suffix?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
    }
}

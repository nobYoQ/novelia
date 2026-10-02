package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.library.ReadingDestination
import cc.novelia.app.ui.shelf.BookFavoriteState

@Composable internal fun BookReadingActions(destination: ReadingDestination?, continuing: Boolean,
    favoriteState: BookFavoriteState, onLocalFavorite: () -> Unit, onCloudFavorite: () -> Unit,
    onRead: () -> Unit, onDownload: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BookFavoriteActions(favoriteState, onLocalFavorite, onCloudFavorite, onDownload = onDownload)
        Button(onClick = onRead, enabled = destination != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag("book-read-action"),
            shape = MaterialTheme.shapes.large, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if(continuing) "继续阅读" else "开始阅读", style = MaterialTheme.typography.titleMedium)
                destination?.let { chapter ->
                    Text(chapter.label, Modifier.testTag("book-resume-chapter"), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
        }
    }
}

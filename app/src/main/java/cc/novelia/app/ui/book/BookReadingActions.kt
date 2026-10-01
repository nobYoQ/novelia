package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.library.ReadingDestination
import cc.novelia.app.ui.shelf.BookFavoriteState

@Composable internal fun BookReadingActions(destination: ReadingDestination?, continuing: Boolean,
    favoriteState: BookFavoriteState, onFavorite: () -> Unit, onRead: () -> Unit, onDownload: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onFavorite, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .testTag("book-manage-favorite").semantics { contentDescription = favoriteState.label },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                Icon(if(favoriteState.isSaved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd,
                    null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                // 待处理操作仍明确显示，只在视觉上省略重复的操作提示。
                Text(favoriteState.label.substringBefore(" · "), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
            }
            TextButton(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp).testTag("book-download")
                .semantics { contentDescription = "下载小说" }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("下载", style = MaterialTheme.typography.labelLarge)
            }
        }
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

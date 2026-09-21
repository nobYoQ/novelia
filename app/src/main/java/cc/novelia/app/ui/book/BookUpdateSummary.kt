package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.library.readingDestination
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.ui.components.bookUpdateDateTime

@Composable internal fun BookUpdateSummary(detail: WebDetail, onReadChapter: (String) -> Unit) {
    val chapter = readingDestination(detail.toc, detail.lastUpdatedChapter?.chapterId)
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("book-update-summary"),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Update, null, tint = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("最后更新时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(bookUpdateDateTime(detail.lastUpdatedAt) ?: "暂无时间记录", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if(chapter != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(onClick = { onReadChapter(chapter.chapterId) },
                    modifier = Modifier.fillMaxWidth().testTag("book-update-chapter"),
                    shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.primary) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(chapter.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.Outlined.ChevronRight, "阅读此章节", Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

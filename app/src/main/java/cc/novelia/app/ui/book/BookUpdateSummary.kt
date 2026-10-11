package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.library.readingDestination
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.ui.components.book.bookUpdateDateTime

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BookUpdateSummary(detail: WebDetail, onReadChapter: (String) -> Unit) {
    val chapter = readingDestination(detail.toc, detail.lastUpdatedChapter?.chapterId)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("book-update-summary")) {
        FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最近更新", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(bookUpdateDateTime(detail.lastUpdatedAt) ?: "暂无时间记录",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(chapter != null) {
            Surface(onClick = { onReadChapter(chapter.chapterId) },
                modifier = Modifier.fillMaxWidth().testTag("book-update-chapter"),
                color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(chapter.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Icon(Icons.Outlined.ChevronRight, "阅读此章节", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

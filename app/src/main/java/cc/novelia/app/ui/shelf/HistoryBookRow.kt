package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.ui.account.ProfileDetailCard
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.book.BookCover
import cc.novelia.app.ui.theme.motionClickable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private val monthDay = DateTimeFormatter.ofPattern("M月d日")

internal fun historyTime(seconds: Long?, today: LocalDate, zone: ZoneId): String? {
    if (seconds == null || seconds <= 0) return null
    val time = runCatching { Instant.ofEpochSecond(seconds).atZone(zone) }.getOrNull() ?: return null
    val date = time.toLocalDate()
    return when {
        date == today -> "今天 ${clock.format(time)}"
        date == today.minusDays(1) -> "昨天 ${clock.format(time)}"
        date.year == today.year -> monthDay.format(date)
        else -> "${date.year}年${monthDay.format(date)}"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryBookRow(
    book: BookCard,
    readChapterId: String?,
    chapterTitle: String?,
    lastReadSeconds: Long?,
    onRead: () -> Unit,
    onCover: () -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val readTime = historyTime(lastReadSeconds, today, zone)
    val updateTime = historyTime(book.updateAt, today, zone)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val latest = book.latestChapter?.takeIf { !it.chapterId.isNullOrBlank() && !readChapterId.isNullOrBlank() }
    val caughtUp = latest != null && latest.chapterId == readChapterId
    val readTitle = chapterTitle?.takeIf(String::isNotBlank) ?: latest?.takeIf { caughtUp }?.title
    var menuOpen by remember(book.ref) { mutableStateOf(false) }

    ProfileDetailCard {
        Row(
            Modifier.fillMaxWidth().motionClickable(onClick = onRead).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(book, Modifier.size(52.dp, 72.dp).motionClickable(onClick = onCover), showFallbackTitle = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(book.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box {
                        AppIconButton(onClick = { menuOpen = true }, enabled = canRemove) {
                            Icon(Icons.Outlined.MoreVert, "《${book.title}》的更多操作", tint = secondary)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("删除阅读历史") }, enabled = canRemove, onClick = {
                                menuOpen = false
                                onRemove()
                            }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(readTitle?.let { "读到 $it" } ?: "继续阅读",
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodySmall, color = secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (caughtUp) Text("（已是最新）", style = MaterialTheme.typography.bodySmall, color = secondary)
                }
                if (latest != null && !caughtUp) {
                    Text("最新 ${latest.title}", style = MaterialTheme.typography.bodySmall, color = secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (readTime != null || updateTime != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (readTime != null) Text("$readTime 读过", style = MaterialTheme.typography.labelSmall, color = secondary)
                        if (updateTime != null) {
                            val newer = lastReadSeconds != null && lastReadSeconds > 0 &&
                                book.updateAt != null && book.updateAt > lastReadSeconds
                            Text("$updateTime 更新", style = MaterialTheme.typography.labelSmall,
                                color = if (newer) MaterialTheme.colorScheme.primary else secondary)
                        }
                    }
                }
            }
        }
    }
}

package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.ui.account.ProfileDetailCard
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppTextButton
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
    loadingCatalog: Boolean,
    retryCatalog: (() -> Unit)?,
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val readTime = historyTime(lastReadSeconds, today, zone)
    val updateTime = historyTime(book.updateAt, today, zone)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val isWeb = !book.ref.isLocal && !book.ref.isWenku
    val latest = book.latestChapter?.takeIf { isWeb && !it.chapterId.isNullOrBlank() }
    val caughtUp = latest != null && latest.chapterId == readChapterId
    val readTitle = chapterTitle?.takeIf(String::isNotBlank) ?: latest?.takeIf { caughtUp }?.title
    var menuOpen by remember(book.ref) { mutableStateOf(false) }

    ProfileDetailCard {
        Column(Modifier.fillMaxWidth().motionClickable(onClick = onRead)) {
            HistoryBookSummary(book, onCover) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (readTitle != null) {
                        HistoryChapterLine("读到", readTitle, caughtUp)
                    } else {
                        Text("继续阅读", style = MaterialTheme.typography.bodyMedium, color = secondary)
                    }
                    if (latest != null && !caughtUp) {
                        HistoryChapterLine("最新", latest.title)
                    } else if (isWeb && latest == null) {
                        if (retryCatalog != null) AppTextButton(onClick = retryCatalog) { Text("目录未获取 · 重试") }
                        else Text(if (loadingCatalog) "正在读取目录…" else "目录暂无可读章节",
                            style = MaterialTheme.typography.bodySmall, color = secondary)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (readTime != null) Text("$readTime 读过", style = MaterialTheme.typography.bodySmall, color = secondary)
                    if (updateTime != null) {
                        val newer = lastReadSeconds != null && lastReadSeconds > 0 &&
                            book.updateAt != null && book.updateAt > lastReadSeconds
                        Text("$updateTime 更新", style = MaterialTheme.typography.bodySmall, color = if (newer) accent else secondary)
                    }
                }
                Box {
                    AppIconButton(onClick = { menuOpen = true }, enabled = canRemove) {
                        Icon(Icons.Outlined.MoreVert, "《${book.title}》的更多操作", Modifier.size(20.dp), tint = secondary)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("删除阅读历史") }, enabled = canRemove, onClick = {
                            menuOpen = false
                            onRemove()
                        }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryBookSummary(book: BookCard, onCover: () -> Unit, chapters: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
        val coverWidth = 64.dp
        val gutter = 14.dp
        // 给章节保留容纳标签和章名的空间；分屏或大字号时改用封面下方的整行。
        val chaptersBesideCover = maxWidth - coverWidth - gutter >= 160.dp * LocalDensity.current.fontScale
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(gutter), verticalAlignment = Alignment.CenterVertically) {
                BookCover(book, Modifier.size(coverWidth, 90.dp).motionClickable(onClick = onCover), showFallbackTitle = false)
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium,
                        fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (chaptersBesideCover) {
                        Spacer(Modifier.height(10.dp))
                        chapters()
                    }
                }
            }
            if (!chaptersBesideCover) {
                Spacer(Modifier.height(12.dp))
                chapters()
            }
        }
    }
}

@Composable
private fun HistoryChapterLine(label: String, title: String, caughtUp: Boolean = false) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.alignByBaseline(), style = MaterialTheme.typography.bodySmall, color = secondary)
        Text(title, Modifier.weight(1f, fill = false).alignByBaseline(), style = MaterialTheme.typography.bodyMedium,
            color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (caughtUp) Text("已是最新", Modifier.alignByBaseline(), style = MaterialTheme.typography.bodySmall, color = secondary)
    }
}

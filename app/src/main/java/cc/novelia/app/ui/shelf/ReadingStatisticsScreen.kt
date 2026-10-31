package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.account.*
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.appRoundedCornerShape
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable fun ReadingStatisticsScreen(c: AppController, bookKey: String? = null) {
    val state by c.store.state.collectAsStateWithLifecycle()
    ReadingStatisticsView(state.readingStatistics, state.historyPaused, bookKey, c::back,
        onEnabled = { enabled -> c.store.update { it.copy(readingStatistics = it.readingStatistics.withEnabled(enabled)) } },
        onClear = { c.store.update { it.copy(readingStatistics = it.readingStatistics.cleared()) } })
}

@Composable internal fun ReadingStatisticsView(stats: ReadingStatistics, historyPaused: Boolean, bookKey: String?,
    onBack: () -> Unit, onEnabled: (Boolean) -> Unit, onClear: () -> Unit) {
    var onlyBook by rememberSaveable(bookKey) { mutableStateOf(bookKey != null) }
    var clear by remember { mutableStateOf(false) }
    val today by produceState(LocalDate.now()) { while(isActive) { value = LocalDate.now(); delay(60_000) } }
    val selected = remember(stats, onlyBook, bookKey) {
        if(onlyBook && bookKey != null) stats.copy(days = stats.days.mapValues { (_, books) -> books.filterKeys { it == bookKey } }) else stats
    }
    val days = remember(today, selected) { (6L downTo 0).map { today.minusDays(it).let { date -> date to selected.dayMillis(date) } } }
    val books = remember(selected) {
        val totals = mutableMapOf<String, ReadingTimeEntry>()
        selected.days.toSortedMap().values.forEach { entries -> entries.forEach { (key, entry) ->
            totals[key] = entry.copy(millis = (totals[key]?.millis ?: 0) + entry.millis)
        } }
        totals.entries.sortedByDescending { it.value.millis }
    }
    ProfileDetailScreen("阅读统计", onBack, actions = {
        AppIconButton(onClick = { clear = true }, enabled = stats.days.isNotEmpty()) { Icon(Icons.Outlined.DeleteSweep, "清空阅读统计") }
    }) { padding -> ProfileDetailList(Modifier.padding(padding).testTag("reading-statistics-list")) {
        if(bookKey != null) item { ProfileChoiceRow("统计范围", listOf("本书", "全部书籍"), if(onlyBook) 0 else 1) { onlyBook = it == 0 } }
        item(key = "overview") { ReadingTimeOverview(selected, today, days.sumOf { it.second }, books.size) }
        item(key = "recording") {
            ProfileToggle("记录阅读时长", when {
                historyPaused -> "阅读历史已暂停，时长统计也已暂停。"
                !stats.enabled -> "已关闭记录，已有统计仍会保留"
                else -> "已开启 · 仅统计此设备前台阅读"
            }, stats.enabled, onChange = onEnabled)
        }
        item(key = "week") { ReadingWeekChart(days, today) }
        item { Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("按书籍统计", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text("按累计阅读时长排序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${books.size} 本", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } }
        if(books.isEmpty()) item { ProfileEmptyState("还没有阅读时长", "开始阅读后会自动累计；已读进度不会换算成阅读时长。") }
        itemsIndexed(books, key = { _, book -> "book-${book.key}" }) { index, book ->
            ReadingBookTimeRow(book.value, index + 1, selected.totalMillis)
        }
        item(key = "explanation") { ProfileDetailCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("时长如何计算", style = MaterialTheme.typography.titleSmall)
                }
                Text("切到后台、锁屏或打开阅读操作面板时暂停。普通阅读连续 5 分钟无操作后暂停；自动阅读和前台朗读可持续累计。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("每 15 秒及离开页面时保存。保留最近 730 个有记录的日期，随书库备份保存，不通过 WebDAV 同步。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
    } }
    if(clear) ConfirmDialog("清空全部阅读统计？", "全部书籍的时长记录将清空，阅读历史、续读位置和笔记会保留。", { clear = false }, confirmLabel = "清空统计") {
        onClear(); clear = false
    }
}

@Composable private fun ReadingTimeOverview(stats: ReadingStatistics, today: LocalDate, weekMillis: Long, bookCount: Int) {
    ProfileDetailCard(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(24.dp))
                Text("今日阅读", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("${today.monthValue} 月 ${today.dayOfMonth} 日", style = MaterialTheme.typography.labelMedium)
            }
            Text(formatReadingTime(stats.dayMillis(today)), Modifier.testTag("reading-statistics-today")
                .clearAndSetSemantics { contentDescription = "今日 · ${formatReadingTime(stats.dayMillis(today))}" },
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text(if(stats.streak(today) > 0) "连续阅读 ${stats.streak(today)} 天" else "从今天开始阅读吧", style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .15f))
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if(maxWidth / fontScale < 280.dp) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ReadingTimeMetric("近七天", formatReadingTime(weekMillis))
                    ReadingTimeMetric("累计 · $bookCount 本书", formatReadingTime(stats.totalMillis), Modifier.testTag("reading-statistics-total"))
                } else Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ReadingTimeMetric("近七天", formatReadingTime(weekMillis), Modifier.weight(1f))
                    ReadingTimeMetric("累计 · $bookCount 本书", formatReadingTime(stats.totalMillis), Modifier.weight(1f).testTag("reading-statistics-total"))
                }
            }
        }
    }
}

@Composable private fun ReadingTimeMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun ReadingWeekChart(days: List<Pair<LocalDate, Long>>, today: LocalDate) {
    val maximum = days.maxOf { it.second }.coerceAtLeast(1)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val colors = MaterialTheme.colorScheme
    ProfileDetailCard(Modifier.testTag("reading-statistics-week")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("近七天", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text("${days.first().first.monthValue}/${days.first().first.dayOfMonth} — ${today.monthValue}/${today.dayOfMonth} · 单位：分钟",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if(maxWidth / fontScale < 260.dp) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    days.forEach { (date, millis) ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${date.dayOfMonth} 日 · ${formatReadingTime(millis)}", style = MaterialTheme.typography.labelMedium)
                            LinearProgressIndicator(progress = { millis.toFloat() / maximum }, modifier = Modifier.fillMaxWidth(),
                                color = if(date == today) colors.primary else colors.secondary, trackColor = colors.surfaceContainerHighest,
                                drawStopIndicator = {})
                        }
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    days.forEach { (date, millis) ->
                        Column(Modifier.weight(1f).clearAndSetSemantics {
                            contentDescription = "${date.monthValue} 月 ${date.dayOfMonth} 日，${formatReadingTime(millis)}"
                        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when { millis == 0L -> "—"; millis < 60_000 -> "<1"; else -> "${millis / 60_000}" },
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Box(Modifier.height(112.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                                Box(Modifier.widthIn(max = 28.dp).fillMaxWidth().height(
                                    if(millis > 0) (112f * millis.toFloat() / maximum).coerceAtLeast(3f).dp else 3.dp)
                                    .clip(appRoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                    .background(if(millis == 0L) colors.outlineVariant else if(date == today) colors.primary else colors.secondary.copy(alpha = .65f)))
                            }
                            Text(if(date == today) "今天" else "${date.dayOfMonth} 日", style = MaterialTheme.typography.labelSmall,
                                color = if(date == today) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(if(days.all { it.second == 0L }) "这七天还没有阅读记录" else "阅读 ${days.count { it.second > 0 }} 天 · 日均 ${formatReadingTime(days.sumOf { it.second } / 7)}",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable private fun ReadingBookTimeRow(entry: ReadingTimeEntry, rank: Int, totalMillis: Long) {
    val share = if(totalMillis > 0) entry.millis.toFloat() / totalMillis else 0f
    ProfileDetailCard {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Surface(shape = appRoundedCornerShape(12.dp), color = if(rank == 1) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    Text("$rank", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title.ifBlank { "未命名作品" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatReadingTime(entry.millis)} · 占累计 ${(share * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth().height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
            }
        }
    }
}

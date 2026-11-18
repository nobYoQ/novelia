package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.account.*
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.navigation.AppController
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
    val scopeKey = bookKey.takeIf { onlyBook }
    val report = remember(stats.days, scopeKey) { ReadingReport(stats, scopeKey) }
    var expanded by rememberSaveable(scopeKey) { mutableStateOf(false) }
    var pie by rememberSaveable { mutableStateOf(false) }
    val books = report.books
    ProfileDetailScreen("阅读统计", onBack, actions = {
        AppIconButton(onClick = { clear = true }, enabled = stats.days.isNotEmpty()) { Icon(Icons.Outlined.DeleteSweep, "清空阅读统计") }
    }) { padding -> ProfileDetailList(Modifier.padding(padding), listModifier = Modifier.testTag("reading-statistics-list")) {
        if(bookKey != null) item { ProfileChoiceRow("统计范围", listOf("本书", "全部书籍"), if(onlyBook) 0 else 1) { onlyBook = it == 0 } }
        item(key = "overview") { ReadingTimeOverview(report, today) }
        item(key = "recording") {
            ProfileToggle("记录阅读时长", when {
                historyPaused -> "阅读历史已暂停，时长统计也已暂停。"
                !stats.enabled -> "已关闭记录，已有统计仍会保留"
                else -> "已开启 · 仅统计此设备前台阅读"
            }, stats.enabled, onChange = onEnabled)
        }
        item(key = "week") { ReadingTrendChart(report, today) }
        item(key = "books") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("按书籍统计", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text("${books.size} 本", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if(books.isNotEmpty()) ChartChoices(listOf("排行榜", "饼图"), if(pie) 1 else 0) { pie = it == 1 }
        } }
        if(books.isEmpty()) item { ProfileEmptyState("还没有阅读时长", "开始阅读后会自动累计；已读进度不会换算成阅读时长。") }
        else if(pie) item(key = "pie") { ReadingBookPie(report) }
        else itemsIndexed(books.take(READING_RANK_LIMIT), key = { _, book -> "book-${book.key}" }) { index, book ->
            ReadingBookTimeRow(book.value, index + 1, report.totalMillis)
        }
        if(books.size > READING_RANK_LIMIT) {
            item(key = "more-books") { AppTextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if(expanded) "收起其他书籍" else "展开其他 ${books.size - READING_RANK_LIMIT} 本书")
                Icon(if(expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            } }
            if(expanded) itemsIndexed(books.drop(READING_RANK_LIMIT), key = { _, book -> "book-${book.key}" }) { index, book ->
                ReadingBookTimeRow(book.value, READING_RANK_LIMIT + index + 1, report.totalMillis)
            }
        }
        item(key = "explanation") { ProfileSummary("时长如何计算",
            "切到后台、锁屏或打开阅读操作面板时暂停。普通阅读连续 5 分钟无操作后暂停；自动阅读和前台朗读可持续累计。每 15 秒及离开页面时保存。保留最近 730 个有记录的日期，随书库备份保存，不通过 WebDAV 同步。", Icons.Outlined.Info) }
    } }
    if(clear) ConfirmDialog("清空全部阅读统计？", "全部书籍的时长记录将清空，阅读历史、续读位置和笔记会保留。", { clear = false }, confirmLabel = "清空统计") {
        onClear(); clear = false
    }
}

@Composable private fun ReadingTimeOverview(report: ReadingReport, today: LocalDate) {
    val todayText = formatReadingTime(report.daily[today] ?: 0L)
    val week = remember(report, today) { report.series(today.minusDays(6), today).sumOf { it.second } }
    val metrics = listOf("累计时长" to formatReadingTime(report.totalMillis), "近七天" to formatReadingTime(week),
        "阅读天数" to "${report.daily.size} 天", "阅读书籍" to "${report.books.size} 本",
        "阅读日均" to formatReadingTime(report.activeDayAverage), "连续阅读" to "${report.streak(today)} 天")
    ProfileDetailCard(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(20.dp))
                Text("阅读总览", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text("${today.monthValue} 月 ${today.dayOfMonth} 日", style = MaterialTheme.typography.labelMedium)
            }
            Text("今日阅读", style = MaterialTheme.typography.labelLarge)
            Text(todayText, Modifier.testTag("reading-statistics-today").clearAndSetSemantics { contentDescription = "今日 · $todayText" },
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(color = LocalContentColor.current.copy(alpha = .2f))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = (maxWidth.value / (108 * LocalDensity.current.fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 3)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    metrics.chunked(columns).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, value) -> Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                            Text(value, if(label == "累计时长") Modifier.testTag("reading-statistics-total") else Modifier,
                                style = MaterialTheme.typography.titleSmall)
                        } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    } }
                }
            }
        }
    }
}

@Composable private fun ReadingBookTimeRow(entry: ReadingTimeEntry, rank: Int, totalMillis: Long) {
    val share = entry.millis.toFloat() / totalMillis.coerceAtLeast(1)
    ProfileDetailCard {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$rank", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.title.ifBlank { "未命名作品" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatReadingTime(entry.millis)} · 占累计 ${entry.millis * 100 / totalMillis}%", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth().height(4.dp), drawStopIndicator = {})
            }
        }
    }
}

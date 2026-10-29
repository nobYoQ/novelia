package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
        item { ProfileToggle("记录阅读时长", "仅统计此设备前台阅读", stats.enabled, onChange = onEnabled) }
        if(historyPaused) item { Text("阅读历史已暂停，时长统计也已暂停。", color = MaterialTheme.colorScheme.secondary) }
        if(bookKey != null) item { ProfileChoiceRow("统计范围", listOf("本书", "全部书籍"), if(onlyBook) 0 else 1) { onlyBook = it == 0 } }
        item { ProfileDetailCard { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("今日 · ${formatReadingTime(selected.dayMillis(today))}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("reading-statistics-today"))
            Text("近七天 · ${formatReadingTime(days.sumOf { it.second })}")
            Text("累计 · ${formatReadingTime(selected.totalMillis)}", Modifier.testTag("reading-statistics-total"))
            Text("连续阅读 ${selected.streak(today)} 天", style = MaterialTheme.typography.bodySmall)
        } } }
        item { ProfileDetailCard { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("近七天", style = MaterialTheme.typography.titleMedium)
            val maximum = days.maxOf { it.second }.coerceAtLeast(1)
            days.forEach { (date, millis) ->
                Text("${date.monthValue} 月 ${date.dayOfMonth} 日 · ${formatReadingTime(millis)}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { millis.toFloat() / maximum }, modifier = Modifier.fillMaxWidth())
            }
        } } }
        item { Text("按书籍统计", style = MaterialTheme.typography.titleMedium) }
        if(books.isEmpty()) item { ProfileEmptyState("还没有阅读时长", "开始阅读后会自动累计；已读进度不会换算成阅读时长。") }
        items(books, key = { it.key }) { (_, entry) -> ProfileDetailCard {
            ListItem(headlineContent = { Text(entry.title.ifBlank { "未命名作品" }) }, supportingContent = { Text(formatReadingTime(entry.millis)) })
        } }
        item { Text("切到后台、锁屏或打开阅读操作面板时暂停计时。普通阅读连续 5 分钟无操作后暂停；自动阅读和前台朗读可持续累计。每 15 秒及离开页面时保存。保留最近 730 个有记录的日期，随书库备份保存，不通过 WebDAV 同步。", style = MaterialTheme.typography.bodySmall) }
    } }
    if(clear) ConfirmDialog("清空全部阅读统计？", "全部书籍的时长记录将清空，阅读历史、续读位置和笔记会保留。", { clear = false }, confirmLabel = "清空统计") {
        onClear(); clear = false
    }
}

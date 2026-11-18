package cc.novelia.app.ui.shelf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReadingReport
import cc.novelia.app.data.model.formatReadingTime
import cc.novelia.app.ui.account.ProfileDetailCard
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.inkOn
import java.time.LocalDate
import java.time.YearMonth

@Composable internal fun ChartChoices(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    AppChipFlowRow { labels.forEachIndexed { index, label ->
        AppSelectionChip(index == selected, { onSelect(index) }, { Text(label) })
    } }
}

@Composable internal fun ReadingTrendChart(report: ReadingReport, today: LocalDate) {
    var monthly by rememberSaveable { mutableStateOf(false) }
    var chart by rememberSaveable { mutableIntStateOf(0) }
    val thisMonth = YearMonth.from(today)
    val earliest = YearMonth.from(report.daily.keys.minOrNull() ?: today).coerceAtMost(thisMonth)
    var monthStart by rememberSaveable { mutableStateOf<Long?>(null) }
    val month = monthStart?.let { YearMonth.from(LocalDate.ofEpochDay(it)).coerceIn(earliest, thisMonth) } ?: thisMonth
    val first = if(monthly) month.atDay(1) else today.minusDays(6)
    val last = if(monthly) month.atEndOfMonth().coerceAtMost(today) else today
    val days = remember(report, first, last) { report.series(first, last) }
    var selectedDate by rememberSaveable(first.toEpochDay(), last.toEpochDay()) { mutableLongStateOf(last.toEpochDay()) }
    val selected = (selectedDate.coerceIn(first.toEpochDay(), last.toEpochDay()) - first.toEpochDay()).toInt()
    fun select(index: Int) { selectedDate = days[index].first.toEpochDay() }
    val (date, millis) = days[selected]
    ProfileDetailCard(Modifier.testTag("reading-statistics-week")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("阅读趋势", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            ChartChoices(listOf("近七天", "按月"), if(monthly) 1 else 0) { monthly = it == 1 }
            if(monthly) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(onClick = { monthStart = month.minusMonths(1).atDay(1).toEpochDay() }, enabled = month > earliest) { Icon(Icons.Outlined.ChevronLeft, "上个月") }
                Text("${month.year} 年 ${month.monthValue} 月", style = MaterialTheme.typography.titleSmall)
                AppIconButton(onClick = { monthStart = month.plusMonths(1).takeIf { it < thisMonth }?.atDay(1)?.toEpochDay() }, enabled = month < thisMonth) { Icon(Icons.Outlined.ChevronRight, "下个月") }
            }
            ChartChoices(listOf("柱状图", "折线图", "日历"), chart) { chart = it }
            if(chart == 2) ReadingCalendar(days, selected, ::select)
            else ReadingTimePlot(days, selected, line = chart == 1, onSelect = ::select)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                AppIconButton(onClick = { select(selected - 1) }, enabled = selected > 0) { Icon(Icons.Outlined.ChevronLeft, "前一天") }
                Text("${date.monthValue} 月 ${date.dayOfMonth} 日 · ${formatReadingTime(millis)}", Modifier.weight(1f),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                AppIconButton(onClick = { select(selected + 1) }, enabled = selected < days.lastIndex) { Icon(Icons.Outlined.ChevronRight, "后一天") }
            }
            Text("本段累计 ${formatReadingTime(days.sumOf { it.second })} · 阅读 ${days.count { it.second > 0 }} 天",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ReadingTimePlot(days: List<Pair<LocalDate, Long>>, selected: Int, line: Boolean, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val minutes = ((days.maxOf { it.second } + 59_999) / 60_000).coerceAtLeast(1L)
    val peak = minutes * 60_000f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$minutes 分钟", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(120.dp).testTag(if(line) "reading-trend-line" else "reading-trend-bars")
            .pointerInput(days) { detectTapGestures { onSelect((it.x / size.width * days.size).toInt().coerceIn(days.indices)) } }) {
            val slot = size.width / days.size
            val radius = 3.dp.toPx()
            val baseline = size.height - radius
            fun point(index: Int) = Offset((index + .5f) * slot, baseline - days[index].second / peak * (baseline - radius))
            for(level in 0..2) {
                val height = radius + (baseline - radius) * level / 2f
                drawLine(scheme.outlineVariant, Offset(0f, height), Offset(size.width, height), 1.dp.toPx())
            }
            for(index in days.indices) {
                val position = point(index)
                if(line) {
                    if(index > 0) drawLine(scheme.primary, point(index - 1), position, 2.dp.toPx(), StrokeCap.Round)
                    drawCircle(scheme.primary, if(index == selected) radius else radius * .6f, position)
                } else {
                    val width = (slot * .60f).coerceAtMost(32.dp.toPx())
                    val height = (baseline - position.y).coerceAtLeast(2.dp.toPx())
                    val color = if(index == selected) scheme.primary else if(days[index].second > 0) scheme.secondary else scheme.outlineVariant
                    drawRoundRect(color, Offset(position.x - width / 2, baseline - height), Size(width, height), CornerRadius(2.dp.toPx()))
                }
            }
            if(line) drawCircle(scheme.primary, radius * 1.8f, point(selected), style = Stroke(1.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for(date in listOf(days.first().first, days.last().first).distinct()) Text("${date.monthValue}/${date.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun ReadingCalendar(days: List<Pair<LocalDate, Long>>, selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val peak = days.maxOf { it.second }.coerceAtLeast(1L)
    val leading = days.first().first.dayOfWeek.value - 1
    val shape = MaterialTheme.shapes.extraSmall
    Column(Modifier.testTag("reading-trend-calendar"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row { "一二三四五六日".forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it.toString(), style = MaterialTheme.typography.labelMedium) } } }
        repeat((leading + days.size + 6) / 7) { week -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(7) { weekday ->
                val index = week * 7 + weekday - leading
                if(index !in days.indices) Spacer(Modifier.weight(1f)) else {
                    val (date, millis) = days[index]
                    val fill = if(millis == 0L) scheme.surfaceContainerHighest else lerp(scheme.primaryContainer, scheme.primary, millis.toFloat() / peak * .55f)
                    val ink = inkOn(fill)
                    Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(shape).background(fill)
                        .then(if(index == selected) Modifier.border(2.dp, ink, shape) else Modifier)
                        .selectable(index == selected, role = Role.RadioButton, onClick = { onSelect(index) })
                        .semantics { contentDescription = "${date.monthValue} 月 ${date.dayOfMonth} 日，${formatReadingTime(millis)}" },
                        contentAlignment = Alignment.Center) { Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium, color = ink) }
                }
            }
        } }
        Row(Modifier.align(Alignment.End).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("少", style = MaterialTheme.typography.labelSmall)
            for(level in 0..3) Spacer(Modifier.size(10.dp).background(if(level == 0) scheme.surfaceContainerHighest
                else lerp(scheme.primaryContainer, scheme.primary, .55f * level / 3), shape))
            Text("多", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable internal fun ReadingBookPie(report: ReadingReport) {
    val slices = remember(report) { report.bookSlices() }
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, lerp(scheme.primary, scheme.surface, .38f), scheme.tertiary,
        lerp(scheme.tertiary, scheme.surface, .58f), scheme.onSurface, scheme.outlineVariant)
    ProfileDetailCard(Modifier.testTag("reading-book-pie")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(144.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 22.dp.toPx()
                    val inset = Offset(stroke / 2, stroke / 2)
                    val ring = Size(size.width - stroke, size.height - stroke)
                    var start = -90f
                    for((index, slice) in slices.withIndex()) {
                        val sweep = (slice.millis.toDouble() / report.totalMillis * 360).toFloat()
                        drawArc(colors[index], start, sweep, false, inset, ring, style = Stroke(stroke))
                        if(slices.size > 1) drawArc(scheme.surfaceContainerLow, start, minOf(1.5f, sweep / 8), false, inset, ring, style = Stroke(stroke))
                        start += sweep
                    }
                }
                Text("${report.books.size} 本", style = MaterialTheme.typography.titleMedium)
            }
            slices.forEachIndexed { index, slice -> Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.size(12.dp).background(colors[index], CircleShape).border(1.dp, scheme.outlineVariant, CircleShape))
                Text(slice.title.ifBlank { "未命名作品" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${formatReadingTime(slice.millis)} · ${slice.millis * 100 / report.totalMillis}%", style = MaterialTheme.typography.labelMedium)
            } }
        }
    }
}

package cc.novelia.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable data class ReadingTimeEntry(val title: String, val millis: Long)

/** 本机按日期、书目累计；保留最近 730 个有记录的日期，不参与 WebDAV 合并。 */
@Serializable data class ReadingStatistics(
    val enabled: Boolean = true,
    val revision: Long = 0,
    val days: Map<String, Map<String, ReadingTimeEntry>> = emptyMap(),
) {
    val totalMillis get() = days.values.sumOf { books -> books.values.sumOf { it.millis } }
    fun dayMillis(date: LocalDate) = days[date.toString()].orEmpty().values.sumOf { it.millis }
    fun streak(today: LocalDate): Int {
        var date = if(dayMillis(today) > 0) today else today.minusDays(1)
        var count = 0
        while(dayMillis(date) > 0 && count < 730) { count++; date = date.minusDays(1) }
        return count
    }
    internal val nextRevision get() = if(revision == Long.MAX_VALUE) 0 else revision + 1
    fun cleared() = copy(revision = nextRevision, days = emptyMap())
    fun withEnabled(value: Boolean) = if(value == enabled) this else copy(enabled = value, revision = nextRevision)
    fun requireValid() {
        require(revision >= 0 && days.size <= 730) { "阅读统计范围无效" }
        days.forEach { (day, books) ->
            require(day.length == 10 && LocalDate.parse(day).year in 1900..9999 && books.size <= 1000) { "阅读统计日期无效" }
            require(books.all { (key, entry) -> key.isNotBlank() && key.length <= 512 && entry.title.length <= 500 && entry.millis in 1..90_000_000L }) { "阅读统计记录无效" }
        }
    }
}

/** 清空、停用或暂停历史后，旧计时器不能重新写回数据。时长只来自单调时钟。 */
fun LibraryState.withReadingTime(bookKey: String, title: String, startedAt: Long, millis: Long,
    revision: Long, zone: ZoneId = ZoneId.systemDefault()): LibraryState {
    val stats = readingStatistics
    if(!stats.enabled || historyPaused || stats.revision != revision || millis <= 0 || bookKey.isBlank() || bookKey.length > 512) return this
    var cursor = Instant.ofEpochMilli(startedAt)
    var remaining = millis.coerceAtMost(86_400_000)
    val days = stats.days.toMutableMap()
    while(remaining > 0) {
        val date = cursor.atZone(zone).toLocalDate()
        if(date.year !in 1900..9999) return this
        val next = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val duration = minOf(remaining, next - cursor.toEpochMilli())
        if(duration <= 0) return this
        val books = days[date.toString()].orEmpty()
        if(bookKey in books || books.size < 1000) {
            val total = ((books[bookKey]?.millis ?: 0) + duration).coerceAtMost(90_000_000)
            days[date.toString()] = books + (bookKey to ReadingTimeEntry(title.take(500), total))
        }
        remaining -= duration
        cursor = cursor.plusMillis(duration)
    }
    return copy(readingStatistics = stats.copy(days = days.toSortedMap().entries.toList().takeLast(730).associate { it.toPair() }))
}

/** 同一备份反复导入取较大值，避免重复累计；不同设备的时间不做相加。 */
fun ReadingStatistics.mergeDays(other: ReadingStatistics): ReadingStatistics {
    val merged = (days.keys + other.days.keys).sorted().takeLast(730).associateWith { day ->
        val left = days[day].orEmpty(); val right = other.days[day].orEmpty()
        (left.keys + right.keys).take(1000).associateWith { key -> listOfNotNull(left[key], right[key]).maxBy { it.millis } }
    }
    return if(merged == days) this else copy(revision = nextRevision, days = merged)
}

fun formatReadingTime(millis: Long): String {
    if(millis <= 0) return "0 分钟"
    if(millis < 60_000) return "不足 1 分钟"
    val minutes = millis / 60_000
    return if(minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时 ${minutes % 60} 分钟"
}

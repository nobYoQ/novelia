package cc.novelia.app.data.model

import java.time.LocalDate

internal const val READING_RANK_LIMIT = 5

internal class ReadingReport(stats: ReadingStatistics, bookKey: String?) {
    val daily: Map<LocalDate, Long>
    val books: List<Map.Entry<String, ReadingTimeEntry>>
    val totalMillis: Long
    val activeDayAverage: Long get() = if(daily.isEmpty()) 0 else totalMillis / daily.size

    init {
        val totals = mutableMapOf<String, ReadingTimeEntry>()
        daily = buildMap {
            for((day, entries) in stats.days.toSortedMap()) {
                var duration = 0L
                for((key, entry) in entries) {
                    if(bookKey != null && key != bookKey) continue
                    duration += entry.millis
                    totals[key] = entry.copy(millis = (totals[key]?.millis ?: 0L) + entry.millis)
                }
                if(duration > 0) put(LocalDate.parse(day), duration)
            }
        }
        books = totals.entries.sortedWith(compareByDescending<Map.Entry<String, ReadingTimeEntry>> { it.value.millis }.thenBy { it.key })
        totalMillis = daily.values.sum()
    }

    fun series(first: LocalDate, last: LocalDate): List<Pair<LocalDate, Long>> =
        (first.toEpochDay()..last.toEpochDay()).map { LocalDate.ofEpochDay(it).let { day -> day to (daily[day] ?: 0L) } }

    fun streak(today: LocalDate) = readingStreak(today) { it in daily }

    fun bookSlices(): List<ReadingTimeEntry> = books.take(READING_RANK_LIMIT).map { it.value } +
        if(books.size > READING_RANK_LIMIT) listOf(ReadingTimeEntry("其他书籍", books.drop(READING_RANK_LIMIT).sumOf { it.value.millis })) else emptyList()
}

internal fun readingStreak(today: LocalDate, hasReading: (LocalDate) -> Boolean): Int {
    var date = if(hasReading(today)) today else today.minusDays(1)
    var count = 0
    while(hasReading(date) && count < 730) { count++; date = date.minusDays(1) }
    return count
}

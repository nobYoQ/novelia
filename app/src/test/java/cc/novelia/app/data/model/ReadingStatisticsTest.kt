package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReadingStatisticsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val midnight = Instant.parse("2026-10-29T16:00:00Z").toEpochMilli()
    @Test fun splitsMidnightAndPreservesTimeThroughSerialization() {
        val state = LibraryState().withReadingTime("local/a", "书 A", midnight - 30_000, 90_000, 0, zone)
        assertEquals(30_000L, state.readingStatistics.dayMillis(LocalDate.parse("2026-10-29")))
        assertEquals(60_000L, state.readingStatistics.dayMillis(LocalDate.parse("2026-10-30")))
        assertEquals(2, state.readingStatistics.streak(LocalDate.parse("2026-10-30")))
        assertEquals(2, state.readingStatistics.streak(LocalDate.parse("2026-10-31")))
        assertEquals(0, state.readingStatistics.streak(LocalDate.parse("2026-11-01")))
        state.readingStatistics.requireValid()
        assertEquals(state, appJson.decodeFromString<LibraryState>(appJson.encodeToString(state)))
        assertEquals(ReadingStatistics(), appJson.decodeFromString<LibraryState>("{}").readingStatistics)
    }
    @Test fun pausedHistoryDisabledStatisticsAndOldRevisionCannotWrite() {
        val original = LibraryState().withReadingTime("local/a", "书 A", midnight, 60_000, 0, zone)
        val paused = original.copy(historyPaused = true)
        assertEquals(paused, paused.withReadingTime("local/a", "书 A", midnight, 60_000, 0, zone))
        val disabled = original.copy(readingStatistics = original.readingStatistics.withEnabled(false))
        assertEquals(disabled, disabled.withReadingTime("local/a", "书 A", midnight, 60_000, 0, zone))
        val cleared = original.copy(readingStatistics = original.readingStatistics.cleared())
        assertEquals(cleared, cleared.withReadingTime("local/a", "书 A", midnight, 60_000, 0, zone))
        assertEquals(60_000L, cleared.withReadingTime("local/a", "书 A", midnight, 60_000, 1, zone).readingStatistics.totalMillis)
    }
    @Test fun daylightSavingAndRetentionKeepRealDurations() {
        val dst = LibraryState().withReadingTime("local/a", "书 A", Instant.parse("2026-11-01T05:59:30Z").toEpochMilli(), 90_000, 0, ZoneId.of("America/New_York"))
        assertEquals(90_000L, dst.readingStatistics.totalMillis)
        var state = LibraryState()
        repeat(731) { day -> state = state.withReadingTime("local/a", "书 A", midnight + day * 86_400_000L, 1_000, 0, zone) }
        assertEquals(730, state.readingStatistics.days.size)
        assertEquals(730_000L, state.readingStatistics.totalMillis)
        state.readingStatistics.requireValid()
    }
    @Test fun mergeDoesNotCountImportedCopiesTwice() {
        val stats = LibraryState().withReadingTime("local/a", "书 A", midnight, 60_000, 0, zone).readingStatistics
        assertEquals(60_000L, stats.mergeDays(stats).mergeDays(stats).totalMillis)
        assertEquals("不足 1 分钟", formatReadingTime(1_000))
        assertEquals("1 小时 1 分钟", formatReadingTime(3_660_000))
        assertThrows(IllegalArgumentException::class.java) { stats.copy(days = mapOf("2026-10-30" to mapOf("local/a" to ReadingTimeEntry("A", -1)))).requireValid() }
    }

    @Test fun detailTotalsUseTheBookIdentityAndIncludeOnlyItsMountedLocalVolumes() {
        val parent = BookRef("wenku", "parent")
        val volume = BookRef("local", "volume")
        val other = BookRef("local", "unrelated")
        val state = LibraryState(books = listOf(SavedBook(BookCard(volume, "卷一"), parentWenkuKey = parent.key)))
            .withReadingTime(parent.key, "文库", midnight, 10_000, 0, zone)
            .withReadingTime(volume.key, "卷一", midnight, 50_000, 0, zone)
            .withReadingTime(volume.key, "卷一", midnight + 86_400_000L, 70_000, 0, zone)
            .withReadingTime(other.key, "无关书目", midnight, 30_000, 0, zone)

        assertEquals(130_000L, state.readingStatistics.totalMillis(state.readingTimeKeys(parent)))
        assertEquals(120_000L, state.readingStatistics.totalMillis(state.readingTimeKeys(volume)))
        assertEquals(0L, state.readingStatistics.totalMillis(state.readingTimeKeys(BookRef("syosetu", "unread"))))
    }

    @Test fun reportUsesTheSameFilteredDaysForTotalsCalendarAndBookShares() {
        val today = LocalDate.of(2024, 3, 1)
        val stats = ReadingStatistics(days = mapOf(
            "2024-03-01" to mapOf("1" to ReadingTimeEntry("新书名", 90_000)),
            "2024-02-28" to mapOf("7" to ReadingTimeEntry("书 7", 1_000)),
            "2024-02-29" to (1..7).associate { "$it" to ReadingTimeEntry("书 $it", it * 60_000L) }))
        val all = ReadingReport(stats, null)
        assertEquals(stats.totalMillis, all.totalMillis)
        assertEquals(7, all.books.size)
        assertEquals(3, all.streak(today))
        assertEquals(all.books.take(5).map { it.value }, all.bookSlices().dropLast(1))
        assertEquals(ReadingTimeEntry("其他书籍", 270_000), all.bookSlices().last())
        assertEquals(all.totalMillis, all.bookSlices().sumOf { it.millis })
        val book = ReadingReport(stats, "1")
        assertEquals(listOf(0L, 60_000L, 90_000L), book.series(today.minusDays(2), today).map { it.second })
        assertEquals(2, book.daily.size)
        assertEquals(75_000L, book.activeDayAverage)
        assertEquals(2, book.streak(today.plusDays(1)))
        assertEquals(listOf(ReadingTimeEntry("新书名", 150_000)), book.bookSlices())
        val missing = ReadingReport(stats, "missing")
        assertTrue(missing.bookSlices().isEmpty()); assertEquals(0L, missing.activeDayAverage)
    }
}

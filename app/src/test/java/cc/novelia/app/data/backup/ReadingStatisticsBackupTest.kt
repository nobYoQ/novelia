package cc.novelia.app.data.backup

import cc.novelia.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ReadingStatisticsBackupTest {
    @Test fun restoresRemappedBooksAndRepeatedImportsKeepTheLargerTime() {
        val stats = ReadingStatistics(days = mapOf("2026-10-30" to mapOf("local/old" to ReadingTimeEntry("测试书籍", 120_000))))
        val incoming = LibraryState(readingStatistics = stats)
        assertEquals(setOf("old"), localDocumentIds(incoming))
        val remapped = remapBackupLibrary(incoming, mapOf("old" to "new")) { null }
        assertTrue("local/new" in remapped.readingStatistics.days.getValue("2026-10-30"))
        val first = mergeLibraryBackup(LibraryState(), remapped)
        val second = mergeLibraryBackup(first, remapped)
        assertEquals(120_000L, second.readingStatistics.totalMillis)
        assertEquals(first, second)
        assertEquals(1L, second.readingStatistics.revision)
    }
}

package cc.novelia.app

import cc.novelia.app.data.*
import org.junit.Assert.*
import org.junit.Test

class BookUpdatesTest {
    @Test fun anotherEngineCatchingUpIsDetectedWhenMaximumTranslatedCountStaysTheSame() {
        val previous = WebOutline(providerId = "syosetu", novelId = "1", total = 100, gpt = 100, sakura = 20).card()
        val current = previous.copy(translations = previous.translations + ("sakura" to 35))
        assertEquals(previous.translated, current.translated)
        val update = detectBookUpdate(previous.updateSnapshot(1), current.updateSnapshot(2), wenku = false)
        assertEquals(mapOf("sakura" to 15), update.translations)
        assertEquals(0, update.newChapters)
        assertEquals("Sakura 补齐 15 章", update.summary)
        assertTrue(update.hasChanges)
    }

    @Test fun aLegacyCardWithoutEngineCountsEstablishesBaselineWithoutInventingTranslationUpdates() {
        val legacy = appJson.decodeFromString<BookCard>("""{"ref":{"provider":"syosetu","id":"1"},"title":"作品","total":100,"translated":80}""")
        val current = legacy.copy(translations = mapOf("sakura" to 90, "gpt" to 100, "youdao" to 10))
        val baseline = detectBookUpdate(legacy.updateSnapshot(), current.updateSnapshot(10), false)
        assertFalse(baseline.hasChanges)
        assertTrue(baseline.translations.isEmpty())
        val afterBaseline = detectBookUpdate(current.updateSnapshot(10), current.copy(translations = current.translations + ("sakura" to 95)).updateSnapshot(20), false)
        assertEquals(mapOf("sakura" to 5), afterBaseline.translations)
    }

    @Test fun aLegacyCardCanStillReportActuallyNewChapters() {
        val previous = BookUpdateSnapshot(total = 10)
        val current = BookUpdateSnapshot(total = 12, translations = mapOf("gpt" to 12), checkedAt = 20)
        val changes = detectBookUpdate(previous, current, false)
        assertEquals(2, changes.newChapters)
        assertTrue(changes.translations.isEmpty())
    }

    @Test fun replacingAWenkuVolumeNameIsDetectedEvenWithUnchangedFileCount() {
        val ref = BookRef("wenku", "book")
        val previous = WenkuDetail(volumeJp = listOf(JapaneseVolume(volumeId = "第一卷.epub")), volumeZh = listOf("旧版.txt")).card(ref)
        val current = WenkuDetail(volumeJp = listOf(JapaneseVolume(volumeId = "第一卷.epub")), volumeZh = listOf("新版.txt")).card(ref)
        assertEquals(previous.total, current.total)
        val update = detectBookUpdate(previous.updateSnapshot(), current.updateSnapshot(40), true)
        assertEquals(1, update.newVolumes)
        assertEquals(0, update.newChapters)
        assertEquals("新增 1 个分卷文件", update.summary)
    }

    @Test fun decrementsAndPreviouslyUnknownEnginesDoNotBecomePositiveUpdates() {
        val previous = BookUpdateSnapshot(total = 100, translations = mapOf("sakura" to 80))
        val current = BookUpdateSnapshot(total = 90, translations = mapOf("sakura" to 70, "gpt" to 90))
        assertFalse(detectBookUpdate(previous, current, false).hasChanges)
        assertEquals(0, detectBookUpdate(BookUpdateSnapshot(total = 3), BookUpdateSnapshot(total = 2), true).newVolumes)
    }

    @Test fun aWenkuOutlineEstablishesItsFirstVolumeBaseline() {
        val outline = BookUpdateSnapshot()
        val first = BookUpdateSnapshot(total = 2, volumeIds = listOf("jp:第一卷.epub", "zh:第一卷.txt"), checkedAt = 10)
        assertFalse(detectBookUpdate(outline, first, true).hasChanges)
        val second = first.copy(total = 3, volumeIds = first.volumeIds + "jp:第二卷.epub", checkedAt = 20)
        assertEquals(1, detectBookUpdate(first, second, true).newVolumes)
        assertEquals(1, detectBookUpdate(BookUpdateSnapshot(checkedAt = 10), second.copy(total = 1, volumeIds = listOf("jp:第一卷.epub")), true).newVolumes)
    }

    @Test fun unreadChangesAccumulateDeltasAcrossChecksWithoutCountingTheBaselineAgain() {
        val first = BookUpdateSnapshot(100, mapOf("sakura" to 50, "gpt" to 100), checkedAt = 1)
        val second = BookUpdateSnapshot(102, mapOf("sakura" to 60, "gpt" to 100), checkedAt = 2)
        val third = BookUpdateSnapshot(105, mapOf("sakura" to 60, "gpt" to 105), checkedAt = 3)
        val unread = detectBookUpdate(first, second, false).accumulate(detectBookUpdate(second, third, false))
        assertEquals(5, unread.newChapters)
        assertEquals(mapOf("sakura" to 10, "gpt" to 5), unread.translations)
        assertEquals(3L, unread.checkedAt)
        assertEquals("新增 5 章 · Sakura 补齐 10 章 · GPT 补齐 5 章", unread.summary)
        assertEquals(unread, unread.accumulate(detectBookUpdate(third, third, false)))
    }

    @Test fun notificationRelevanceFollowsPreferredEngineWhileNewContentAlwaysMatters() {
        val gptFirst = ReaderSettings(engines = listOf("gpt", "sakura"))
        val sakuraOnly = BookUpdateInfo(translations = mapOf("sakura" to 8))
        assertFalse(sakuraOnly.relevantTo(gptFirst))
        assertTrue(sakuraOnly.relevantTo(ReaderSettings(engines = listOf("sakura", "gpt"))))
        assertTrue(BookUpdateInfo(translations = mapOf("gpt" to 1)).relevantTo(gptFirst))
        assertFalse(sakuraOnly.relevantTo(ReaderSettings(engines = emptyList())))
        assertTrue(BookUpdateInfo(newChapters = 1).relevantTo(ReaderSettings(engines = emptyList())))
        assertTrue(BookUpdateInfo(newVolumes = 1).relevantTo(ReaderSettings(engines = emptyList())))
        val japaneseOnly = gptFirst.copy(mode = "jp")
        assertFalse(BookUpdateInfo(translations = mapOf("gpt" to 1)).relevantTo(japaneseOnly))
        assertTrue(BookUpdateInfo(newChapters = 1).relevantTo(japaneseOnly))
        assertTrue(BookUpdateInfo(newVolumes = 1).relevantTo(japaneseOnly))
    }
}

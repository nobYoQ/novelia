package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookMetadataTest {
    private val ref = BookRef("syosetu", "story")

    @Test fun partialResponsesRetainTheKnownTimeAndReplaceOtherMetadata() {
        val previous = BookCard(ref, "旧标题", total = 8, favored = "old-folder", updateAt = 1_700_000_000L)
        for (missingTime in listOf(null, 0L, -1L)) {
            val incoming = BookCard(ref, "新标题", total = 10, favored = "new-folder", updateAt = missingTime)
            assertEquals(incoming.copy(updateAt = previous.updateAt), incoming.withKnownUpdateTime(previous))
        }
    }

    @Test fun aValidResponseTimeTakesPrecedenceEvenWhenItIsEarlier() {
        val previous = BookCard(ref, "标题", updateAt = 1_700_000_100L)
        val incoming = previous.copy(updateAt = 1_700_000_000L)
        assertEquals(incoming, incoming.withKnownUpdateTime(previous))
    }

    @Test fun invalidOrUnrelatedPreviousTimesAreNotInherited() {
        val incoming = BookCard(ref, "标题", updateAt = 0)
        assertNull(incoming.withKnownUpdateTime(null).updateAt)
        assertNull(incoming.withKnownUpdateTime(incoming.copy(updateAt = -1)).updateAt)
        assertNull(incoming.withKnownUpdateTime(incoming.copy(ref = BookRef("syosetu", "another"), updateAt = 1_700_000_000L)).updateAt)
    }

    @Test fun legacyDetailJsonDerivesTheLatestChapterTimeInSeconds() {
        val detail = appJson.decodeFromString<WebDetail>("""{
            "titleJp":"作品",
            "syncAt":1800000000,
            "toc":[
                {"titleJp":"第一章","chapterId":"1","createAt":1700000100},
                {"titleJp":"卷标题","createAt":1900000000},
                {"titleJp":"第二章","chapterId":"2","createAt":1700000000},
                {"titleJp":"第三章","chapterId":"3"},
                {"titleJp":"第四章","chapterId":"4","createAt":-1}
            ]
        }""")
        assertEquals(1_700_000_100L, detail.lastUpdatedAt)
        assertEquals(1_700_000_100L, detail.card(ref).updateAt)
        assertEquals("1", detail.lastUpdatedChapter?.chapterId)
    }

    @Test fun explicitDetailTimeTakesPrecedenceOverChapterTimes() {
        val detail = WebDetail(updateAt = 1_700_000_000L, toc = listOf(TocItem(chapterId = "1", createAt = 1_700_000_100L)))
        assertEquals(1_700_000_000L, detail.card(ref).updateAt)
    }

    @Test fun invalidExplicitTimeFallsBackToChapterTime() {
        for (invalidTime in listOf(0L, -1L)) {
            val detail = WebDetail(updateAt = invalidTime, toc = listOf(TocItem(chapterId = "1", createAt = 1_700_000_000L)))
            assertEquals(1_700_000_000L, detail.card(ref).updateAt)
        }
    }

    @Test fun synchronizationTimeIsNotUsedAsAContentUpdateTime() {
        val detail = WebDetail(syncAt = 1_700_000_000L, toc = listOf(TocItem(chapterId = "1", createAt = 0)))
        assertNull(detail.lastUpdatedAt)
        assertNull(detail.card(ref).updateAt)
    }

    @Test fun latestChapterFallsBackToTheLastRealChapterAndBreaksTimestampTiesInTocOrder() {
        val undated = WebDetail(toc = listOf(TocItem(chapterId = "a"), TocItem(chapterId = "b"), TocItem(titleJp = "下一卷")))
        assertEquals("b", undated.lastUpdatedChapter?.chapterId)
        assertNull(undated.lastUpdatedAt)
        val dated = undated.copy(toc = undated.toc.map { it.copy(createAt = 1_700_000_000L) })
        assertEquals("b", dated.lastUpdatedChapter?.chapterId)
        assertNull(WebDetail(toc = listOf(TocItem(titleJp = "卷标题"))).lastUpdatedChapter)
    }
}

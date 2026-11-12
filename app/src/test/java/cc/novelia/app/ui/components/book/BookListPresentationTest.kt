package cc.novelia.app.ui.components.book

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.CloudReadingProgress
import cc.novelia.app.data.model.WebOutline
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.model.CloudFolders
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.library.withCloudReadingMetadata
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import cc.novelia.app.data.updates.BookUpdateInfo
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class BookListPresentationTest {
    private val book = BookCard(BookRef("syosetu", "n1"), "风与书页", total = 10)

    @Test fun discoveryFavoritesUseBookIdentityAndCurrentAccountIntent() {
        val cloud = book.copy(favored = "reading")
        val local = BookListPresentation(books = mapOf(book.ref.key to SavedBook(cloud)))
        assertTrue(local.favoriteState(book).local)
        assertNull(local.favoriteState(cloud).cloudFolder)
        assertFalse(local.favoriteState(book.copy(ref = BookRef("kakuyomu", "n1"))).isSaved)
        assertFalse(BookListPresentation().favoriteState(cloud).isSaved)
        val cloudOnly = BookListPresentation(account = "alice").favoriteState(cloud)
        assertFalse(cloudOnly.local)
        assertEquals("reading", cloudOnly.cloudFolder)
        val both = local.copy(account = "alice").favoriteState(cloud)
        assertTrue(both.local)
        assertEquals("reading", both.cloudFolder)

        val removal = PendingAction("remove", "alice", "DELETE", "user/favored-web/reading/${book.ref.key}")
        val pending = BookListPresentation(account = "alice", pending = listOf(removal))
        assertFalse(pending.favoriteState(cloud).isSaved)
        val removedCloud = pending.copy(books = local.books).favoriteState(cloud)
        assertTrue(removedCloud.local)
        assertNull(removedCloud.cloudFolder)
        assertFalse(pending.copy(account = "bob").favoriteState(book).isSaved)
        val queued = pending.copy(pending = listOf(removal.copy(method = "PUT"))).favoriteState(book)
        assertEquals("reading", queued.cloudFolder)
        assertEquals("PUT", queued.pendingMethod)

        val wenku = book.copy(ref = BookRef("wenku", "n1"))
        assertFalse(pending.copy(pending = listOf(removal.copy(method = "PUT"))).favoriteState(wenku).isSaved)
        assertEquals("reading", pending.copy(pending = listOf(removal.copy(method = "PUT", path = "user/favored-wenku/reading/n1"))).favoriteState(wenku).cloudFolder)
    }

    @Test fun folderNamesFollowLocalMembershipBookTypeAndLatestPendingMove() {
        val folders = CloudFolders(
            favoredWeb = listOf(Folder("default", "网络默认"), Folder("reading", "网络待读")),
            favoredWenku = listOf(Folder("default", "文库默认"), Folder("reading", "文库待读")),
        )
        val local = SavedBook(book.copy(favored = "stale-account-folder"), "本地追更")
        val presentation = BookListPresentation(books = mapOf(book.ref.key to local), account = "alice", cloudFolders = folders)
        val state = presentation.favoriteState(book.copy(favored = "default"))
        assertEquals("本地追更", state.localFolderName)
        assertEquals("本地追更", state.localIndicatorLabel)
        assertEquals("网络默认", state.cloudFolderName)
        assertEquals("网络默认", state.cloudIndicatorLabel)
        assertNull(presentation.favoriteState(book).cloudFolderName)
        assertEquals("文库默认", presentation.favoriteState(book.copy(ref = BookRef("wenku", "n1"), favored = "default")).cloudFolderName)

        val move = PendingAction("move", "alice", "PUT", "user/favored-web/reading/${book.ref.key}")
        val moved = presentation.copy(pending = listOf(move)).favoriteState(book.copy(favored = "default"))
        assertEquals("网络待读", moved.cloudFolderName)
        assertEquals("网络待读 · 待同步", moved.cloudIndicatorLabel)
        assertEquals("本地追更", moved.localFolderName)
        assertNull(presentation.copy(pending = listOf(move.copy(method = "DELETE"))).favoriteState(book.copy(favored = "default")).cloudFolderName)
        assertNull(presentation.copy(account = null).favoriteState(book.copy(favored = "default")).cloudFolderName)
        assertEquals("网络默认", presentation.copy(account = "bob", pending = listOf(move)).favoriteState(book.copy(favored = "default")).cloudFolderName)

        val unavailable = presentation.copy(cloudFolders = null).favoriteState(book.copy(favored = "default"))
        assertEquals("云端收藏", unavailable.cloudIndicatorLabel)
        assertEquals("云端收藏待同步", presentation.copy(cloudFolders = null, pending = listOf(move)).favoriteState(book).cloudIndicatorLabel)
        assertEquals("云端收藏", presentation.favoriteState(book.copy(favored = "missing")).cloudIndicatorLabel)
        assertEquals("云端收藏", presentation.copy(cloudFolders = CloudFolders(favoredWeb = listOf(Folder("default", "  "))))
            .favoriteState(book.copy(favored = "default")).cloudIndicatorLabel)
    }

    @Test fun cloudOnlyHistoryIsNotUnreadOrAFabricatedZeroPercent() {
        val remote = appJson.decodeFromString<WebOutline>("""{"providerId":"syosetu","novelId":"n1","total":10,"lastReadAt":1700000000}""").card("alice")
        val row = bookRowStatus(remote, null, null, null, "alice")
        assertEquals("有阅读记录", row.progressLabel)
        assertNull(row.progress)
        assertEquals("云端进度待同步", bookRowStatus(remote, null, null, null, "bob").progressLabel)
        assertEquals("未读", bookRowStatus(remote, null, null, null).progressLabel)
    }

    @Test fun cloudChapterPositionExcludesDirectoryHeadingsAndMissingChaptersStayUnknown() {
        val detail = WebDetail(toc = listOf(TocItem(titleJp = "第一卷"), TocItem(chapterId = "a"), TocItem(chapterId = "b"), TocItem(chapterId = "c")), lastReadChapterId = "b")
        val row = bookRowStatus(detail.card(book.ref, "alice"), null, null, null, "alice")
        assertEquals("读到第 2 章", row.progressLabel)
        assertEquals(2f / 3, row.progress!!, .0001f)
        val removed = bookRowStatus(detail.copy(lastReadChapterId = "removed").card(book.ref, "alice"), null, null, null, "alice")
        assertEquals("有阅读记录", removed.progressLabel)
        assertNull(removed.progress)
    }

    @Test fun newerCloudHistoryTakesPrecedenceWhileNewerLocalPositionsRemainPrecise() {
        val remote = book.copy(cloudReading = CloudReadingProgress("alice", lastReadAt = 20))
        val local = Position("four", index = 9, updatedAt = 10_000, chapterIndex = 3, chapterCount = 10, paragraphCount = 10)
        assertEquals("有阅读记录", bookRowStatus(remote, null, local, null, "alice").progressLabel)
        assertEquals("已读 40%", bookRowStatus(remote, null, local.copy(updatedAt = 21_000), null, "alice").progressLabel)
    }

    @Test fun refreshedHistoryUpdatesOnlyMatchingSavedBooksAndNeverReplacesLocalAnchors() {
        val position = Position("local-chapter", index = 8)
        val before = LibraryState(books = listOf(SavedBook(book)), positions = mapOf(book.ref.key to position))
        val remote = book.copy(cloudReading = CloudReadingProgress("alice", lastReadAt = 20))
        assertEquals(before, before.withCloudReadingMetadata(listOf(remote), "bob"))
        assertEquals(before, before.withCloudReadingMetadata(listOf(remote), null))
        val merged = before.withCloudReadingMetadata(listOf(remote), "alice")
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(merged))
        assertEquals(before.positions, restored.positions)
        assertEquals("有阅读记录", bookRowStatus(restored.books.single().book, null, null, null, "alice").progressLabel)
        val cleared = remote.copy(cloudReading = CloudReadingProgress("alice", chapterResolved = true))
        assertEquals("未读", bookRowStatus(cleared, restored.books.single(), null, null, "alice").progressLabel)
    }

    @Test fun absentFavoriteHistoryAndLegacyEmptyRecordsAreUnknownUntilDetailIsResolved() {
        val outline = WebOutline(providerId = "syosetu", novelId = "n1").card("alice")
        assertNull(outline.cloudReading)
        for(card in listOf(outline, book.copy(cloudReading = CloudReadingProgress("alice")))) {
            val row = bookRowStatus(card, null, null, null, "alice")
            assertNull(row.progress)
            assertEquals("云端进度待同步", row.progressLabel)
        }
        val known = book.copy(cloudReading = CloudReadingProgress("alice", chapterId = "c", chapterIndex = 2, chapterCount = 10, chapterResolved = true))
        assertEquals(.3f, bookRowStatus(outline, SavedBook(known), null, null, "alice").progress!!, .0001f)
        assertEquals("未读", bookRowStatus(WebDetail().card(book.ref, "alice"), null, null, null, "alice").progressLabel)
    }

    @Test fun cloudDetailsWithoutTimestampsCanRecoverFromAnOlderLocalZeroPosition() {
        val cloud = book.copy(cloudReading = CloudReadingProgress("alice", chapterId = "c", chapterIndex = 5, chapterCount = 10, chapterResolved = true))
        val beginning = Position("first", index = 1, chapterIndex = 0, chapterCount = 10, paragraphCount = 10)
        assertEquals(.6f, bookRowStatus(cloud, null, beginning, null, "alice").progress!!, .0001f)
        assertEquals(.8f, bookRowStatus(cloud, null, beginning.copy(chapterIndex = 7, index = 9), null, "alice").progress!!, .0001f)
    }

    @Test fun cloudChapterLabelAndFillUseTheSameReachedChapterIncludingFirstAndLast() {
        val remote = book.copy(total = 100)
        for(number in listOf(1, 50, 100)) {
            val card = remote.copy(cloudReading = CloudReadingProgress("alice", chapterId = "$number",
                chapterIndex = number - 1, chapterCount = 100, chapterResolved = true))
            val row = bookRowStatus(card, null, null, null, "alice")
            assertEquals("读到第 $number 章", row.progressLabel)
            assertEquals(number / 100f, row.progress!!, .0001f)
            assertEquals(number / 200f, bookRowStatus(card.copy(total = 200), null, null, null, "alice").progress!!, .0001f)
        }
    }

    @Test fun cloudListsUseOneCloudPositionForTextAndBarEvenWithDifferentLocalState() {
        val remote = book.copy(cloudReading = CloudReadingProgress("alice", lastReadAt = 10, chapterId = "3",
            chapterIndex = 2, chapterCount = 10, chapterResolved = true))
        val local = Position("1", index = 1, updatedAt = 20_000, chapterIndex = 0, chapterCount = 10, paragraphCount = 5)
        for(saved in listOf<SavedBook?>(null, SavedBook(book, status = "读完"))) {
            val row = bookRowStatus(remote, saved, local, null, "alice", preferCloud = true)
            assertEquals("读到第 3 章", row.progressLabel)
            assertEquals(.3f, row.progress!!, .0001f)
        }
        assertEquals(.1f, bookRowStatus(remote, null, local, null, "alice").progress!!, .0001f)
        assertEquals("已读 100%", bookRowStatus(remote, SavedBook(book, status = "读完"), local, null, "alice").progressLabel)
    }

    @Test fun listMetadataDoesNotEraseKnownChaptersOrLocalUpdateBaselines() {
        val known = book.copy(cloudReading = CloudReadingProgress("alice", lastReadAt = 20, chapterId = "c", chapterIndex = 2, chapterCount = 10, chapterResolved = true))
        val state = LibraryState(books = listOf(SavedBook(known)))
        val marker = book.copy(total = 12, cloudReading = CloudReadingProgress("alice", lastReadAt = 20), updateAt = 1_700_000_000L)
        val result = state.withCloudReadingMetadata(listOf(marker), "alice")
        assertEquals(known.cloudReading, result.books.single().book.cloudReading)
        assertEquals(10, result.books.single().book.total)
        assertEquals(marker.updateAt, result.books.single().book.updateAt)
        assertNull(state.withCloudReadingMetadata(listOf(marker.copy(cloudReading = marker.cloudReading!!.copy(lastReadAt = 21))), "alice")
            .books.single().book.cloudReading?.chapterIndex)
    }

    @Test fun readingProgressUsesTheReachedChapterAndLatestKnownTotal() {
        val position = Position("four", index = 9, chapterIndex = 3, chapterCount = 10, paragraphCount = 10)
        val row = bookRowStatus(book, null, position, BookUpdateInfo(newChapters = 3))
        assertEquals(.4f, row.progress!!, .0001f)
        assertEquals("已读 40%", row.progressLabel)
        assertEquals("更新 3 章", row.updateLabel)
        assertEquals(.2f, bookRowStatus(book.copy(total = 20), null, position, null).progress!!, .0001f)
    }

    @Test fun openingASingleChapterReachesTheLatestChapterWithoutMarkingItsContentCompleted() {
        val single = book.copy(total = 1)
        val first = Position("one", index = 1, chapterIndex = 0, chapterCount = 1, paragraphCount = 1)
        assertFalse(first.chapterCompleted)
        assertEquals(1f, bookRowStatus(single, null, first, null).progress!!, .0001f)
        assertEquals("已读 100%", bookRowStatus(single, null, first, null).progressLabel)
        assertEquals("已读 100%", bookRowStatus(single, null, first.copy(index = 2), null).progressLabel)
    }

    @Test fun finishingTheLastScreenReachesOneHundredWithoutMovingTheRestoreAnchor() {
        val lastScreen = Position("last", index = 100, offset = 260, textOffset = 720,
            chapterIndex = 9, chapterCount = 10, paragraphCount = 100)
        assertEquals("已读 100%", bookRowStatus(book, null, lastScreen, null).progressLabel)
        val completed = lastScreen.copy(chapterCompleted = true)
        val restored = appJson.decodeFromString<Position>(appJson.encodeToString(completed))
        assertEquals(lastScreen, restored.copy(chapterCompleted = false))
        assertEquals("已读 100%", bookRowStatus(book, null, restored, null).progressLabel)
        assertEquals(1f, bookRowStatus(book, null, restored, null).progress!!, 0f)
        assertEquals("已读 90%", bookRowStatus(book.copy(total = 11), null, restored, null).progressLabel)
        assertEquals(10f / 11, bookRowStatus(book.copy(total = 11), null, restored, null).progress!!, .0001f)
    }

    @Test fun chapterReadingProgressIsIndependentOfParagraphAndCompletionAnchors() {
        val single = book.copy(total = 1)
        val middle = Position("one", index = 1, offset = 900, textOffset = 700,
            chapterIndex = 0, chapterCount = 1, paragraphCount = 1)
        assertEquals("已读 100%", bookRowStatus(single, null, middle, null).progressLabel)
        val finished = middle.copy(chapterCompleted = true)
        assertEquals("已读 100%", bookRowStatus(single, null, finished, null).progressLabel)
        assertEquals("已读 100%", bookRowStatus(single, null, finished.copy(offset = 0, textOffset = 0), null).progressLabel)
        val legacy = appJson.decodeFromString<Position>("""{"chapterId":"one","index":1,"chapterIndex":0,"chapterCount":1,"paragraphCount":1}""")
        assertFalse(legacy.chapterCompleted)
        assertEquals("已读 100%", bookRowStatus(single, null, legacy, null).progressLabel)
    }

    @Test fun legacyOrInvalidPositionsNeverInventAPercentage() {
        assertEquals("未读", bookRowStatus(book, null, null, null).progressLabel)
        listOf(Position("four"), Position("four", chapterIndex = -1), Position("four", chapterIndex = 10, chapterCount = 10))
            .forEach { position ->
                val row = bookRowStatus(book, null, position, null)
                assertNull(row.progress)
                assertEquals("继续阅读", row.progressLabel)
            }
        assertEquals("已读 100%", bookRowStatus(book, SavedBook(book, status = "读完"), null, null).progressLabel)
    }

    @Test fun translatedChaptersAreNotCountedAsNewStoryChapters() {
        val row = bookRowStatus(book, null, null, BookUpdateInfo(translations = mapOf("sakura" to 3)))
        assertEquals("译文更新", row.updateLabel)
        assertEquals("更新 2 卷", bookRowStatus(book, null, null, BookUpdateInfo(newVolumes = 2)).updateLabel)
    }

    @Test fun serverDatesUseSecondsAndMissingDatesStayAbsent() {
        assertEquals("2024-01-01", bookUpdateDate(1704067200, ZoneId.of("UTC")))
        assertNull(bookUpdateDate(null))
        assertNull(bookUpdateDate(0))
        assertNull(bookUpdateDate(-1))
    }

    @Test fun finishedStatusCannotHideNewChaptersOrUseAnOlderListTotal() {
        val old = Position("last", chapterIndex = 9, chapterCount = 10, chapterCompleted = true)
        val latest = book.copy(total = 11)
        val saved = SavedBook(latest, status = "读完", hasUpdates = true)
        val row = bookRowStatus(book, saved, old, BookUpdateInfo(newChapters = 1))
        assertEquals(10f / 11, row.progress!!, .0001f)
        assertEquals("已读 90%", row.progressLabel)
        assertEquals("更新 1 章", row.updateLabel)
        val noAnchor = bookRowStatus(latest, saved, null, BookUpdateInfo(newChapters = 1))
        assertEquals(10f / 11, noAnchor.progress!!, .0001f)
        assertEquals("已读 90%", noAnchor.progressLabel)
    }

    @Test fun localAndCloudProgressAgreeAtEveryReachedChapter() {
        for(index in 0 until book.total) {
            val local = Position("$index", chapterIndex = index, chapterCount = book.total)
            val remote = book.copy(cloudReading = CloudReadingProgress("alice", chapterId = "$index",
                chapterIndex = index, chapterCount = book.total, chapterResolved = true))
            assertEquals(bookRowStatus(remote, null, null, null, "alice").progress,
                bookRowStatus(book, null, local, null).progress)
        }
    }
}

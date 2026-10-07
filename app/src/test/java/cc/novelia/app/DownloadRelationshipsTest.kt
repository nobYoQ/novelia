package cc.novelia.app

import cc.novelia.app.data.library.*
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DownloadRelationshipsTest {
    private val parent = BookRef("wenku", "series")
    private val other = BookRef("wenku", "other")
    private val first = BookRef("local", "first")
    private val second = BookRef("local", "second")
    private fun entry(id: String, source: BookRef = parent, volume: String = "第1卷.epub") =
        DownloadEntry(id, volume, "$id.txt", "https://n.novelia.cc/api/wenku/${source.id}/file/${cc.novelia.app.data.network.encodeSegment(volume)}", sourceBook = source)
    private fun library(): LibraryState {
        val one = entry("one"); val two = entry("two", volume = "第2卷.txt"); val foreign = entry("foreign", other)
        return LibraryState(books = listOf(SavedBook(BookCard(parent, "文库")), SavedBook(BookCard(other, "同名文库")),
            SavedBook(BookCard(first, "重命名分卷"), parentWenkuKey = parent.key, sourceVolumeId = one.title),
            SavedBook(BookCard(second, "第二卷"), parentWenkuKey = parent.key, sourceVolumeId = two.title)),
            downloads = listOf(one, two, foreign), positions = mapOf(first.key to Position("c"), second.key to Position("c")))
            .withDownloadLink(one, first).withDownloadLink(two, second)
    }

    @Test fun legacyUrlsRecoverBookAndDecodedVolumeIdentityWithoutTitleMatching() {
        val legacy = entry("one").copy(sourceBook = null, title = "改名")
        assertEquals(parent, legacy.originBook())
        assertEquals("第1卷.epub", legacy.originVolumeId())
        assertEquals(BookRef("syosetu", "n123"), legacy.copy(url = "https://n.novelia.cc/api/novel/syosetu/n123/file").originBook())
        assertNull(legacy.copy(url = "https://example.invalid/file").originBook())
    }

    @Test fun readingLinkSurvivesDownloadCleanupShelfRemovalAndSerialization() {
        val removed = library().withoutBook(first).copy(downloads = emptyList())
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(removed))
        assertEquals(first, restored.downloadedVolume(parent, "第1卷.epub"))
        assertNull(restored.downloadedVolume(other, "第1卷.epub"))
        val old = appJson.decodeFromString<LibraryState>("{}"); assertTrue(old.downloadLinks.isEmpty())
    }

    @Test fun upgradeRetainsLegacyVolumeIdentityAfterParentIsRemovedAndMigrationIsIdempotent() {
        val legacy = library().copy(downloadLinks = emptyList(), downloads = emptyList()).withMigratedDownloadLinks()
        assertEquals(legacy, legacy.withMigratedDownloadLinks())
        val removed = legacy.withoutBook(parent)
        assertEquals(first, removed.downloadedVolume(parent, "第1卷.epub"))
        assertEquals(setOf(parent, first, second), removed.bookDeletionPlan(setOf(parent)).books)
    }

    @Test fun wholeSeriesDeletionIncludesDetachedVolumesAndUnimportedTasksButKeepsOtherSeries() {
        val state = library().withoutBook(first).copy(downloads = library().downloads + entry("waiting", volume = "第3卷.txt"))
        val plan = state.bookDeletionPlan(setOf(parent))
        assertEquals(setOf(parent, first, second), plan.books)
        assertEquals(setOf("one", "two", "waiting"), plan.downloadIds)
        val removed = state.withDeletedBookRecords(plan.books, true)
        assertTrue(removed.positions.isEmpty())
        assertEquals(listOf(other), removed.books.map { it.book.ref })
        assertTrue(removed.downloadLinks.isEmpty())
    }

    @Test fun singleVolumeDeletionKeepsParentOtherVolumesAndReadingDataWhenRequested() {
        val state = library()
        val plan = state.bookDeletionPlan(setOf(first), includeSourceBooks = false)
        assertEquals(setOf(first), plan.books)
        assertEquals(setOf("one"), plan.downloadIds)
        val removed = state.withDeletedBookRecords(plan.books, false)
        assertEquals(state.positions, removed.positions)
        assertTrue(removed.books.any { it.book.ref == parent })
        assertEquals(second, removed.downloadedVolume(parent, "第2卷.txt"))
        assertNull(removed.downloadedVolume(parent, "第1卷.epub"))
    }

    @Test fun downloadEntryDeletionExpandsOnlyItsWenkuVolumeIncludingLegacyMountedCopy() {
        val state = library().copy(downloadLinks = emptyList())
        val plan = state.bookDeletionPlan(downloadIds = setOf("one"))
        assertEquals(setOf(first), plan.books)
        assertEquals(setOf("one"), plan.downloadIds)
    }

    @Test fun deletingOneSourcePreservesHashDeduplicatedCopyStillUsedByAnotherBook() {
        val one = entry("one"); val foreign = entry("foreign", other)
        val state = LibraryState(books = listOf(SavedBook(BookCard(parent, "文库")), SavedBook(BookCard(other, "另一文库")),
            SavedBook(BookCard(first, "共享副本"), parentWenkuKey = parent.key)), downloads = listOf(one, foreign))
            .withDownloadLink(one, first).withDownloadLink(foreign, first)
        val plan = state.bookDeletionPlan(setOf(parent))
        assertEquals(setOf(parent), plan.books)
        assertEquals(setOf("one"), plan.downloadIds)
        val removed = state.withDeletedBookRecords(plan.books, true, plan.linkIds)
        assertEquals(first, removed.downloadedVolume(other, foreign.title))
        assertNull(removed.downloadedVolume(parent, one.title))
    }

    @Test fun completeWebDeletionWorksFromEitherShelfCopyAndIncludesAllDownloadFormats() {
        val web = BookRef("syosetu", "n123")
        val download = entry("web").copy(sourceBook = web)
        val state = LibraryState(books = listOf(SavedBook(BookCard(web, "网络小说")), SavedBook(BookCard(first, "本地副本"))),
            downloads = listOf(download, download.copy(id = "other-format")))
            .withDownloadLink(download, first)
        assertEquals(setOf(first, web), state.bookDeletionPlan(setOf(first)).books)
        assertEquals(setOf("web", "other-format"), state.bookDeletionPlan(setOf(web)).downloadIds)
        assertEquals(setOf(first), state.bookDeletionPlan(setOf(first), includeSourceBooks = false).books)
        assertEquals(setOf("web"), state.bookDeletionPlan(setOf(first), includeSourceBooks = false).downloadIds)
    }
}

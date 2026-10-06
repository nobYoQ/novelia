package cc.novelia.app

import cc.novelia.app.data.library.*
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class WenkuSiteOrderTest {
    private val parent = SavedBook(BookCard(BookRef("wenku", "series"), "小说"))
    private fun volume(id: String, title: String, source: String? = null) =
        SavedBook(BookCard(BookRef("local", id), title), parentWenkuKey = parent.book.ref.key, sourceVolumeId = source)
    private fun keys(state: LibraryState) = state.books.first { it.book.ref == parent.book.ref }.volumeOrder

    @Test fun siteNormalizationSortsNumericJapaneseVolumesAndKeepsChineseOrder() {
        val ids = siteVolumeIds(listOf("zh:乙.epub", "jp:小说【特典】10.epub", "jp:小说[扫描](2).epub",
            "jp:小说１.epub", "zh:甲.epub", "jp:小说１.epub"))
        assertEquals(listOf("jp:小说１.epub", "jp:小说[扫描](2).epub", "jp:小说【特典】10.epub", "zh:乙.epub", "zh:甲.epub"), ids)
    }

    @Test fun provenanceSurvivesRenameAndDescendingLeavesUnknownVolumesAtEnd() {
        val state = LibraryState(books = listOf(parent.copy(volumeOrder = listOf("local/x", "local/two", "local/y", "local/one")),
            volume("two", "用户改名", "卷二.epub"), volume("one", "zh.卷一.txt"), volume("x", "外传"), volume("y", "笔记")),
            positions = mapOf("local/two" to Position("chapter", index = 8)))
        val asc = state.withWenkuSiteOrder(parent.book.ref.key, listOf("jp:卷一.epub", "jp:卷二.epub"))
        assertEquals(listOf("local/one", "local/two", "local/x", "local/y"), keys(asc))
        val desc = asc.withWenkuSiteOrder(parent.book.ref.key, listOf("jp:卷一.epub", "jp:卷二.epub"), true)
        assertEquals(listOf("local/two", "local/one", "local/x", "local/y"), keys(desc))
        assertEquals(state.positions, desc.positions)
        assertTrue(desc.books.first().siteVolumeOrderDescending)
        assertEquals(desc, appJson.decodeFromString<LibraryState>(appJson.encodeToString(desc)))
    }

    @Test fun newlyMountedVolumeIsInsertedIntoSelectedSiteDirection() {
        val initial = LibraryState(books = listOf(parent, volume("one", "1", "1.epub"), volume("three", "3", "3.epub")))
            .withWenkuSiteOrder(parent.book.ref.key, listOf("jp:1.epub", "jp:2.epub", "jp:3.epub"), true)
        val next = initial.copy(books = initial.books + volume("two", "2", "2.epub"))
            .withWenkuSiteOrder(parent.book.ref.key, initial.books.first().book.volumeIds, initial.books.first().siteVolumeOrderDescending)
        assertEquals(listOf("local/three", "local/two", "local/one"), keys(next))
    }

    @Test fun oldSettingsKeepDestructiveCleanupDisabledAndNewSettingsRoundTrip() {
        val old = appJson.decodeFromString<LibraryState>("{}")
        assertFalse(old.deleteDownloadAfterImport)
        assertFalse(old.deleteLocalCopyOnShelfRemoval)
        val backup = appJson.decodeFromString<SettingsBackup>("""{"version":1}""")
        assertFalse(backup.deleteDownloadAfterImport)
        assertFalse(backup.deleteLocalCopyOnShelfRemoval)
        val enabled = backup.copy(deleteDownloadAfterImport = true, deleteLocalCopyOnShelfRemoval = true)
        assertEquals(enabled, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(enabled)))
    }
}

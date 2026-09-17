package cc.novelia.app

import cc.novelia.app.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class WenkuVolumesTest {
    private val parent = SavedBook(BookCard(BookRef("wenku", "series"), "青空文库"), folder = "长篇", addedAt = 1)
    private val other = SavedBook(BookCard(BookRef("wenku", "other"), "远山文库"), addedAt = 2)
    private fun volume(id: String, title: String = "第$id 卷") = SavedBook(BookCard(BookRef("local", id), title), addedAt = 3)
    private fun library() = LibraryState(books = listOf(parent, other, volume("1"), volume("2"), volume("10")), folders = listOf("默认收藏", "长篇"))

    @Test fun mountingReassigningAndDetachingPreserveReadingData() {
        val position = Position("chapter", index = 3, textOffset = 42)
        val original = library().copy(positions = mapOf("local/1" to position))
        val mounted = original.withWenkuVolumes(parent.book.ref.key, setOf("local/1", "local/2"))
        assertTrue(mounted.books.first { it.book.ref == parent.book.ref }.volumesExpanded)
        assertEquals(parent.book.ref.key, mounted.books.first { it.book.ref.key == "local/1" }.parentWenkuKey)
        val reassigned = mounted.withVolumeParent("local/1", other.book.ref.key)
        assertEquals(other.book.ref.key, reassigned.books.first { it.book.ref.key == "local/1" }.parentWenkuKey)
        assertEquals(listOf("local/2"), reassigned.shelfGroups(false, "全部", "青空", 0).single().volumes.map { it.book.ref.key })
        val detached = reassigned.withVolumeParent("local/1", null)
        assertNull(detached.books.first { it.book.ref.key == "local/1" }.parentWenkuKey)
        assertEquals(original.positions, detached.positions)
        assertEquals(original.books.size, detached.books.size)
    }

    @Test fun managerSelectionReplacesOnlyTheChosenParentsChildren() {
        val original = library().withVolumeParent("local/10", other.book.ref.key).withWenkuVolumes(parent.book.ref.key, setOf("local/1", "local/2"))
        val updated = original.withWenkuVolumes(parent.book.ref.key, setOf("local/2"))
        assertNull(updated.books.first { it.book.ref.key == "local/1" }.parentWenkuKey)
        assertEquals(other.book.ref.key, updated.books.first { it.book.ref.key == "local/10" }.parentWenkuKey)
    }

    @Test fun invalidMountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { library().withWenkuVolumes("local/1", setOf("local/2")) }
        assertThrows(IllegalArgumentException::class.java) { library().withWenkuVolumes(parent.book.ref.key, setOf(other.book.ref.key)) }
        assertThrows(IllegalArgumentException::class.java) { library().withVolumeParent("local/missing", parent.book.ref.key) }
        assertThrows(IllegalArgumentException::class.java) { library().withVolumeParent("local/1", "wenku/missing") }
    }

    @Test fun groupsKeepLocalCopiesAccessibleAndNaturallyOrdered() {
        val mounted = library().withWenkuVolumes(parent.book.ref.key, setOf("local/10", "local/2", "local/1"))
        val groups = mounted.shelfGroups(false, "全部", "", 2)
        assertEquals(2, groups.size)
        assertEquals(listOf("local/1", "local/2", "local/10"), groups.first { it.saved.book.ref == parent.book.ref }.volumes.map { it.book.ref.key })
        assertEquals(3, mounted.shelfGroups(true, "全部", "", 0).size)
        assertEquals(1, mounted.shelfGroups(false, "长篇", "", 0).size)
        assertEquals(3, mounted.shelfGroups(true, "长篇", "", 0).size)
        val match = mounted.shelfGroups(false, "长篇", "第2 ", 0).single()
        assertEquals(parent.book.ref, match.saved.book.ref)
        assertEquals("local/2", match.volumes.single().book.ref.key)
    }

    @Test fun deletingParentReleasesVolumesAndDoesNotEraseProgress() {
        val mounted = library().withVolumeParent("local/1", parent.book.ref.key).copy(positions = mapOf("local/1" to Position("chapter")))
        val removed = mounted.withoutBook(parent.book.ref)
        assertEquals(4, removed.books.size)
        assertNull(removed.books.first { it.book.ref.key == "local/1" }.parentWenkuKey)
        assertEquals("长篇", removed.books.first { it.book.ref.key == "local/1" }.folder)
        assertEquals(mounted.positions, removed.positions)
        val dangling = library().copy(books = listOf(volume("1").copy(parentWenkuKey = "wenku/missing")))
        assertEquals("local/1", dangling.shelfGroups(false, "全部", "", 0).single().saved.book.ref.key)
    }

    @Test fun childReadingUpdatesGroupOrderAndFolderMovesKeepAssociationsConsistent() {
        val mounted = library().withVolumeParent("local/1", parent.book.ref.key).copy(positions = mapOf("local/1" to Position("chapter", updatedAt = 100)))
        assertEquals(parent.book.ref, mounted.shelfGroups(false, "全部", "", 0).first().saved.book.ref)
        val whole = mounted.moveShelfBooks(setOf(parent.book.ref.key), "新收藏夹")
        assertEquals(1, whole.shelfGroups(false, "新收藏夹", "", 0).single().volumes.size)
        val detached = whole.moveShelfBooks(setOf("local/1"), "独立阅读")
        assertNull(detached.books.first { it.book.ref.key == "local/1" }.parentWenkuKey)
        assertEquals("local/1", detached.shelfGroups(false, "独立阅读", "", 0).single().saved.book.ref.key)
    }

    @Test fun oldLibraryAndDownloadRecordsRemainCompatibleAndNewRelationsRoundTrip() {
        val old = appJson.decodeFromString<SavedBook>("""{"book":{"ref":{"provider":"local","id":"1"},"title":"第一卷"}}""")
        assertNull(old.parentWenkuKey)
        assertFalse(old.volumesExpanded)
        assertTrue(old.volumeOrder.isEmpty())
        val oldDownload = appJson.decodeFromString<DownloadEntry>("""{"id":"d","title":"卷","fileName":"a.epub","url":"https://example.com/a.epub"}""")
        assertNull(oldDownload.sourceBook)
        val state = library().withVolumeParent("local/1", parent.book.ref.key).copy(downloads = listOf(oldDownload.copy(sourceBook = parent.book.ref)))
        assertEquals(state, appJson.decodeFromString<LibraryState>(appJson.encodeToString(state)))
    }

    @Test fun manualOrderSurvivesSerializationSearchAndShelfSorting() {
        val ordered = library().withWenkuVolumes(parent.book.ref.key, setOf("local/1", "local/2", "local/10"))
            .withWenkuVolumeOrder(parent.book.ref.key, listOf("local/10", "local/1", "local/2"))
            .copy(positions = mapOf("local/2" to Position("chapter", updatedAt = 100)))
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(ordered))
        for(sort in 0..2) {
            assertEquals(listOf("local/10", "local/1", "local/2"), restored.shelfGroups(false, "全部", "", sort)
                .first { it.saved.book.ref == parent.book.ref }.volumes.map { it.book.ref.key })
        }
        assertEquals(listOf("local/10", "local/1"), restored.shelfGroups(false, "长篇", "第1", 0).single().volumes.map { it.book.ref.key })
        assertEquals(ordered.positions, restored.positions)
        assertEquals(emptyList<String>(), restored.books.first { it.book.ref == other.book.ref }.volumeOrder)
    }

    @Test fun newMountsAppendAndRemovedOrReassignedVolumesLeaveNoStaleOrder() {
        val ordered = library().withWenkuVolumes(parent.book.ref.key, setOf("local/1", "local/2"))
            .withWenkuVolumeOrder(parent.book.ref.key, listOf("local/2", "local/1"))
        val added = ordered.withVolumeParent("local/10", parent.book.ref.key)
        assertEquals(listOf("local/2", "local/1", "local/10"), added.shelfGroups(false, "长篇", "", 0).single().volumes.map { it.book.ref.key })
        assertEquals(added, added.withWenkuVolumes(parent.book.ref.key, setOf("local/10", "local/1", "local/2")))
        for(removed in listOf(
            added.withVolumeParent("local/2", other.book.ref.key),
            added.withVolumeParent("local/2", null),
            added.withoutBook(BookRef("local", "2")),
            added.moveShelfBooks(setOf("local/2"), "独立阅读"),
            added.withWenkuVolumes(parent.book.ref.key, setOf("local/1", "local/10"))
        )) {
            assertEquals(listOf("local/1"), removed.books.first { it.book.ref == parent.book.ref }.volumeOrder)
            assertEquals(listOf("local/1", "local/10"), removed.shelfGroups(false, "全部", "青空", 0).single().volumes.map { it.book.ref.key })
        }
    }

    @Test fun invalidOrdersCannotDropDuplicateOrStealVolumes() {
        val mounted = library().withWenkuVolumes(parent.book.ref.key, setOf("local/1", "local/2"))
            .withVolumeParent("local/10", other.book.ref.key)
        for(keys in listOf(emptyList(), listOf("local/1"), listOf("local/1", "local/1"), listOf("local/1", "local/10"), listOf("local/1", "local/missing"))) {
            assertThrows(IllegalArgumentException::class.java) { mounted.withWenkuVolumeOrder(parent.book.ref.key, keys) }
        }
        assertThrows(IllegalArgumentException::class.java) { mounted.withWenkuVolumeOrder("wenku/missing", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { mounted.withWenkuVolumeOrder("local/1", emptyList()) }
    }
}

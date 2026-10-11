package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.updates.withBookUpdate
import cc.novelia.app.data.updates.withSavedBook
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class WenkuMetadataTest {
    private val ref = BookRef("wenku", "three-volumes")
    private val detail = WenkuDetail(
        title = "三卷作品",
        volumes = (1..3).map { WenkuVolume(asin = "volume-$it", title = "第 $it 卷") },
        volumeJp = (1..3).map { JapaneseVolume(volumeId = "第 $it 卷.epub") },
        volumeZh = (1..3).map { "第 $it 卷.txt" },
    )

    @Test fun bilingualFilesDoNotDoubleThePublishedVolumeCount() {
        val book = detail.card(ref)
        assertEquals(3, book.publishedVolumeCount)
        assertEquals(6, book.total)
        assertEquals(6, book.volumeIds.size)
        assertTrue(book.volumeIds.containsAll(listOf("jp:第 1 卷.epub", "zh:第 1 卷.txt")))
    }

    @Test fun publicationCountDoesNotDependOnHowManyFilesHaveBeenUploaded() {
        assertEquals(3, detail.copy(volumeZh = emptyList(), volumeJp = emptyList()).card(ref).publishedVolumeCount)
        assertEquals(3, detail.copy(volumeZh = detail.volumeZh + "第 1 卷修订版.txt").card(ref).publishedVolumeCount)
        assertEquals(0, detail.copy(volumes = emptyList()).card(ref).publishedVolumeCount)
    }

    @Test fun legacyFavoriteGainsTheCountOnRefreshWithoutInventingAnUpdate() {
        val legacy = appJson.decodeFromString<BookCard>("""{
            "ref":{"provider":"wenku","id":"three-volumes"},"title":"三卷作品","total":6,
            "volumeIds":["jp:第 1 卷.epub","jp:第 2 卷.epub","jp:第 3 卷.epub","zh:第 1 卷.txt","zh:第 2 卷.txt","zh:第 3 卷.txt"]
        }""")
        assertNull(legacy.publishedVolumeCount)
        val refreshed = LibraryState().withSavedBook(legacy).withBookUpdate(detail.card(ref), 10)
        assertEquals(3, refreshed.books.single().book.publishedVolumeCount)
        assertFalse(refreshed.books.single().hasUpdates)
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(refreshed))
        assertEquals(3, restored.books.single().book.publishedVolumeCount)
    }

    @Test fun partialFavoriteResponsesAndFolderMovesPreserveTheKnownCount() {
        val outline = WenkuOutline(id = ref.id, title = detail.title).card()
        val saved = LibraryState().withSavedBook(detail.card(ref))
        val moved = saved.withSavedBook(outline, "追更")
        assertEquals(3, moved.books.single().book.publishedVolumeCount)
        assertEquals("追更", moved.books.single().folder)
        assertNull(outline.withKnownUpdateTime(detail.card(BookRef("wenku", "another"))).publishedVolumeCount)
        assertEquals(0, detail.copy(volumes = emptyList()).card(ref).withKnownUpdateTime(detail.card(ref)).publishedVolumeCount)
    }

    @Test fun newUploadedFilesStillProduceFileUpdatesWhenThePublicationCountIsUnchanged() {
        val original = detail.copy(volumeZh = detail.volumeZh.take(2)).card(ref)
        val updated = LibraryState().withSavedBook(original).withBookUpdate(detail.card(ref), 10)
        assertEquals(3, updated.books.single().book.publishedVolumeCount)
        assertEquals(1, updated.bookUpdates.getValue(ref.key).newVolumes)
        assertEquals("新增 1 个分卷文件", updated.bookUpdates.getValue(ref.key).summary)
    }
}

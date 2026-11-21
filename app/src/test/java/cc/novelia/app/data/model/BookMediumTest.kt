package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import org.junit.Assert.*
import org.junit.Test

class BookMediumTest {
    @Test fun identifiesArchiveAndTextFormatsWithoutLoadingChapters() {
        val doc = LocalDocument("id", "同名作品", "txt", emptyList())
        for (format in comicArchiveFormats) assertEquals(BookMedium.Comic, doc.copy(format = format).bookMedium { error("不需要正文") })
        assertEquals(BookMedium.Novel, doc.bookMedium { error("不需要正文") })
        val local = BookCard(BookRef("local", "id"), "漫画.cbz")
        assertNull(local.knownShelfMedium)
        assertEquals(BookMedium.Comic, local.copy(subtitle = "CBZ · 1 章").knownShelfMedium)
        assertEquals(BookMedium.Novel, local.copy(subtitle = "TXT · 1 章").knownShelfMedium)
        assertEquals(BookMedium.Novel, local.copy(ref = BookRef("wenku", "id")).knownShelfMedium)
    }

    @Test fun oldEpubChecksTheWholeBookAndNewEpubUsesItsImportMetadata() {
        val page = "novelia-image:" + "a".repeat(64)
        val entries = listOf(LocalChapter("cover", "封面", emptyList()), LocalChapter("body", "正文", emptyList()))
        val doc = LocalDocument("id", "作品", "epub", entries)
        assertEquals(BookMedium.Novel, doc.bookMedium { id -> LocalChapter(id, "", listOf(if (id == "cover") page else "文字")) })
        assertEquals(BookMedium.Comic, doc.bookMedium { id -> LocalChapter(id, "", listOf(page)) })
        assertEquals(BookMedium.Comic, doc.copy(imageOnlyEpub = true).bookMedium { error("不需要正文") })
        assertEquals(BookMedium.Novel, doc.copy(imageOnlyEpub = false).bookMedium { error("不需要正文") })
    }

    @Test fun legacyLocationStartsOnNovelShelfAndDisablingRestoreResetsTheShelf() {
        assertEquals(BookMedium.Novel, appJson.decodeFromString<ShelfLocation>("{}").medium)
        val state = LibraryState(lastShelfLocation = ShelfLocation(medium = BookMedium.Comic, comicFolderId = "gone"))
        assertEquals(BookMedium.Comic, state.restoredShelfLocation().medium)
        assertNull(state.restoredShelfLocation().comicFolderId)
        assertEquals(BookMedium.Novel, state.copy(rememberLastShelfFolder = false).restoredShelfLocation().medium)
    }
}

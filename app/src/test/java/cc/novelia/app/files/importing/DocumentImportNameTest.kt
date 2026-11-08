package cc.novelia.app.files.importing

import org.junit.Assert.*
import org.junit.Test

class DocumentImportNameTest {
    @Test fun fileManagerGenericTypeKeepsTheRealExtension() {
        assertEquals("星川.EPUB", documentImportName("星川.EPUB", "123", "application/octet-stream"))
        assertEquals("小说.txt", documentImportName(null, "primary:Books/小说.txt", null))
        assertEquals("字幕.srt", documentImportName("字幕.srt", null, "text/plain"))
    }

    @Test fun missingExtensionUsesOnlyAKnownBookType() {
        assertEquals("漫画.zip", documentImportName("漫画", null, "application/zip"))
        assertEquals("漫画.cbr", documentImportName("漫画", null, "application/vnd.comicbook-rar"))
        assertEquals("漫画.7z", documentImportName("漫画", null, "application/x-7z-compressed"))
        assertEquals("漫画.CBT", documentImportName("漫画.CBT", null, "application/octet-stream"))
        assertEquals("小说.epub", documentImportName("小说", "123", "application/epub+zip"))
        assertEquals("123.srt", documentImportName(null, "123", "application/x-subrip"))
        assertEquals("导入文档.txt", documentImportName("", null, "text/plain; charset=utf-8"))
        assertThrows(IllegalStateException::class.java) { documentImportName("123", null, "application/octet-stream") }
        assertThrows(IllegalStateException::class.java) { documentImportName(null, null, null) }
    }

    @Test fun misleadingMimeCannotTurnUnsupportedFilesIntoBooks() {
        assertThrows(IllegalArgumentException::class.java) { documentImportName("图片.png", null, "text/plain") }
        assertThrows(IllegalArgumentException::class.java) { documentImportName("文档.pdf", "123.txt", "application/epub+zip") }
    }
}

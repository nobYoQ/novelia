package cc.novelia.app

import cc.novelia.app.files.localBookExportFileName
import org.junit.Assert.assertEquals
import org.junit.Test

class BookExportFileNameTest {
    @Test fun existingExtensionsAreNotAppendedAgain() {
        for (format in listOf("epub", "txt", "srt")) {
            for (name in listOf("第一卷", "第一卷.$format", "第一卷.${format.uppercase()}", "第一卷.$format.$format")) {
                assertEquals("第一卷.$format", localBookExportFileName(name, format, format))
            }
        }
    }

    @Test fun missingOriginalUsesOneTxtExtensionAndKeepsDotsWithinTheTitle() {
        assertEquals("第一卷.txt", localBookExportFileName("第一卷.epub.epub", "epub", "txt"))
        assertEquals("第1.5卷.txt", localBookExportFileName("第1.5卷.EPUB", "epub", "txt"))
        assertEquals("故事.epub的续篇.epub", localBookExportFileName("故事.epub的续篇", "epub", "epub"))
    }

    @Test fun sanitizationAndLengthLimitApplyToTheStemBeforeTheFinalExtension() {
        assertEquals("卷_一_二.epub", localBookExportFileName(" 卷:一/二.epub ", "epub", "epub"))
        assertEquals("小说.epub", localBookExportFileName(".epub", "epub", "epub"))
        assertEquals("卷".repeat(120) + ".epub", localBookExportFileName("卷".repeat(130) + ".epub", "epub", "epub"))
    }
}

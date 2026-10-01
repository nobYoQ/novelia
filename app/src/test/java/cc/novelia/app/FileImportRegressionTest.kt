package cc.novelia.app

import cc.novelia.app.files.DocumentTools
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileImportRegressionTest {
    @get:Rule val folder = TemporaryFolder()

    private fun epub(extra: Map<String, ByteArray> = emptyMap()): File {
        val file = folder.newFile("book-${System.nanoTime()}.epub")
        val entries = linkedMapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>".toByteArray(),
            "OPS/book.opf" to "<package><manifest><item id='c' href='c.xhtml'/><item id='cover' href='image.png' properties='cover-image'/></manifest><spine><itemref idref='c'/></spine></package>".toByteArray(),
            "OPS/c.xhtml" to "<html><body><p>插图前</p><img src='image.png'/><p>插图后</p></body></html>".toByteArray(),
            "OPS/image.png" to "image-content".toByteArray(),
        ) + extra
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return file
    }

    @Test fun fileImportsStreamImagesAndKeepIllustrationOrder() {
        val images = mutableMapOf<String, ByteArray>()
        val document = DocumentTools.parseFile("插图.epub", epub(), { hash, input -> images[hash] = input.readBytes() })
        assertTrue(document.images.isEmpty())
        assertEquals(1, images.size)
        assertEquals(images.keys.single(), document.coverImage)
        assertEquals(listOf("插图前", "novelia-image:${document.coverImage}", "插图后"), document.chapters.single().paragraphs)
        assertArrayEquals("image-content".toByteArray(), images.values.single())
    }

    @Test fun oversizedFileIsRejectedBeforeReadOrImageAllocation() {
        val file = folder.newFile("large.epub")
        RandomAccessFile(file, "rw").use { it.setLength(DocumentTools.MAX_INPUT.toLong() + 1) }
        val error = assertThrows(IllegalArgumentException::class.java) {
            DocumentTools.parseFile("large.epub", file, { _, _ -> fail("Must not start parsing") })
        }
        assertTrue(error.message!!.contains("64 MB"))
        DocumentTools.requireImportSize(DocumentTools.MAX_INPUT.toLong())
    }

    @Test fun unsafeArchivesAndOversizedXhtmlAreRejectedByFilePath() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentTools.parseFile("bad.epub", epub(mapOf("../../escape" to byteArrayOf(1))), { _, _ -> })
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentTools.parseFile("large.epub", epub(mapOf("OPS/c.xhtml" to ByteArray(8 * 1024 * 1024 + 1))), { _, _ -> })
        }
    }

    @Test fun largeOcrBlockKeepsLinearOutputAndRespondsToCancellation() {
        val input = "一行\n".repeat(100_000)
        assertEquals("一行".repeat(100_000), DocumentTools.repairOcr(input))
        var checked = 0
        assertThrows(CancellationException::class.java) {
            DocumentTools.repairOcr(input) { if (++checked == 20) throw CancellationException() }
        }
        assertEquals(20, checked)
    }

    @Test fun understatedZipEntrySizeCannotBypassExpansionValidation() {
        val file = epub()
        val bytes = file.readBytes()
        val directory = bytes.indices.first { offset -> offset + 28 < bytes.size &&
            bytes[offset] == 0x50.toByte() && bytes[offset + 1] == 0x4b.toByte() &&
            bytes[offset + 2] == 0x01.toByte() && bytes[offset + 3] == 0x02.toByte() }
        // 只修改中央目录首个条目声明的解压大小，不改变其实际数据流。
        bytes[directory + 24] = 1
        for (offset in 25..27) bytes[directory + offset] = 0
        file.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) {
            DocumentTools.parseFile("invalid.epub", file, { _, _ -> })
        }
    }

    @Test fun textAndSubtitleParsingCheckCancellationBetweenRecords() {
        for (format in listOf("txt", "srt")) {
            var checks = 0
            assertThrows(CancellationException::class.java) {
                DocumentTools.parseText("正文一行\n\n".repeat(1000), format) { if (++checks == 10) throw CancellationException() }
            }
            assertEquals(10, checks)
        }
    }
}

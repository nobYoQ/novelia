package cc.novelia.app

import cc.novelia.app.files.PendingExportFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CancellationException

class PendingExportFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun restoredExporterNeedsOnlyTheSavedIdToWriteAllBytes() {
        val directory = folder.newFolder()
        val payload = ByteArray(200_003) { (it % 256).toByte() }
        val id = PendingExportFiles(directory).create(payload)
        val restored = PendingExportFiles(directory)
        val exported = ByteArrayOutputStream()
        restored.finish(id, { exported })
        assertArrayEquals(payload, exported.toByteArray())
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun cancelledPickerReleasesItsStagedPayload() {
        val directory = folder.newFolder()
        val files = PendingExportFiles(directory)
        files.finish(files.create("取消导出".toByteArray()), null)
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun failedDestinationClosesStreamAndCleansPayload() {
        val directory = folder.newFolder()
        val files = PendingExportFiles(directory)
        var closed = false
        val failing = object : OutputStream() {
            override fun write(value: Int) { throw IOException("no space") }
            override fun close() { closed = true }
        }
        val id = files.create("导出内容".toByteArray())
        assertThrows(IOException::class.java) { files.finish(id, { failing }) }
        assertTrue(closed)
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun missingStagedPayloadReportsAnErrorInsteadOfExportingAnEmptyFile() {
        val directory = folder.newFolder()
        val error = assertThrows(IllegalArgumentException::class.java) {
            PendingExportFiles(directory).finish(UUID.randomUUID().toString(), { fail("Must not open the destination"); null })
        }
        assertTrue(error.message!!.contains("已不存在"))
    }

    @Test fun cancelledPreparationRemovesPartiallyWrittenPayload() {
        val directory = folder.newFolder()
        var chunks = 0
        assertThrows(CancellationException::class.java) {
            PendingExportFiles(directory).create(ByteArray(150_000)) { if(++chunks == 2) throw CancellationException() }
        }
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun invalidRestoredIdCannotReadOrRemoveAnUnrelatedFile() {
        val directory = folder.newFolder()
        val unrelated = folder.newFile("unrelated.bin").apply { writeText("保留") }
        assertThrows(IllegalArgumentException::class.java) {
            PendingExportFiles(directory).finish("../unrelated.bin", null)
        }
        assertEquals("保留", unrelated.readText())
    }
}

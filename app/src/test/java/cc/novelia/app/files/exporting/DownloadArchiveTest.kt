package cc.novelia.app.files.exporting

import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun entry(id: String, name: String, status: String = "已完成") = DownloadEntry(id, name, "$id-$name", "https://example.invalid", status = status)

    @Test fun archivesPreserveOriginalBytesChineseNamesAndDuplicateFiles() = runBlocking {
        val downloads = temporary.newFolder()
        val exports = temporary.newFolder()
        val book = BookCard(BookRef("wenku", "series"), "星之旅", authors = listOf("作者甲"), artists = listOf("画师乙"))
        val other = BookCard(BookRef("syosetu", "other"), "另一作品", authors = listOf("作者丙"))
        val entries = listOf(
            entry("one", "第一卷.txt").copy(sourceBook = book.ref, sourceCard = book),
            entry("two", "第一卷.txt").copy(sourceBook = book.ref, sourceCard = book),
            entry("three", "作品.epub").copy(sourceBook = other.ref, sourceCard = other),
        )
        val contents = listOf(byteArrayOf(-1, -2, 0, 65), "另一个同名文件".toByteArray(), ByteArray(150_000) { (it % 256).toByte() })
        entries.zip(contents).forEach { (entry, bytes) -> File(downloads, entry.fileName).writeBytes(bytes) }
        val archive = BookExportFiles(exports)
        val id = archive.create(downloads, entries)
        val target = temporary.newFile("结果.zip")
        BookExportFiles(exports).finish(id, { target.outputStream() })
        ZipFile(target, Charsets.UTF_8).use { zip ->
            val actual = zip.entries().toList()
            assertEquals(listOf("[作者甲×画师乙] 星之旅/第一卷.txt", "[作者甲×画师乙] 星之旅/第一卷（2）.txt", "[作者丙] 另一作品/作品.epub"), actual.map { it.name })
            actual.zip(contents).forEach { (entry, bytes) -> assertArrayEquals(bytes, zip.getInputStream(entry).readBytes()) }
        }
        assertEquals(0, exports.listFiles()!!.size)
        val shared = archive.share(archive.create(downloads, entries))
        ZipFile(shared, Charsets.UTF_8).use { zip ->
            ZipFile(target, Charsets.UTF_8).use { exported ->
                assertEquals(exported.entries().toList().map { it.name }, zip.entries().toList().map { it.name })
                zip.entries().toList().zip(contents).forEach { (entry, bytes) ->
                    assertArrayEquals(bytes, zip.getInputStream(entry).readBytes())
                }
            }
        }
        assertEquals(entries.map { it.fileName }.toSet(), downloads.listFiles()!!.map { it.name }.toSet())
        entries.zip(contents).forEach { (entry, bytes) -> assertArrayEquals(bytes, File(downloads, entry.fileName).readBytes()) }
    }

    @Test fun missingFilesOrChangedTasksDiscardTheEntireStagedArchive() = runBlocking {
        val downloads = temporary.newFolder()
        val exports = temporary.newFolder()
        val first = entry("one", "第一卷.txt")
        File(downloads, first.fileName).writeText("正文")
        val archives = BookExportFiles(exports)
        assertTrue(runCatching { archives.create(downloads, listOf(first, entry("missing", "第二卷.txt"))) }.isFailure)
        assertTrue(runCatching { archives.create(downloads, listOf(first)) { false } }.isFailure)
        assertEquals(0, exports.listFiles()!!.size)
    }

    @Test fun incompleteTasksDuplicateIdsAndEscapingPathsCannotBePacked() = runBlocking {
        val downloads = temporary.newFolder()
        val exports = temporary.newFolder()
        val archives = BookExportFiles(exports)
        val task = entry("one", "作品.txt")
        File(downloads, task.fileName).writeText("正文")
        val outside = temporary.newFile("outside.txt").apply { writeText("保留内容") }
        for(entries in listOf(emptyList(), listOf(task.copy(status = "下载中")), listOf(task, task), listOf(task.copy(fileName = "../outside.txt")))) {
            assertTrue(runCatching { archives.create(downloads, entries) }.isFailure)
            assertEquals(0, exports.listFiles()!!.size)
        }
        assertEquals("保留内容", outside.readText())
    }

    @Test fun preparationCancellationCleansUpPartialArchive() = runBlocking {
        val downloads = temporary.newFolder()
        val exports = temporary.newFolder()
        val task = entry("one", "作品.txt")
        File(downloads, task.fileName).writeBytes(ByteArray(150_000))
        var checks = 0
        val result = runCatching { BookExportFiles(exports).create(downloads, listOf(task)) {
            if(++checks == 2) throw CancellationException("取消")
            true
        } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(0, exports.listFiles()!!.size)
    }

    @Test fun cancellingPickerOrFailingDestinationReleasesStagedFiles() = runBlocking {
        val downloads = temporary.newFolder()
        val exports = temporary.newFolder()
        val task = entry("one", "作品.txt")
        File(downloads, task.fileName).writeText("正文")
        val archives = BookExportFiles(exports)
        archives.finish(archives.create(downloads, listOf(task)), null)
        val id = archives.create(downloads, listOf(task))
        val failing = object : OutputStream() { override fun write(value: Int) { throw IOException("无法写入") } }
        assertThrows(IOException::class.java) { archives.finish(id, { failing }) }
        assertEquals(0, exports.listFiles()!!.size)
    }

    @Test fun sharingRetainsCurrentArchiveAndCleansOnlyOldSharedArchives() = runBlocking {
        val downloads = temporary.newFolder()
        val exports = temporary.newFolder()
        val task = entry("one", "作品.txt")
        File(downloads, task.fileName).writeText("正文")
        val archives = BookExportFiles(exports)
        val old = archives.share(archives.create(downloads, listOf(task)))
        assertTrue(old.setLastModified(System.currentTimeMillis() - 48 * 60 * 60 * 1000L))
        val pendingId = archives.create(downloads, listOf(task))
        val unrelated = File(exports, "其他文件.zip").apply { writeText("保留") }
        val shared = archives.share(archives.create(downloads, listOf(task)))
        assertFalse(old.exists())
        assertTrue(shared.isFile)
        assertEquals("保留", unrelated.readText())
        val output = ByteArrayOutputStream()
        archives.finish(pendingId, { output })
        assertTrue(output.size() > 0)
    }

    @Test fun invalidSavedIdsCannotAccessOtherFiles() {
        val exports = temporary.newFolder()
        val unrelated = temporary.newFile("unrelated.zip").apply { writeText("保留") }
        val archives = BookExportFiles(exports)
        assertThrows(IllegalArgumentException::class.java) { archives.finish("../unrelated.zip", null) }
        assertThrows(IllegalArgumentException::class.java) { archives.share("../unrelated.zip") }
        assertThrows(IllegalArgumentException::class.java) { archives.finish(UUID.randomUUID().toString(), { ByteArrayOutputStream() }) }
        assertEquals("保留", unrelated.readText())
    }
}

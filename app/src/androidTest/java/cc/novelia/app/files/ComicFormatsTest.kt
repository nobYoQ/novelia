package cc.novelia.app.files

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.files.comic.packComicFolder
import java.io.File
import android.content.ContextWrapper
import android.graphics.Bitmap
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.data.backup.LibraryBackupService
import cc.novelia.app.data.catalog.KeywordStore
import cc.novelia.app.files.comic.*
import cc.novelia.app.files.importing.importLocalDocument
import cc.novelia.app.ui.reader.comic.loadComicVolume
import java.io.ByteArrayOutputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ComicFormatsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun png(): ByteArray {
        val image = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.BLUE)
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it); image.recycle() }.toByteArray()
    }

    @Test fun largeStoredZipKeepsOnlyOriginalAndCoverThenLoadsSelectedPage() = runBlocking {
        val ctx = instrumentation.targetContext
        val root = Files.createTempDirectory(ctx.cacheDir.toPath(), "large-comic").toFile()
        val isolated = object : ContextWrapper(ctx) {
            override fun getFilesDir() = File(root, "library").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val store = LocalStore(isolated)
        try {
            val file = File(root, "large.cbz")
            val random = java.util.Random(42)
            val scan = Bitmap.createBitmap(2400, 3600, Bitmap.Config.ARGB_8888)
            val row = IntArray(scan.width)
            for (y in 0 until scan.height) {
                for (x in row.indices) row[x] = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                scan.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            val image = ByteArrayOutputStream().also { scan.compress(Bitmap.CompressFormat.JPEG, 96, it); scan.recycle() }.toByteArray()
            val count = maxOf(10, 100 * 1024 * 1024 / image.size + 1)
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                fun small(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                small("ComicInfo.xml", "<ComicInfo><Series>测试系列</Series><Number>3</Number><Writer>作者</Writer></ComicInfo>".toByteArray())
                small("cover.png", png())
                val sum = java.util.zip.CRC32().apply { update(image) }.value
                for (n in count downTo 1) {
                    val entry = ZipEntry("$n.jpg").apply { method = ZipEntry.STORED; size = image.size.toLong(); crc = sum }
                    zip.putNextEntry(entry); zip.write(image); zip.closeEntry()
                }
            }
            val start = android.os.SystemClock.elapsedRealtime()
            val ref = importLocalDocument(store, file).ref
            val imported = android.os.SystemClock.elapsedRealtime()
            val doc = store.documentIndex(ref.id)
            assertEquals("测试系列 · 第 3 卷", doc.name)
            assertEquals((1..count).map { "$it.jpg" }, doc.archivePages.map { it.path })
            assertEquals(listOf("作者"), store.state.value.books.single().book.authors)
            assertEquals(1, store.documentImage(ref.id, doc.coverImage!!).parentFile!!.list()!!.size)
            val volume = requireNotNull(store.loadComicVolume(ref.id))
            assertEquals(0, File(isolated.cacheDir, "comic-pages").list().orEmpty().size)
            val page = volume.pages.first().load!!()
            fun decode(target: File) = BitmapFactory.decodeFile(target.path, BitmapFactory.Options().apply { inSampleSize = 2 })!!.also {
                assertEquals(1200, it.width); assertEquals(1800, it.height); it.recycle()
            }
            decode(page)
            val shown = android.os.SystemClock.elapsedRealtime()
            assertEquals(1, File(isolated.cacheDir, "comic-pages").list()!!.size)
            val subsequent = volume.pages.drop(1).map { next ->
                val t = android.os.SystemClock.elapsedRealtime()
                decode(next.load!!())
                android.os.SystemClock.elapsedRealtime() - t
            }.sorted()
            android.util.Log.i("ComicFixture", "sizeMiB=" + file.length() / 1048576 + " pages=" + count + " importMs=" + (imported - start) +
                " firstDecodeMs=" + (shown - imported) + " medianNextDecodeMs=" + subsequent[subsequent.size / 2] +
                " peakPageCacheMiB=" + File(isolated.cacheDir, "comic-pages").listFiles().orEmpty().sumOf { it.length() } / 1048576)
            store.flush()
        } finally { root.deleteRecursively() }
    }

    @Test fun rar4Rar5SolidSevenZipAndTarReadRealPagesThroughBothEntryPoints() {
        for ((fixture, aliases) in listOf("stored.cbr" to listOf("rar", "cbr"), "stored-v5.rar" to listOf("rar"),
            "pages.cb7" to listOf("7z", "cb7"), "pages.cbt" to listOf("tar", "cbt"))) {
            val bytes = instrumentation.context.assets.open("comic/$fixture").use { it.readBytes() }
            val file = File.createTempFile("comic-format-", ".tmp", instrumentation.targetContext.cacheDir)
            try {
                file.writeBytes(bytes)
                for (alias in aliases) {
                    val images = mutableMapOf<String, Int>()
                    val parsed = DocumentTools.parseFile("书.$alias", file, { hash, stream ->
                        val bitmap = requireNotNull(BitmapFactory.decodeStream(stream))
                        images[hash] = Color.red(bitmap.getPixel(0, 0))
                        bitmap.recycle()
                    })
                    val hashes = parsed.chapters.single().paragraphs.map { it.removePrefix("novelia-image:") }
                    assertEquals(fixture, listOf(2, 10), hashes.map(images::getValue))
                    assertEquals(hashes.first(), parsed.coverImage)
                    assertEquals(parsed.chapters, DocumentTools.parse("书.$alias", bytes).chapters)
                    val stage = Files.createTempDirectory(file.parentFile!!.toPath(), "indexed-native").toFile()
                    try {
                        val indexed = indexComic(file, "书.$alias", "fixture", stage) {}
                        val colors = indexed.archivePages.map { page ->
                            val out = ByteArrayOutputStream()
                            extractComicPage(file, alias, page.path, out) {}
                            val buffer = out.toByteArray()
                            val bitmap = BitmapFactory.decodeByteArray(buffer, 0, buffer.size)
                            Color.red(bitmap.getPixel(0, 0)).also { bitmap.recycle() }
                        }
                        assertEquals(listOf(2, 10), colors)
                    } finally { stage.deleteRecursively() }
                }
            } finally { file.delete() }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun selectedTreePacksNestedPagesDeterministicallyAndIgnoresHiddenFiles() {
        val ctx = instrumentation.targetContext
        val root = Files.createTempDirectory(ctx.cacheDir.toPath(), "comic-tree").toFile()
        try {
            val volume = File(root, "作品").apply { mkdir() }
            File(volume, "第10话").apply { mkdir(); resolve("1.jpg").writeText("ten") }
            File(volume, "第2话").apply { mkdir(); resolve("1.jpg").writeText("two"); resolve(".hidden.png").writeText("skip") }
            File(volume, "说明.txt").writeText("skip")
            val treeAuthority = "test.comic.tree"
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): MatrixCursor {
                    val source = File(root, DocumentsContract.getDocumentId(uri))
                    val files = if (uri.lastPathSegment == "children") source.listFiles().orEmpty().toList() else listOf(source)
                    val columns = requireNotNull(projection)
                    return MatrixCursor(columns).apply {
                        for (file in files) addRow(columns.map { when (it) {
                            Document.COLUMN_DOCUMENT_ID -> file.relativeTo(root).path
                            Document.COLUMN_DISPLAY_NAME -> file.name
                            Document.COLUMN_MIME_TYPE -> if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
                            else -> null
                        } })
                    }
                }
                override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
                    ParcelFileDescriptor.open(File(root, DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.MODE_READ_ONLY)
                override fun getType(uri: Uri): String? = null
                override fun delete(uri: Uri, s: String?, a: Array<out String>?) = error("只读")
                override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = error("只读")
                override fun insert(uri: Uri, values: ContentValues?): Uri = error("只读")
            }
            provider.attachInfo(ctx, ProviderInfo().apply { authority = treeAuthority; exported = true })
            val tree = DocumentsContract.buildTreeDocumentUri(treeAuthority, volume.name)
            val cbz = File(root, "out.cbz")
            repeat(2) {
                assertEquals("作品", packComicFolder(ContentResolver.wrap(provider), tree, cbz) {})
                val doc = DocumentTools.parse("作品.cbz", cbz.readBytes())
                assertEquals(listOf("two", "ten").map { "novelia-image:" + DocumentTools.digest(it.byteInputStream()) },
                    doc.chapters.single().paragraphs)
                val old = File(root, "previous.cbz")
                if (old.exists()) assertArrayEquals(old.readBytes(), cbz.readBytes()) else cbz.copyTo(old)
            }
            val seriesDir = File(root, "连载").apply { mkdir() }
            val selected = DocumentsContract.buildTreeDocumentUri(treeAuthority, seriesDir.name)
            val isolated = object : ContextWrapper(ctx) {
                override fun getFilesDir() = File(root, "library").apply { mkdirs() }
                override fun getContentResolver() = ContentResolver.wrap(provider)
            }
            val store = LocalStore(isolated)
            fun chapter(number: Int) {
                ZipOutputStream(File(seriesDir, "第${number}话.cbz").outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("1.png")); zip.write(png()); zip.closeEntry()
                }
            }
            chapter(2); chapter(10)
            runBlocking {
                val ref = importComicSeries(store, selected).ref
                val initial = store.documentIndex(ref.id)
                assertEquals(listOf("第2话", "第10话"), initial.chapters.map { it.title })
                store.savePosition(ref, Position(initial.chapters.last().id, 1), "连载")
                chapter(1); chapter(11)
                assertEquals(ref, importComicSeries(store, selected, ref.id).ref)
                val refreshed = requireNotNull(store.loadComicVolume(ref.id))
                assertEquals(listOf("第1话", "第2话", "第10话", "第11话"), refreshed.sections.map { it.title })
                assertEquals(refreshed.sections[2].id, store.state.value.positions[ref.key]?.chapterId)
                assertEquals(1, store.state.value.books.size)
                assertFalse(importComicSeries(store, selected).imported)
                assertArrayEquals(png(), refreshed.pages[2].load!!().readBytes())
                val backup = ByteArrayOutputStream().also { LibraryBackupService(store, KeywordStore(isolated)).export(it, false) }
                val dest = object : ContextWrapper(isolated) { override fun getFilesDir() = File(root, "restore").apply { mkdirs() } }
                val restored = LocalStore(dest)
                val service = LibraryBackupService(restored, KeywordStore(dest))
                service.restore(service.prepare(backup.toByteArray().inputStream()).stagingId)
                val restoredRef = restored.state.value.books.single().book.ref
                val restoredVolume = requireNotNull(restored.loadComicVolume(restoredRef.id))
                assertEquals(refreshed.sections, restoredVolume.sections)
                assertArrayEquals(png(), restoredVolume.pages[3].load!!().readBytes())
                store.flush(); restored.flush()
            }
        } finally { root.deleteRecursively() }
    }
}

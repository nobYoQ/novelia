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
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ComicFormatsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

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
        } finally { root.deleteRecursively() }
    }
}

package cc.novelia.app.ui.components

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.files.DocumentTools
import cc.novelia.app.ui.downloads.DownloadsScreen
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.shelf.ShelfScreen
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BookExportUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: NoveliaApplication
    private lateinit var previous: LibraryState
    private lateinit var snackbar: SnackbarHostState
    private val files = mutableListOf<File>()
    private val documents = mutableListOf<String>()

    @Before fun prepare() = runBlocking {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        app.initialization.await()
        assertNull("Use the anonymous dedicated test emulator", app.session.profile.value)
        previous = app.store.state.value
    }

    @After fun cleanup() = runBlocking {
        app.store.update { previous }
        app.store.flush()
        documents.forEach(app.store::removeDocument)
        files.forEach { it.delete() }
    }

    @Test fun downloadedEpubKeepsItsFormatAndBytesAfterScreenRestoration() = downloadRoundTrip("epub", epubBytes())

    @Test fun downloadedTxtKeepsItsEncodingAndBytesAfterScreenRestoration() = downloadRoundTrip("txt", txtBytes())

    @Test fun batchWithOneCompletedFileExportsOriginalEvenWhenAnUnfinishedTaskIsSelected() {
        val payloads = batchDownloads(1)
        val picker = ExportPicker()
        val restoration = screen(picker) { DownloadsScreen(it) }
        selectBatch()
        compose.onNodeWithText("导出文件（1）").performClick()
        assertPicker(picker, payloads.keys.single(), "text/plain")
        restoration.emulateSavedInstanceStateRestore()
        val target = finishExport(picker, "txt", payloads.values.single().size)
        awaitMessage("文件已导出")
        assertArrayEquals(payloads.values.single(), target.readBytes())
    }

    @Test fun batchWithMultipleFilesExportsZipAfterRestorationAndKeepsOriginalBytes() {
        val payloads = batchDownloads(2)
        val picker = ExportPicker()
        val restoration = screen(picker) { DownloadsScreen(it) }
        selectBatch()
        compose.onNodeWithText("导出 ZIP（2）").performClick()
        assertPicker(picker, "下载文件（2）.zip", "application/zip")
        restoration.emulateSavedInstanceStateRestore()
        val target = finishExport(picker, "zip", 16_384)
        awaitMessage("压缩包已导出")
        ZipFile(target, Charsets.UTF_8).use { zip ->
            assertEquals(payloads.keys.toList(), zip.entries().toList().map { it.name })
            payloads.forEach { (name, bytes) -> assertArrayEquals(bytes, zip.getInputStream(zip.getEntry(name)).readBytes()) }
        }
    }

    @Test fun cancelledBatchZipExportReleasesStagingAndKeepsDownloads() {
        batchDownloads(2)
        val picker = ExportPicker()
        screen(picker) { DownloadsScreen(it) }
        selectBatch()
        compose.onNodeWithTag("download-batch-export").performClick()
        assertPicker(picker, "下载文件（2）.zip", "application/zip")
        val staged = app.store.exportsDir.listFiles().orEmpty().filter { it.name.startsWith("download-export-") }
        assertTrue(staged.isNotEmpty())
        compose.runOnIdle { picker.dispatchResult(picker.pendingCode!!, Activity.RESULT_CANCELED, null) }
        compose.waitUntil(5000) { staged.none(File::exists) }
        compose.onNodeWithTag("download-batch-export").assertIsEnabled()
        assertEquals(3, app.store.state.value.downloads.size)
    }

    @Test fun batchShareUsesOriginalForOneFileAndZipForMultipleFilesWithReadPermission() {
        val payloads = batchDownloads(2)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val captured = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if(intent.action != Intent.ACTION_CHOOSER) return null
                @Suppress("DEPRECATION") val send = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                captured += send
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            screen(ExportPicker()) { DownloadsScreen(it) }
            compose.onNodeWithText("批量操作").performClick()
            val first = app.store.state.value.downloads.first()
            compose.onNodeWithTag("download-select-${first.id}").performClick()
            compose.onNodeWithText("分享文件（1）").performClick()
            compose.waitUntil(5000) { captured.size == 1 }
            val single = captured[0]
            assertEquals("text/plain", single.type)
            @Suppress("DEPRECATION") val originalUri = single.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertArrayEquals(payloads.values.first(), app.contentResolver.openInputStream(originalUri)!!.use { it.readBytes() })
            assertTrue(single.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(originalUri, single.clipData!!.getItemAt(0).uri)

            compose.onNodeWithText("全选").performClick()
            compose.onNodeWithText("分享 ZIP（2）").performClick()
            compose.waitUntil(5000) { captured.size == 2 }
            val multiple = captured[1]
            assertEquals("application/zip", multiple.type)
            @Suppress("DEPRECATION") val archiveUri = multiple.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            files += File(app.store.exportsDir, archiveUri.lastPathSegment!!)
            val archived = linkedMapOf<String, ByteArray>()
            ZipInputStream(app.contentResolver.openInputStream(archiveUri)!!, Charsets.UTF_8).use { zip ->
                while(true) {
                    val entry = zip.nextEntry ?: break
                    archived[entry.name] = zip.readBytes()
                }
            }
            assertEquals(payloads.keys, archived.keys)
            payloads.forEach { (name, bytes) -> assertArrayEquals(bytes, archived.getValue(name)) }
            assertTrue(multiple.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(archiveUri, multiple.clipData!!.getItemAt(0).uri)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun localEpubKeepsItsOriginalArchiveAfterScreenRestoration() = localRoundTrip("epub", epubBytes())

    @Test fun localTxtKeepsItsOriginalEncodingAfterScreenRestoration() = localRoundTrip("txt", txtBytes())

    @Test fun localDownloadTitleWithExtensionExportsOneSuffixAndUnchangedBytes() =
        localRoundTrip("epub", epubBytes(), "导出测试.epub.epub")

    @Test fun missingEpubOriginalExportsCacheAsTxtAndKeepsThatChoiceAfterRestoration() {
        val id = UUID.randomUUID().toString()
        val doc = LocalDocument(id, "旧版书籍.epub", "epub", listOf(
            LocalChapter("one", "第一章", listOf("第一段正文。", "novelia-image:${"a".repeat(64)}")),
            LocalChapter("two", "第二章", listOf("第二段正文。")),
        ))
        saveLocal(doc)
        val picker = ExportPicker()
        val restoration = screen(picker) { ShelfScreen(it) }
        openLocalExport(doc.name)
        assertPicker(picker, "旧版书籍.txt", "text/plain")
        restoration.emulateSavedInstanceStateRestore()
        // 选择器显示的是 TXT；此时恢复原件也不能把 EPUB 字节写进 .txt。
        app.store.documentSource(id, "epub").writeBytes(epubBytes())
        val expected = "第一章\n\n第一段正文。\n\n第二章\n\n第二段正文。".toByteArray(Charsets.UTF_8)
        val target = finishExport(picker, "txt", expected.size)
        awaitMessage("正文已导出为 TXT")
        assertArrayEquals(expected, target.readBytes())
        assertEquals(listOf("第一章", "第二章"), parseExport(target).chapters.map { it.title })
    }

    @Test fun originalDisappearingWhilePickerIsOpenDoesNotWriteTextIntoEpub() {
        val doc = DocumentTools.parse("导出测试.epub", epubBytes())
        saveLocal(doc)
        val source = app.store.documentSource(doc.id, "epub").apply { writeBytes(epubBytes()) }
        val picker = ExportPicker()
        screen(picker) { ShelfScreen(it) }
        openLocalExport(doc.name)
        assertPicker(picker, "导出测试.epub", "application/epub+zip")
        assertTrue(source.delete())
        val target = finishExport(picker, "epub", 32)
        awaitMessage("原文件已不存在，请重新选择导出缓存正文为 TXT")
        assertArrayEquals(ByteArray(32 + 128) { 42 }, target.readBytes())
    }

    @Test fun documentContractAlsoDeclaresSubtitleAndToolFormats() {
        val contract = CreateBookDocument()
        listOf("字幕.srt" to "application/x-subrip", "词频.tsv" to "text/tab-separated-values", "书籍.EPUB" to "application/epub+zip").forEach { (name, mime) ->
            val intent = contract.createIntent(app, name)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals(name, intent.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals(mime, intent.type)
        }
    }

    private fun downloadRoundTrip(format: String, bytes: ByteArray) {
        val id = UUID.randomUUID().toString()
        val name = "导出测试.$format"
        val source = File(app.store.downloadsDir, "$id-$name").apply { writeBytes(bytes) }
        files += source
        app.store.update { it.copy(downloads = listOf(DownloadEntry(id, "导出测试", source.name, "https://example.invalid/export", status = "已完成", progress = 100))) }
        val picker = ExportPicker()
        val restoration = screen(picker) { DownloadsScreen(it) }
        compose.onNodeWithContentDescription("更多下载操作 导出测试").performClick()
        compose.onNodeWithText("导出文件").performClick()
        assertPicker(picker, name, if(format == "epub") "application/epub+zip" else "text/plain")
        restoration.emulateSavedInstanceStateRestore()
        val target = finishExport(picker, format, bytes.size)
        awaitMessage("文件已导出")
        assertArrayEquals(bytes, target.readBytes())
        assertTrue(parseExport(target).chapters.flatMap { it.paragraphs }.contains("导出验证正文。"))
    }

    private fun batchDownloads(count: Int): Map<String, ByteArray> {
        val payloads = linkedMapOf("第一卷.txt" to txtBytes()).apply { if(count > 1) put("第二卷.epub", epubBytes()) }
        val entries = payloads.map { (name, bytes) ->
            val id = UUID.randomUUID().toString()
            val file = File(app.store.downloadsDir, "$id-$name").apply { writeBytes(bytes) }
            files += file
            DownloadEntry(id, name, file.name, "https://example.invalid", status = "已完成")
        } + DownloadEntry(UUID.randomUUID().toString(), "尚未完成", "unfinished.txt", "https://example.invalid", status = "已暂停")
        app.store.update { it.copy(downloads = entries) }
        return payloads
    }

    private fun selectBatch() {
        compose.onNodeWithText("批量操作").performClick()
        compose.onNodeWithText("全选").performClick()
    }

    private fun localRoundTrip(format: String, bytes: ByteArray, name: String = "导出测试") {
        val doc = DocumentTools.parse("导出测试.$format", bytes).copy(name = name)
        saveLocal(doc)
        app.store.documentSource(doc.id, format).writeBytes(bytes)
        val picker = ExportPicker()
        val restoration = screen(picker) { ShelfScreen(it) }
        openLocalExport(doc.name)
        assertPicker(picker, "导出测试.$format", if(format == "epub") "application/epub+zip" else "text/plain")
        restoration.emulateSavedInstanceStateRestore()
        val target = finishExport(picker, format, bytes.size)
        awaitMessage("原文件已导出")
        assertArrayEquals(bytes, target.readBytes())
        assertTrue(parseExport(target).chapters.flatMap { it.paragraphs }.contains("导出验证正文。"))
    }

    private fun saveLocal(doc: LocalDocument) {
        documents += doc.id
        app.store.saveDocument(doc)
        app.store.update { it.copy(books = listOf(SavedBook(BookCard(BookRef("local", doc.id), doc.name)))) }
    }

    private fun openLocalExport(title: String) {
        compose.onNodeWithText("本地文件").performClick()
        compose.onNodeWithContentDescription("管理 $title").performClick()
        compose.onNodeWithText("导出原文件").performScrollTo().performClick()
    }

    private fun screen(picker: ExportPicker, content: @Composable (AppController) -> Unit): StateRestorationTester {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    AppInteractionMode(false, true) { NoveliaTheme("light") {
                        val nav = rememberNavController()
                        val scope = rememberCoroutineScope()
                        snackbar = remember { SnackbarHostState() }
                        val controller = remember(nav, scope, snackbar) { AppController(app, nav, scope, snackbar) }
                        content(controller)
                    } }
                }
            }
        }
    }

    private fun assertPicker(picker: ExportPicker, name: String, mime: String) {
        compose.waitUntil(5000) { picker.intent != null }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent!!.action)
        assertEquals(name, picker.intent!!.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(mime, picker.intent!!.type)
    }

    private fun finishExport(picker: ExportPicker, format: String, size: Int): File {
        // 模拟覆盖已有的更大文件，验证输出流会截断旧内容。
        val target = File(app.store.exportsDir, "book-export-${UUID.randomUUID()}.$format").apply { writeBytes(ByteArray(size + 128) { 42 }) }
        files += target
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", target)
        compose.runOnIdle { picker.dispatchResult(picker.pendingCode!!, Activity.RESULT_OK, Intent().setData(uri)) }
        return target
    }

    private fun awaitMessage(message: String) {
        compose.waitUntil(5000) { snackbar.currentSnackbarData?.visuals?.message == message }
    }

    private fun parseExport(file: File) = DocumentTools.parseFile(file.name, file, { _, _ -> })

    private inner class ExportPicker : ActivityResultRegistry() {
        @Volatile var pendingCode: Int? = null
        @Volatile var intent: Intent? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            pendingCode = requestCode
            intent = contract.createIntent(app, input)
        }
    }

    private fun txtBytes() = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "第一章\r\n导出验证正文。\r\n".toByteArray(Charsets.UTF_16LE)

    private fun epubBytes(): ByteArray {
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:book-export-test</dc:identifier><dc:title>导出测试</dc:title><dc:language>zh</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="toc" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest><spine toc="toc"><itemref idref="chapter"/></spine></package>""",
            "OEBPS/toc.ncx" to """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="urn:uuid:book-export-test"/></head><docTitle><text>导出测试</text></docTitle><navMap><navPoint id="chapter" playOrder="1"><navLabel><text>第一章</text></navLabel><content src="chapter.xhtml"/></navPoint></navMap></ncx>""",
            "OEBPS/chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title></head><body><h1>第一章</h1><p>导出验证正文。</p></body></html>""",
        )
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip -> entries.forEach { (name, text) ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                val entry = ZipEntry(name)
                if(name == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            } }
        }.toByteArray()
    }
}

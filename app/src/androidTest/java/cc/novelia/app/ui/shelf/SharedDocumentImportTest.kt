package cc.novelia.app.ui.shelf

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.ComicReadingMode
import cc.novelia.app.data.model.ShelfLocation
import cc.novelia.app.files.importing.documentImportLock
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 仅创建、清理本次测试的文件和书籍；分享通过真实 Activity 分发和 ContentResolver 导入。 */
class SharedDocumentImportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
    private val prefix = "分享测试-${UUID.randomUUID()}"
    private val files = mutableListOf<File>()

    private lateinit var priorLocation: ShelfLocation
    @Before fun ready() = runBlocking { app.initialization.await(); priorLocation = app.store.state.value.lastShelfLocation }

    @After fun cleanup() {
        app.store.state.value.books.filter { it.book.title.startsWith(prefix) }.forEach { app.store.removeDocument(it.book.ref.id) }
        files.forEach { it.delete() }
        app.store.update { it.copy(lastShelfLocation = priorLocation) }
    }

    @Test fun bookPickerAcceptsGenericProviderTypesAndRetainsEverySelectedUri() {
        val contract = cc.novelia.app.ui.components.documents.OpenBookDocuments()
        val intent = contract.createIntent(app, Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", intent.type)
        assertFalse(intent.hasExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        val first = Uri.parse("content://files/one.cbz")
        val second = Uri.parse("content://files/two.cb7")
        val result = Intent().apply {
            clipData = ClipData.newRawUri("漫画", first).apply { addItem(ClipData.Item(second)) }
        }
        assertEquals(listOf(first, second), contract.parseResult(android.app.Activity.RESULT_OK, result))
        assertTrue(contract.parseResult(android.app.Activity.RESULT_CANCELED, null).isEmpty())
    }

    @Test fun coldShareImportsTextAndRecreationDoesNotReplayIt() {
        val uri = fixture("txt", "第一章 开始\n这是系统分享导入的正文。$prefix")
        val intent = share(listOf(uri), "text/plain").putExtra(Intent.EXTRA_TEXT, "不应当搜索此文件说明")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            awaitBooks(1)
            compose.onNodeWithTag("import-results").performClick()
            compose.onNodeWithText("成功 · 已加入书架").assertExists()
            // 在结果面板打开时重建，导入清单和已完成状态必须继续保留。
            scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("成功 · 已加入书架").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("成功 · 已加入书架").assertIsDisplayed()
            compose.onNodeWithText("开始阅读").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("这是系统分享导入的正文。$prefix", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, importedCount())
        }
    }

    @Test fun cbzOpensInComicReaderAndKeepsThePageAcrossModesAndRecreation() {
        val previousMode = app.store.state.value.comicReadingMode
        app.store.update { it.copy(comicReadingMode = ComicReadingMode.RightToLeft) }
        val file = File(app.store.exportsDir, "$prefix.cbz")
        files += file
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf(10, 2, 1).forEach { number ->
                val image = Bitmap.createBitmap(160, if (number == 2) 1500 else 240, Bitmap.Config.ARGB_8888)
                image.eraseColor(android.graphics.Color.rgb(number * 20, 100, 120))
                zip.putNextEntry(ZipEntry("$number.png"))
                image.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                image.recycle()
            }
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        try {
            ActivityScenario.launch<MainActivity>(share(listOf(uri), "application/vnd.comicbook+zip")).use { scenario ->
                awaitBooks(1)
                val ref = app.store.state.value.books.single { it.book.title == prefix }.book.ref
                compose.onNodeWithTag("import-results").performClick()
                compose.onNodeWithText("开始阅读").performClick()
                fun awaitText(label: String) = compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
                }
                awaitText("1 / 3")
                compose.onNodeWithContentDescription("漫画显示设置").assertDoesNotExist()
                compose.onNodeWithText("1 / 3").performClick()
                compose.onNodeWithContentDescription("页缩略图").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("跳到第 3 页").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("跳到第 3 页").performClick()
                awaitText("3 / 3")
                compose.onNodeWithContentDescription("上一页").performClick()
                compose.onNodeWithContentDescription("上一页").performClick()
                awaitText("1 / 3")
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("第 1 页").performTouchInput { doubleClick(center) }
                compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "250%")).assertExists()
                compose.onNodeWithContentDescription("第 1 页").performTouchInput { doubleClick(center) }
                compose.onNodeWithContentDescription("第 1 页").performTouchInput { swipeRight() }
                awaitText("2 / 3")
                compose.onNodeWithText("2 / 3").performClick()
                compose.onNodeWithText("从右向左").performClick()
                compose.onNodeWithText("纵向条漫").performClick()
                assertEquals(ComicReadingMode.RightToLeft, app.store.state.value.comicReadingMode)
                assertEquals(ComicReadingMode.Webtoon, app.store.state.value.books.single { it.book.ref == ref }.comicPreferences.mode)
                val strip = compose.onNodeWithTag("comic-strip")
                strip.performTouchInput { doubleClick(center) }
                strip.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "200%"))
                strip.performTouchInput { doubleClick(center) }
                strip.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "100%"))
                compose.onNodeWithContentDescription("下一屏").performClick()
                compose.waitUntil(10_000) { (app.store.state.value.positions[ref.key]?.offset ?: 0) > 0 }
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("第 2 页").fetchSemanticsNodes().isNotEmpty() }
                val position = app.store.state.value.positions.getValue(ref.key)
                scenario.recreate()
                awaitText("2 / 3")
                assertEquals(position.offset, app.store.state.value.positions.getValue(ref.key).offset)
                compose.onNodeWithText("纵向条漫").performClick()
                compose.onNodeWithText("从左向右").performClick()
                compose.onNodeWithContentDescription("第 2 页").performTouchInput { swipeLeft() }
                awaitText("3 / 3")
                compose.onNodeWithText("3 / 3").performClick()
                compose.onNodeWithContentDescription("返回").performClick()
                compose.onNodeWithTag("shelf-medium").assertTextEquals("漫画")
                compose.onNodeWithTag("shelf-continue-reading").performClick()
                awaitText("3 / 3")
                assertTrue(app.store.state.value.positions.getValue(ref.key).chapterCompleted)
            }
        } finally { app.store.update { it.copy(comicReadingMode = previousMode) } }
    }

    @Test fun imageEpubTurnsAcrossSpineAndResumesAtOriginalChapterId() {
        val previousMode = app.store.state.value.comicReadingMode
        app.store.update { it.copy(comicReadingMode = ComicReadingMode.LeftToRight) }
        val file = File(app.store.exportsDir, "$prefix.epub")
        files += file
        ZipOutputStream(file.outputStream()).use { zip ->
            fun text(name: String, contents: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(contents.toByteArray()); zip.closeEntry()
            }
            text("META-INF/container.xml", """<container><rootfile full-path="book.opf"/></container>""")
            text("book.opf", """<package><manifest><item id="first" href="10.png" media-type="image/png"/><item id="second" href="2.png" media-type="image/png"/></manifest><spine><itemref idref="first"/><itemref idref="second"/></spine></package>""")
            for (i in listOf(2, 10)) {
                val bitmap = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888)
                zip.putNextEntry(ZipEntry("$i.png"))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                bitmap.recycle(); zip.closeEntry()
            }
        }
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            ActivityScenario.launch<MainActivity>(share(listOf(uri), "application/epub+zip")).use {
                awaitBooks(1)
                val key = app.store.state.value.books.single { it.book.title == prefix }.book.ref.key
                compose.onNodeWithText("阅读").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("1 / 2").performClick()
                compose.onNodeWithText("上一话").assertIsNotEnabled()
                compose.onNodeWithText("下一话").performClick()
                compose.waitUntil(10_000) { app.store.state.value.positions[key]?.chapterId == "second" }
                compose.onNodeWithText("上一话").performClick()
                compose.waitUntil(10_000) { app.store.state.value.positions[key]?.chapterId == "first" }
                compose.onNodeWithText("目录").performClick()
                compose.onNodeWithText("章节目录").assertIsDisplayed()
                compose.onAllNodesWithText("1 页").onLast().performClick()
                compose.waitUntil(10_000) { app.store.state.value.positions[key]?.chapterId == "second" }
                assertEquals(1, app.store.state.value.positions.getValue(key).index)
                compose.onNodeWithContentDescription("返回").performClick()
                compose.onNodeWithTag("shelf-medium").assertTextEquals("漫画")
                compose.onNodeWithTag("shelf-continue-reading").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty() }
                assertEquals("second", app.store.state.value.positions[key]?.chapterId)
            }
        } finally { app.store.update { it.copy(comicReadingMode = previousMode) } }
    }

    @Test fun mixedEpubCanSwitchShelvesAndKeepTextAndImagePages() {
        val file = File(app.store.exportsDir, "$prefix.epub")
        files += file
        ZipOutputStream(file.outputStream()).use { zip ->
            fun text(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
            text("META-INF/container.xml", "<container><rootfile full-path='book.opf'/></container>")
            text("book.opf", "<package><manifest><item id='p' href='p.xhtml'/><item id='i' href='i.png' media-type='image/png'/></manifest><spine><itemref idref='p'/><itemref idref='i'/></spine></package>")
            text("p.xhtml", "<html><body><p>版权说明 $prefix</p></body></html>")
            zip.putNextEntry(ZipEntry("i.png"))
            Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).also { it.compress(Bitmap.CompressFormat.PNG, 100, zip); it.recycle() }
            zip.closeEntry()
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        ActivityScenario.launch<MainActivity>(share(listOf(uri), "application/epub+zip")).use {
            awaitBooks(1)
            compose.onNodeWithContentDescription("管理 $prefix").performClick()
            compose.onNodeWithText("书籍详情").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("阅读方式 · 自动识别").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("阅读方式 · 自动识别").performClick()
            compose.onNodeWithText("作为漫画阅读").performClick()
            compose.onNodeWithText("开始阅读").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("版权说明 $prefix").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1 / 2").performClick()
            compose.onNodeWithContentDescription("漫画显示设置").performClick()
            compose.onNodeWithText("按阅读方向拆分横跨页").performClick()
            compose.onNodeWithText("裁去白边").performClick()
            compose.onNodeWithText("适合宽度").performClick()
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithContentDescription("下一页").performClick()
            val ref = app.store.state.value.books.single { it.book.title == prefix }.book.ref
            compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.chapterId == "i" }
            compose.onNodeWithContentDescription("下一页").performClick()
            compose.onNodeWithContentDescription("下一页").assertIsNotEnabled()
            val item = app.store.state.value.books.single { it.book.title == prefix }
            assertEquals(cc.novelia.app.data.model.BookMedium.Comic, item.book.medium)
            assertEquals("i", app.store.state.value.positions[item.book.ref.key]?.chapterId)
            assertTrue(item.comicPreferences.splitWide && item.comicPreferences.fitWidth && item.comicPreferences.cropBorders)
        }
    }

    @Test fun dismissedImportStaysClosedAfterRecreationAndNextBatchCanBeShown() {
        val uri = fixture("txt", "关闭提示后仍保留正文 $prefix")
        ActivityScenario.launch<MainActivity>(share(listOf(uri), "text/plain")).use { scenario ->
            awaitBooks(1)
            compose.onNodeWithText("已准备好阅读").assertDoesNotExist()
            compose.onNodeWithContentDescription("关闭导入结果").performClick()
            compose.onNodeWithTag("import-status").assertDoesNotExist()
            scenario.recreate()
            compose.onNodeWithTag("import-status").assertDoesNotExist()
            compose.onNodeWithTag("shelf-tab-0").performClick()
            compose.onNodeWithTag("shelf-tab-1").performClick()
            compose.onNodeWithTag("import-status").assertDoesNotExist()
            assertEquals(1, importedCount())

            // 同一文件的新批次也应显示；关闭提示不会删除书籍或破坏去重。
            lateinit var original: Intent
            scenario.onActivity { original = Intent(it.intent) }
            try {
                scenario.onActivity { it.startActivity(share(listOf(uri), "text/plain")) }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("文件已在书架中").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("阅读").assertIsDisplayed()
                compose.onNodeWithContentDescription("关闭导入结果").performClick()
                compose.onNodeWithTag("import-status").assertDoesNotExist()
                assertEquals(1, importedCount())
            } finally { scenario.onActivity { it.intent = original } }
        }
    }

    @Test fun openWithImportsOnColdAndWarmLaunchAndKeepsDuplicatesOut() {
        val text = fixture("txt", "打开方式正文 $prefix")
        val epub = epubFixture()
        fun open(uri: Uri, type: String) = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).setPackage(app.packageName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ActivityScenario.launch<MainActivity>(open(text, "text/plain")).use { scenario ->
            awaitBooks(1)
            scenario.recreate()
            assertEquals(1, importedCount())
            lateinit var original: Intent
            scenario.onActivity { original = Intent(it.intent) }
            try {
                scenario.onActivity { it.startActivity(open(text, "application/octet-stream")) }
                compose.waitForIdle()
                scenario.onActivity { it.startActivity(open(epub, "application/epub+zip")) }
                awaitBooks(2)
                scenario.onActivity { assertEquals(Intent.ACTION_VIEW, it.intent.action) }
                compose.onNodeWithTag("import-results").performClick()
                compose.onAllNodesWithText("开始阅读").onFirst().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText(prefix, substring = true).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(2, importedCount())
            } finally { scenario.onActivity { it.intent = original } }
        }
    }

    @Test fun warmClipShareImportsBooksAndReportsUnsupportedFiles() {
        val epub = epubFixture()
        val srt = fixture("srt", "1\n00:00:01,000 --> 00:00:02,000\n字幕正文 $prefix")
        val bad = fixture("pdf", "不支持的文件 $prefix")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var launchIntent: Intent
            scenario.onActivity { launchIntent = Intent(it.intent) }
            try {
                compose.waitUntil(10_000) { compose.onAllNodesWithText("我的", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("我的").performClick()
                val intent = share(listOf(epub, srt, bad), "application/octet-stream", clipOnly = true).apply {
                    // 从现有页面模拟 onNewIntent，不另建一个由分享 Intent 标识的任务。
                    flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                }
                scenario.onActivity { it.startActivity(intent) }
                awaitBooks(2)
                scenario.onActivity { assertEquals(Intent.ACTION_SEND_MULTIPLE, it.intent.action) }
                compose.onNodeWithTag("import-results").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("失败 · " + cc.novelia.app.data.model.supportedBookFiles).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(setOf("epub", "srt"), app.store.state.value.books.filter { it.book.title.startsWith(prefix) }
                    .map { app.store.documentIndex(it.book.ref.id).format }.toSet())
            } finally {
                // ActivityScenario 用初始 Intent 匹配生命周期；setIntent 后需恢复身份才能观测关闭。
                scenario.onActivity { it.intent = launchIntent }
            }
        }
    }

    @Test fun anotherShareWaitsForTheRunningBatchAndDuplicatesKeepOneBook() {
        val first = fixture("txt", "第一本正文 $prefix", "first")
        val second = fixture("txt", "第二本正文 $prefix", "second")
        runBlocking { documentImportLock.lock() }
        var locked = true
        try {
            ActivityScenario.launch<MainActivity>(share(listOf(first), "text/plain")).use { scenario ->
                compose.waitUntil(10_000) { compose.onAllNodesWithText("正在导入 0/1").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("关闭导入结果").assertDoesNotExist()
                scenario.onActivity { it.startActivity(share(listOf(second), "text/plain")) }
                compose.waitForIdle()
                scenario.recreate()
                documentImportLock.unlock()
                locked = false
                awaitBooks(2)
                scenario.onActivity { it.startActivity(share(listOf(first), "text/plain")) }
                compose.onNodeWithTag("import-results").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("重复 · 书架中已有相同文件").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(2, importedCount())
            }
        } finally { if(locked) documentImportLock.unlock() }
    }

    private fun importedCount() = app.store.state.value.books.count { it.book.title.startsWith(prefix) }
    private fun awaitBooks(count: Int) {
        compose.waitUntil(20_000) { importedCount() == count }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("正在导入", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("import-results").assertExists()
    }

    private fun fixture(extension: String, text: String, suffix: String = extension): Uri {
        val file = File(app.store.exportsDir, "$prefix-$suffix.$extension").apply { writeText(text, Charsets.UTF_8) }
        files += file
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    private fun epubFixture(): Uri {
        val file = File(app.store.exportsDir, "$prefix-epub.epub")
        files += file
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf(
                "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
                "book.opf" to "<package><manifest><item id='c' href='chapter.xhtml'/></manifest><spine><itemref idref='c'/></spine></package>",
                "chapter.xhtml" to "<html><body><p>EPUB 正文 $prefix</p></body></html>",
            ).forEach { (name, contents) -> zip.putNextEntry(ZipEntry(name)); zip.write(contents.toByteArray()); zip.closeEntry() }
        }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    private fun share(uris: List<Uri>, type: String, clipOnly: Boolean = false) = Intent(app, MainActivity::class.java).apply {
        action = if(uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        this.type = type
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        clipData = ClipData.newRawUri("书籍", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        if(!clipOnly) {
            if(uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
}

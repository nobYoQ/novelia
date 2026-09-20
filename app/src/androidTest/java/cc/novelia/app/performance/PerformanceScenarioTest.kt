package cc.novelia.app.performance

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.library.shelfGroups
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.reader.prepareReadingParagraphs
import cc.novelia.app.ui.markdown.format
import cc.novelia.app.ui.reader.measureEInkChapter
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline measurement harness. Every library file belongs to a disposable isolated context. */
@RunWith(AndroidJUnit4::class)
class PerformanceScenarioTest {
    private class IsolatedContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
    }

    @Test fun isolatedLibraryAndReaderMatrix(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val iterations = InstrumentationRegistry.getArguments().getString("performanceIterations")?.toIntOrNull()?.coerceIn(1, 10) ?: 3
        val runId = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "performance-fixture-$runId").apply { mkdirs() }
        val isolated = IsolatedContext(base, root)
        val report = PerformanceReport(base, iterations)
        var store: LocalStore? = null
        try {
            withContext(Dispatchers.IO) {
                val current = LocalStore(isolated)
                store = current
                val books = List(1000) { index ->
                    SavedBook(BookCard(BookRef("local", "fixture-$index"), "测试书籍 ${index.toString().padStart(4, '0')}", subtitle = "离线性能样本"),
                        folder = "分组${index % 10}", addedAt = index.toLong())
                }
                report.stage("shelf.persist", JSONObject().put("books", 1000)) {
                    current.update { it.copy(books = books) }; current.flush()
                }
                repeat(iterations) { iteration ->
                    val loaded = report.stage("shelf.reload", JSONObject().put("books", 1000).put("iteration", iteration)) { LocalStore(isolated) }
                    assertEquals(1000, loaded.state.value.books.size)
                    for(sort in 0..2) report.stage("shelf.groupAndSort", JSONObject().put("books", 1000).put("sort", sort).put("iteration", iteration)) {
                        assertEquals(1000, loaded.state.value.shelfGroups(true, "全部", "", sort).size)
                    }
                    report.stage("shelf.filter", JSONObject().put("books", 1000).put("iteration", iteration)) {
                        assertTrue(loaded.state.value.shelfGroups(true, "全部", "测试书籍 09", 0).isNotEmpty())
                    }
                }
                val chapters = List(10_000) { index -> LocalChapter("chapter-$index", "第 ${index + 1} 章", listOf("离线目录样本第 $index 章。")) }
                report.stage("directory.persist", JSONObject().put("chapters", chapters.size)) {
                    current.saveDocument(LocalDocument("catalogue", "一万章目录", "txt", chapters))
                }
                repeat(iterations) { iteration ->
                    val reader = LocalStore(isolated)
                    val index = report.stage("directory.readIndex", JSONObject().put("chapters", 10_000).put("iteration", iteration).put("memoryCache", "cold")) {
                        reader.documentIndex("catalogue")
                    }
                    assertEquals(10_000, index.chapters.size)
                    assertTrue(index.chapters.all { it.paragraphs.isEmpty() })
                    val toc = report.stage("directory.projectToc", JSONObject().put("chapters", 10_000).put("iteration", iteration)) {
                        index.chapters.map { TocItem(it.title, it.title, it.id) }
                    }
                    assertEquals("chapter-9999", toc.last().chapterId)
                    for(chapterId in listOf("chapter-0", "chapter-9999")) {
                        for(cache in listOf("cold", "warm")) report.stage("chapter.read", JSONObject().put("chapterId", chapterId).put("iteration", iteration).put("memoryCache", cache)) {
                            assertEquals(chapterId, reader.documentChapter("catalogue", chapterId).id)
                        }
                    }
                }
                current.flush()
            }
            withContext(Dispatchers.Default) {
                val cancellation = currentCoroutineContext()
                val sample = "旅人沿着森林小路前行，寻找远处的小镇。阅读时保持文字清晰，章节之间自然接续。"
                for(characters in listOf(10_000, 100_000, 500_000)) {
                    val text = buildString(characters) { while(length < characters) append(sample.take(characters - length)) }
                    // 500-character paragraphs keep the fixture deterministic across devices.
                    val chapter = Chapter(paragraphs = text.chunked(500))
                    for(iteration in 0 until iterations) {
                        val settings = ReaderSettings(mode = "jp", fontSize = 20f, lineHeight = 1.8f, indent = true)
                        val prepared = report.stage("reader.prepare", JSONObject().put("characters", characters).put("iteration", iteration)) {
                            prepareReadingParagraphs(chapter, settings) { cancellation.ensureActive() }
                        }
                        assertEquals(characters, prepared.sumOf { paragraph -> paragraph.parts.sumOf { it.text.length } })
                        for(viewport in listOf(Viewport("phone", 327, 640, 1f), Viewport("landscape", 690, 240, 1f), Viewport("largeFont", 327, 640, 1.5f))) {
                            val dimensions = JSONObject().put("characters", characters).put("iteration", iteration).put("viewport", viewport.name)
                                .put("widthPx", viewport.width).put("heightPx", viewport.height).put("fontScale", viewport.fontScale)
                            report.stage("reader.measureAndPaginate", dimensions) {
                                val measured = measureEInkChapter(prepared, settings, viewport.width, viewport.height, 1f, viewport.fontScale) { cancellation.ensureActive() }
                                dimensions.put("pages", measured.pages.size).put("lines", measured.pages.sumOf { it.lines.size })
                                assertTrue(measured.pages.isNotEmpty())
                                assertEquals(prepared.size, measured.layouts.size)
                            }
                        }
                    }
                }
            }
            report.data.put("status", "completed")
        } catch(error: Throwable) {
            report.data.put("status", "failed").put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { store?.flush() }
                    finally {
                        // The only recursive cleanup target is this run's generated child of cacheDir.
                        check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile && root.name == "performance-fixture-$runId")
                        report.data.put("fixtureRemoved", root.deleteRecursively())
                    }
                }
            } finally {
                val output = File(base.getExternalFilesDir(null) ?: base.filesDir, "performance").apply { mkdirs() }
                val json = File(output, "scenarios-$runId.json")
                json.writeText(report.data.toString(2), Charsets.UTF_8)
                val text = File(output, "scenarios-$runId.txt")
                text.writeText(report.summary(), Charsets.UTF_8)
                instrumentation.sendStatus(0, Bundle().apply { putString("performanceReport", json.absolutePath); putString("performanceSummary", text.absolutePath) })
            }
        }
    }

    private data class Viewport(val name: String, val width: Int, val height: Int, val fontScale: Float)

    private class PerformanceReport(context: Context, iterations: Int) {
        private val stages = JSONArray()
        private val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        private val activityManager = context.getSystemService(ActivityManager::class.java)
        private val systemMemory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        private val emulator = Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone") || Build.HARDWARE in listOf("goldfish", "ranchu")
        val data = JSONObject().put("schemaVersion", 1).put("startedAtEpochMs", System.currentTimeMillis()).put("iterations", iterations)
            .put("status", "running").put("networkRequired", false).put("isolatedUserData", true)
            .put("measurementScope", "LocalStore IO, shelf grouping, catalogue projection, prepared text and Android StaticLayout pagination; UI frame timing is measured separately by ReadingBenchmark.")
            .put("limitations", "PSS and heap are before/after samples, not peak memory. No forced GC or OS page-cache eviction. Debug builds and emulators validate the harness only; compare release results on the same physical device, power state and thermal state.")
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("hardware", Build.HARDWARE).put("emulator", emulator)
                .put("processors", Runtime.getRuntime().availableProcessors()).put("lowRamDevice", activityManager.isLowRamDevice)
                .put("memoryClassMb", activityManager.memoryClass).put("totalRamBytes", systemMemory.totalMem))
            .put("build", JSONObject().put("package", context.packageName).put("versionName", packageInfo.versionName)
                .put("versionCode", if(Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()).put("debuggable", context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                .put("fingerprint", Build.FINGERPRINT)).put("stages", stages)

        suspend fun <T> stage(name: String, dimensions: JSONObject, block: suspend () -> T): T {
            data.put("currentStage", JSONObject().put("name", name).put("dimensions", dimensions))
            android.util.Log.i("NoveliaPerformance", "$name $dimensions")
            val before = memory()
            val start = System.nanoTime()
            var success = false
            try { return block().also { success = true } }
            finally {
                val elapsed = (System.nanoTime() - start) / 1_000_000.0
                stages.put(JSONObject().put("name", name).put("dimensions", dimensions).put("elapsedMs", elapsed)
                    .put("successful", success).put("before", before).put("after", memory()))
            }
        }

        private fun memory(): JSONObject {
            val runtime = Runtime.getRuntime()
            return JSONObject().put("javaHeapBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize()).put("processPssKb", Debug.getPss())
        }

        fun summary(): String = buildString {
            appendLine("Novelia isolated performance scenarios")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.SDK_INT}; emulator=$emulator")
            appendLine("Build: ${data.getJSONObject("build")}")
            appendLine("Status: ${data.optString("status")}; fixtureRemoved=${data.optBoolean("fixtureRemoved")}")
            appendLine(data.getString("limitations"))
            appendLine("Stage | Parameters | elapsed ms | Java heap before/after bytes | PSS before/after KB")
            for(index in 0 until stages.length()) {
                val stage = stages.getJSONObject(index)
                val before = stage.getJSONObject("before"); val after = stage.getJSONObject("after")
                appendLine("${stage.getString("name")} | ${stage.getJSONObject("dimensions")} | ${String.format(Locale.ROOT, "%.3f", stage.getDouble("elapsedMs"))} | ${before.getLong("javaHeapBytes")}/${after.getLong("javaHeapBytes")} | ${before.getLong("processPssKb")}/${after.getLong("processPssKb")}")
            }
        }
    }
}

package cc.novelia.app.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.files.DocumentTools
import cc.novelia.app.files.DownloadWorker
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadLiveTest {
    @Test fun generatedEpubIsDownloadedAndParseable() = verifyEpubDownload(legacyName = false)

    @Test fun retryingALegacyLongFilenameDownloadsACompleteEpub() = verifyEpubDownload(legacyName = true)

    private fun verifyEpubDownload(legacyName: Boolean) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        val candidate = app.api.webList(0, query = "<3", provider = "syosetu", type = 3).items.first { it.jp > 0 }
        val ref = BookRef(candidate.providerId, candidate.novelId)
        val id = UUID.randomUUID().toString()
        val label = if (legacyName) "长书名".repeat(40) + ".epub" else "smoke.epub"
        val entry = DownloadEntry(id, "下载验证：${candidate.titleZh ?: candidate.titleJp}", "$id-$label",
            app.api.downloadUrl(ref, null, "jp", listOf("sakura", "gpt", "youdao"), false, "epub", label),
            status = if (legacyName) "失败" else DownloadEntry.QUEUED)
        try {
            if (legacyName) app.store.update { it.copy(downloads = it.downloads + entry) }
            DownloadWorker.enqueue(app, entry)
            var result: DownloadEntry? = null
            repeat(90) { if(result?.status !in listOf("已完成", "失败", "需要登录")) { delay(1000); result = app.store.state.value.downloads.find { it.id == id } } }
            assertEquals(result?.error ?: "下载超时", "已完成", result?.status)
            val completed = requireNotNull(result)
            val file = File(app.store.downloadsDir, completed.fileName)
            assertTrue(file.length() > 0)
            if (legacyName) assertNotEquals(entry.fileName, completed.fileName)
            assertTrue(DocumentTools.parse(completed.fileName, file.readBytes()).chapters.isNotEmpty())
        } finally { DownloadWorker.remove(app, id) }
    }
}

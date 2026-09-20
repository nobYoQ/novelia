package cc.novelia.app.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LibraryStateCodecTest {
    @Test fun restoringTheSameLoadedStateRepairsDeletedAndCorruptPayloads() {
        listOf(false, true).forEach { corrupt ->
            val directory = Files.createTempDirectory("library-repair").toFile()
            try {
                var writes = 0
                fun codec() = LibraryStateCodec(directory, { it.readText() }, { file, text -> writes++; file.writeText(text) })
                val state = LibraryState(drafts = mapOf("draft" to "必须保留的草稿"), savedArticles = listOf(Article(content = "文章正文")))
                val first = codec().encode(state)
                val running = codec()
                assertEquals(state, running.decode(first))
                val payload = directory.listFiles()!!.single()
                if (corrupt) payload.writeText("damaged") else assertTrue(payload.delete())
                val restored = running.encode(state, forcePayloadWrite = true)
                assertEquals(state, codec().decode(restored))
                assertEquals(2, writes)
                running.encode(state.copy(positions = mapOf("local/book" to Position("chapter"))))
                assertEquals("Normal progress still reuses the repaired payload", 2, writes)
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun progressWritesDoNotReencodeOrRewriteLongTextAndLegacyStateRemainsReadable() {
        val directory = Files.createTempDirectory("library-text").toFile()
        try {
            var writes = 0
            val codec = LibraryStateCodec(directory, { it.readText() }, { file, text -> writes++; file.writeText(text) })
            val state = LibraryState(savedArticles = listOf(Article(id = "post", content = "正文".repeat(100_000))), drafts = mapOf("article:new" to "草稿全文"))
            assertEquals(state, codec.decode(appJson.encodeToString(state)))
            val first = codec.encode(state)
            assertTrue(first.length < 5_000)
            val progress = state.copy(positions = mapOf("local/book" to Position("c", 100)))
            val second = codec.encode(progress)
            assertEquals(1, writes)
            assertEquals(progress, codec.decode(second))
            val restarted = LibraryStateCodec(directory, { it.readText() }, { file, text -> writes++; file.writeText(text) })
            assertEquals(progress, restarted.decode(second))
            restarted.encode(progress.copy(recentSearches = listOf("新搜索")))
            assertEquals(1, writes)
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedMainWritePreservesOldPayloadAndCorruptionProtectsLibrary() {
        val directory = Files.createTempDirectory("library-text").toFile()
        try {
            val codec = LibraryStateCodec(directory, { it.readText() }, { file, text -> file.writeText(text) })
            val state = LibraryState(drafts = mapOf("draft" to "不能丢失"))
            val committed = codec.encode(state)
            codec.encode(state.copy(drafts = mapOf("draft" to "未提交的新版")))
            // Simulate failure before the library pointer is committed.
            assertEquals(state, codec.decode(committed))
            val changed = codec.encode(state.copy(drafts = mapOf("draft" to "恢复副本")))
            directory.listFiles()!!.forEach { it.writeText("corrupt") }
            val result = loadLibraryState(true, { changed }, { appJson.encodeToString(state) }, codec::decode)
            assertEquals(state, result.state)
            assertEquals(true, result.issue?.hasLastGood)
        } finally { directory.deleteRecursively() }
    }
}

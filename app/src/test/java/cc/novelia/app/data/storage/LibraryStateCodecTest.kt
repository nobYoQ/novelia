package cc.novelia.app.data.storage

import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LibraryStateCodecTest {
    @Test fun readingStatisticsUseReadingPartitionAndSurviveRestart() {
        val directory = Files.createTempDirectory("reading-statistics").toFile()
        try {
            fun codec() = LibraryStateCodec(directory, { it.readText() }, { file, text -> file.writeText(text) })
            val running = codec()
            val original = LibraryState()
            val first = running.encode(original)
            val state = original.copy(readingStatistics = cc.novelia.app.data.model.ReadingStatistics(days = mapOf(
                "2026-10-30" to mapOf("local/a" to cc.novelia.app.data.model.ReadingTimeEntry("书 A", 60_000)))))
            val second = running.encode(state)
            assertEquals(state, codec().decode(second))
            assertEquals(parts(first).filterKeys { it != "reading" }, parts(second).filterKeys { it != "reading" })
            assertNotEquals(parts(first)["reading"], parts(second)["reading"])
        } finally { directory.deleteRecursively() }
    }
    @Test fun restoringTheSameLoadedStateRepairsDeletedAndCorruptPayloads() {
        listOf(false, true).forEach { corrupt ->
            val directory = Files.createTempDirectory("library-repair").toFile()
            try {
                var writes = 0
                fun codec() = LibraryStateCodec(directory, { it.readText() }, { file, text -> writes++; file.writeText(text) })
                val state = LibraryState(drafts = mapOf("draft" to "必须保留的草稿"))
                val first = codec().encode(state)
                val running = codec()
                assertEquals(state, running.decode(first))
                val payload = directory.resolve(parts(first).getValue("drafts") + ".json")
                if (corrupt) payload.writeText("damaged") else assertTrue(payload.delete())
                val restored = running.encode(state, forcePayloadWrite = true)
                assertEquals(state, codec().decode(restored))
                assertEquals(12, writes)
                running.encode(state.copy(positions = mapOf("local/book" to Position("chapter"))))
                assertEquals("Normal progress only writes the reading partition", 13, writes)
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun progressWritesDoNotReencodeOrRewriteLongTextAndLegacyStateRemainsReadable() {
        val directory = Files.createTempDirectory("library-text").toFile()
        try {
            var writes = 0
            val codec = LibraryStateCodec(directory, { it.readText() }, { file, text -> writes++; file.writeText(text) })
            val state = LibraryState(drafts = mapOf("article:new" to "草稿全文".repeat(100_000)), forumRulesReminderDismissed = true)
            assertEquals(state, codec.decode(appJson.encodeToString(state)))
            val first = codec.encode(state)
            assertTrue(first.length < 5_000)
            val progress = state.copy(positions = mapOf("local/book" to Position("c", 100)))
            val second = codec.encode(progress)
            assertEquals(7, writes)
            assertEquals(parts(first).getValue("drafts"), parts(second).getValue("drafts"))
            assertEquals(progress, codec.decode(second))
            val restarted = LibraryStateCodec(directory, { it.readText() }, { file, text -> writes++; file.writeText(text) })
            assertEquals(progress, restarted.decode(second))
            restarted.encode(progress.copy(recentSearches = listOf("新搜索")))
            assertEquals(8, writes)
        } finally { directory.deleteRecursively() }
    }

    @Test fun legacyFavoritesAreDroppedWhileInlineAndExternalDraftsRemainReadable() {
        val directory = Files.createTempDirectory("library-favorite-removal").toFile()
        try {
            fun codec() = LibraryStateCodec(directory, { it.readText() }, { file, text -> file.writeText(text) })
            val state = LibraryState(drafts = mapOf("article:new" to "必须保留的草稿"), positions = mapOf("local/book" to Position("c", 12)))
            val oldArticles = appJson.parseToJsonElement("""[{"id":"f-5","content":"旧收藏正文"}]""")
            val inline = JsonObject(appJson.encodeToJsonElement(LibraryState.serializer(), state).jsonObject + ("savedArticles" to oldArticles))
            assertEquals(state, codec().decode(inline.toString()))

            val payload = """{"articles":[{"id":"f-5","content":"旧收藏正文"}],"drafts":{"article:new":"必须保留的草稿"}}"""
            val oldHash = hashName(payload)
            directory.resolve("$oldHash.json").writeText(payload)
            val external = JsonObject(appJson.encodeToJsonElement(LibraryState.serializer(), state.copy(drafts = emptyMap())).jsonObject +
                mapOf("savedArticles" to oldArticles, "localLongTextPayload" to JsonPrimitive(oldHash)))
            val running = codec()
            assertEquals(state, running.decode(external.toString()))
            val migrated = running.encode(state)
            val json = appJson.parseToJsonElement(migrated).jsonObject
            assertFalse("savedArticles" in json)
            val newHash = parts(migrated).getValue("drafts")
            assertNotEquals(oldHash, newHash)
            assertFalse("articles" in appJson.parseToJsonElement(directory.resolve("$newHash.json").readText()).jsonObject)
            assertEquals(state, codec().decode(migrated))
            running.compact()
            assertEquals("上一份状态仍可用于恢复草稿", state, codec().decode(external.toString()))
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedMainWritePreservesOldPayloadAndCorruptionProtectsLibrary() {
        val directory = Files.createTempDirectory("library-text").toFile()
        try {
            val codec = LibraryStateCodec(directory, { it.readText() }, { file, text -> file.writeText(text) })
            val state = LibraryState(drafts = mapOf("draft" to "不能丢失"))
            val committed = codec.encode(state)
            codec.encode(state.copy(drafts = mapOf("draft" to "未提交的新版")))
            // 模拟书库引用提交之前发生失败。
            assertEquals(state, codec.decode(committed))
            val changed = codec.encode(state.copy(drafts = mapOf("draft" to "恢复副本")))
            directory.listFiles()!!.forEach { it.writeText("corrupt") }
            val result = loadLibraryState(true, { changed }, { appJson.encodeToString(state) }, codec::decode)
            assertEquals(state, result.state)
            assertEquals(true, result.issue?.hasLastGood)
        } finally { directory.deleteRecursively() }
    }

    private fun parts(text: String) = appJson.parseToJsonElement(text).jsonObject.getValue("localLibraryParts")
        .jsonObject.mapValues { it.value.jsonPrimitive.content }
}

package cc.novelia.app.data.storage

import cc.novelia.app.data.library.withReadingPosition
import cc.novelia.app.data.model.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PartitionedLibraryTest {
    private fun parts(text: String) = appJson.parseToJsonElement(text).jsonObject.getValue("localLibraryParts")
        .jsonObject.mapValues { it.value.jsonPrimitive.content }
    private fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("library-partitions-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
    private fun codec(dir: File) = LibraryStateCodec(dir, { it.readText(Charsets.UTF_8) }, { file, text -> file.writeText(text, Charsets.UTF_8) })

    @Test fun shelfSortsSurviveDiskReloadIndependentlyOfRememberingFolders() = fixture { dir ->
        val state = LibraryState(shelfSort = 2, rememberLastShelfFolder = false, lastShelfLocation = ShelfLocation(tab = 2))
            .withCloudShelfSort("alice", "create")
        val restored = codec(dir).decode(codec(dir).encode(state))

        assertEquals(2, restored.shelfSort)
        assertEquals(ShelfLocation(), restored.restoredShelfLocation())
        assertEquals("create", restored.cloudShelfSort("alice"))
        assertEquals("update", restored.cloudShelfSort("bob"))
        assertEquals("update", restored.withCloudShelfSort("alice", "update").cloudShelfSort("alice"))
    }

    @Test fun twoThousandBookMigrationAndProgressWritesPreserveAllOtherPartitions() = fixture { dir ->
        val legacy = LibraryState(books = List(2000) { SavedBook(BookCard(BookRef("local", "book-$it"), "测试小说 $it")) },
            notes = listOf(Note("note", "local/book-1999", "chapter", 23, "原文", "笔记")),
            drafts = mapOf("draft" to "草稿"), squareCorners = true, amoledBlack = true,
            bookSettings = mapOf("local/book-1999" to ReaderSettings(fontSize = 25f)))
        var bytes = 0L; var writes = 0
        val writer = LibraryStateCodec(dir, { it.readText() }, { file, text ->
            writes++; bytes += text.toByteArray(Charsets.UTF_8).size; file.writeText(text, Charsets.UTF_8)
        })
        assertEquals(legacy, writer.decode(appJson.encodeToString(legacy)))
        val initial = writer.encode(legacy)
        writer.committed(initial); writer.compact()
        val initialParts = parts(initial)
        writes = 0; bytes = 0
        val next = legacy.withReadingPosition(BookRef("local", "book-1999"), Position("chapter", 23, 17,
            textOffset = 45, chapterIndex = 5, chapterCount = 30, paragraphCount = 100,
            sourceParagraph = 22, anchorSource = "jp", anchorTextHash = "anchor"))
        val saved = writer.encode(next)
        writer.committed(saved); writer.compact()
        assertEquals(1, writes)
        assertTrue("Progress writes must not scale with the book metadata", bytes < 4096)
        assertTrue(saved.toByteArray().size < 1024)
        assertEquals(setOf("reading"), parts(saved).keys.filter { parts(saved)[it] != initialParts[it] }.toSet())
        assertEquals(next, codec(dir).decode(saved))
        assertEquals(legacy, codec(dir).decode(initial))
        val reopened = codec(dir)
        reopened.decode(saved)
        val again = reopened.encode(next.copy(positions = next.positions + ("local/book-1999" to next.positions.getValue("local/book-1999").copy(index = 24))))
        assertEquals(parts(saved)["shelf"], parts(again)["shelf"])
    }

    @Test fun failedMainCommitsNeverDisplaceTheLastCommittedPayloadsDuringCleanup() = fixture { dir ->
        val writer = codec(dir)
        val first = LibraryState(drafts = mapOf("d" to "已提交"))
        val a = writer.encode(first); writer.committed(a); writer.compact()
        repeat(5) { writer.encode(first.copy(drafts = mapOf("d" to "未提交-$it"))) }
        val last = first.copy(drafts = mapOf("d" to "新提交"), positions = mapOf("local/a" to Position("c", 9)))
        val b = writer.encode(last); writer.committed(b); writer.compact()
        assertEquals(first, codec(dir).decode(a))
        assertEquals(last, codec(dir).decode(b))
        assertEquals(parts(a).values.toSet() + parts(b).values.toSet(), dir.listFiles()!!.map { it.nameWithoutExtension }.toSet())
    }

    @Test fun failedPayloadWriteLeavesOldSnapshotReadableAndCanRetry() = fixture { dir ->
        var fail = false
        val writer = LibraryStateCodec(dir, { it.readText() }, { file, text ->
            if(fail && "changed-progress" in text) throw IOException("disk full")
            file.writeText(text, Charsets.UTF_8)
        })
        val state = LibraryState(drafts = mapOf("d" to "旧草稿"))
        val a = writer.encode(state); writer.committed(a)
        val next = state.copy(drafts = mapOf("d" to "新草稿"), positions = mapOf("local/a" to Position("changed-progress")))
        fail = true
        assertThrows(IOException::class.java) { writer.encode(next) }
        assertEquals(state, codec(dir).decode(a))
        fail = false
        val b = writer.encode(next); writer.committed(b); writer.compact()
        assertEquals(next, codec(dir).decode(b))
        assertEquals(state, codec(dir).decode(a))
    }

    @Test fun corruptPartitionFallsBackAsOneSnapshotAndExplicitRestoreRepairsEveryPartition() = fixture { dir ->
        val state = LibraryState(drafts = mapOf("d" to "草稿"), positions = mapOf("local/b" to Position("c", 17)))
        val writer = codec(dir)
        val a = writer.encode(state); writer.committed(a)
        val next = state.copy(positions = mapOf("local/b" to Position("next", 29)))
        val b = writer.encode(next); writer.committed(b)
        File(dir, parts(b).getValue("reading") + ".json").writeText("corrupt")
        val loaded = loadLibraryState(true, { b }, { a }, codec(dir)::decode)
        assertEquals(state, loaded.state)
        assertEquals(true, loaded.issue?.hasLastGood)
        dir.listFiles()!!.forEach { it.delete() }
        val repaired = writer.encode(next, forcePayloadWrite = true)
        assertEquals(next, codec(dir).decode(repaired))
    }

    @Test fun missingPartitionsUnknownVersionsAndUnsafePathsAreRejected() = fixture { dir ->
        val writer = codec(dir)
        val a = appJson.parseToJsonElement(writer.encode(LibraryState())).jsonObject
        val refs = a.getValue("localLibraryParts").jsonObject
        listOf(
            JsonObject(a + ("localLibraryFormat" to JsonPrimitive(3))),
            JsonObject(a - "localLibraryFormat"),
            JsonObject(a + ("localLibraryParts" to JsonObject(refs - "reading"))),
            JsonObject(a + ("localLibraryParts" to JsonObject(refs + ("reading" to JsonPrimitive("../outside")))))
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { writer.decode(invalid.toString()) } }
    }
}

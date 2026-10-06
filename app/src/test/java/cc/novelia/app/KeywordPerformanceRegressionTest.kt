package cc.novelia.app

import cc.novelia.app.data.catalog.*
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KeywordPerformanceRegressionTest {
    private fun library(size: Int) = KeywordLibrary(
        (0 until size).map { KeywordEntry("标签$it", "译名$it") }, syncReplica = SyncReplica(deviceId = "test-device"),
    )
    private fun tracked(library: KeywordLibrary) = library.copy(syncReplica = library.syncReplica.track(emptyMap(), WebDavProjection.keywords(library)))
    private fun reference(before: KeywordLibrary, after: KeywordLibrary) = before.syncReplica.track(
        WebDavProjection.keywords(before), WebDavProjection.keywords(after),
    )

    @Test fun deltaTrackingMatchesFullProjectionForLargeLibrariesAndRetainsUnchangedRecords() {
        for(size in listOf(1_000, 20_000, 50_000)) {
            val before = tracked(library(size))
            val after = before.withEntries(KeywordCatalog.observe(before.entries, listOf("新标签")))
            val updated = WebDavProjection.trackKeywords(before, after)
            assertEquals(reference(before, after), updated)
            val original = before.syncReplica.documents.getValue(SyncDomain.KEYWORDS)
            val result = updated.documents.getValue(SyncDomain.KEYWORDS)
            assertEquals(before.syncReplica.clock + 1, updated.clock)
            assertSame(original.records.getValue("@entry/标签0"), result.records.getValue("@entry/标签0"))
        }
    }

    @Test fun deltaTrackingPreservesBootstrapRenamesMovesDeletionsAndCategoryOrder() {
        var before = library(30)
        val changes: List<(KeywordLibrary) -> KeywordLibrary> = listOf(
            { it.createCategory("自定义") },
            { it.editEntry("标签1", "", "自定义") },
            { it.renameCategory("自定义", "重命名") },
            { it.deleteCategory("重命名") },
            { it.copy(entries = it.entries.drop(1)) },
            { it.reorderCategories(it.categories.reversed()) },
            { it.merge(KeywordLibrary(listOf(KeywordEntry("导入标签", "导入译名")))) },
        )
        changes.forEach { transform ->
            val after = transform(before)
            val updated = WebDavProjection.trackKeywords(before, after)
            assertEquals(reference(before, after), updated)
            updated.validate()
            before = after.copy(syncReplica = updated)
        }
    }

    @Test fun useTimeAndKnownObservationsDoNotBootstrapOrModifySyncHistory() {
        val plain = library(20_000)
        for(before in listOf(plain, tracked(plain))) {
            val observed = before.withEntries(KeywordCatalog.observe(before.entries, listOf("标签1")))
            assertSame(before, observed)
            val used = before.withEntries(KeywordCatalog.markUsed(before.entries, listOf("标签1"), 123))
            assertSame(before.syncReplica, WebDavProjection.trackKeywords(before, used))
        }
    }

    @Test fun streamedLocalSnapshotPreservesIdentityVersionsAndUserEdits() {
        val before = tracked(library(20_000).createCategory("保留分类").editEntry("标签1", "", "保留分类"))
        val bytes = ByteArrayOutputStream().also { KeywordLibraryFormat.writeLocal(it, before) }.toByteArray()
        val loaded = KeywordLibraryFormat.readLocal(ByteArrayInputStream(bytes))
        assertEquals(before, loaded)
        loaded.syncReplica.validate()
        assertEquals(before, appJson.decodeFromString<KeywordLibrary>(bytes.toString(Charsets.UTF_8)))
        val output = ByteArrayOutputStream().also { KeywordLibraryFormat.write(it, before) }.toString("UTF-8")
        assertFalse(output.contains("syncReplica"))
        assertEquals(before.entries, KeywordLibraryFormat.decode(output).entries)
    }

    @Test fun streamingSupportsOldArraysBomWhitespaceAndShortReadsButRejectsDamage() {
        val entry = KeywordEntry("旧标签", "旧译名")
        val sources = listOf(appJson.encodeToString(library(2)), appJson.encodeToString(listOf(entry)),
            """[{"src":"原文","dst":"译名","info":"自定义"}]""")
        sources.forEach { source ->
            val bytes = ("\uFEFF \n\t" + source).toByteArray(Charsets.UTF_8)
            val input = object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 1))
            }
            val loaded = KeywordLibraryFormat.readLocal(input)
            val expected = KeywordLibraryFormat.decodeLocal(source)
            assertEquals(expected.copy(syncReplica = loaded.syncReplica), loaded)
        }
        listOf("", "{", "{\"entries\":[]", "{\"entries\":[],\"version\":99}").forEach { damaged ->
            assertTrue(runCatching { KeywordLibraryFormat.readLocal(damaged.byteInputStream()) }.isFailure)
        }
    }

    @Test fun observationsCoalesceSkipKnownAndExplicitFlushIncludesPendingTags() = runTest {
        val known = mutableSetOf("已有")
        val batches = mutableListOf<List<String>>()
        val queue = KeywordObservationQueue(backgroundScope, { it in known }, {
            batches += it.toList(); known += it
        }, { throw AssertionError(it) })
        repeat(20) { queue.enqueue(listOf("已有", "共通", "新增$it")) }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, batches.size)
        assertEquals(21, batches.single().size)
        queue.enqueue(listOf("已有", "共通"))
        queue.flush()
        assertEquals(1, batches.size)
        queue.enqueue(listOf("退出前"))
        queue.flush()
        assertTrue("退出前" in known)
    }

    @Test fun failedObservationIsRetriedWithoutLosingTagsOrCancellingTheConsumer() = runTest {
        var attempts = 0
        var errors = 0
        val seen = mutableSetOf<String>()
        val queue = KeywordObservationQueue(backgroundScope, { it in seen }, {
            attempts++
            if(attempts == 1) error("temporary failure")
            seen += it
        }, { errors++ })
        queue.enqueue(listOf("第一批"))
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(1, errors)
        queue.enqueue(listOf("第二批"))
        advanceTimeBy(1_100); runCurrent()
        assertEquals(setOf("第一批", "第二批"), seen)
        assertEquals(2, attempts)
    }

    @Test fun displayIndexKeepsCustomTranslationsExplicitBlanksAndFallbacks() {
        val index = KeywordDisplayIndex.build(listOf(KeywordEntry("ハーレム"), KeywordEntry("自定义", "译名", translationEdited = true),
            KeywordEntry("ラブコメ", "", translationEdited = true)))
        assertEquals("后宫", index.labels["ハーレム"])
        assertEquals("译名", index.labels["自定义"])
        assertEquals("ラブコメ", index.labels["ラブコメ"])
        assertEquals("", index.entries.getValue("ラブコメ").translation)
    }
}

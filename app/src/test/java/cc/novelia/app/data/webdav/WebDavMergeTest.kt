package cc.novelia.app.data.webdav

import cc.novelia.app.data.catalog.KeywordEntry
import cc.novelia.app.data.catalog.KeywordLibrary
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class WebDavMergeTest {
    private val key = "syosetu/n1"
    private fun replica(device: String, state: LibraryState, domain: SyncDomain) =
        SyncReplica(deviceId = device).track(emptyMap(), WebDavProjection.library(state, setOf(domain))).bind("dataset")
    private fun edit(replica: SyncReplica, before: LibraryState, after: LibraryState, domain: SyncDomain) =
        replica.track(WebDavProjection.library(before, setOf(domain)), WebDavProjection.library(after, setOf(domain)))
    private fun document(replica: SyncReplica, domain: SyncDomain) = replica.documents.getValue(domain)

    @Test fun mergeIsCommutativeAssociativeAndIdempotentAcrossThreeDevices() {
        val original = LibraryState()
        val baseline = replica("a", original, SyncDomain.SETTINGS)
        val a = edit(baseline, original, original.copy(theme = "light"), SyncDomain.SETTINGS)
        val b = edit(baseline.copy(deviceId = "b", clock = 0), original, original.copy(theme = "dark"), SyncDomain.SETTINGS)
        val c = edit(baseline.copy(deviceId = "c", clock = 0), original, original.copy(reducedMotion = true), SyncDomain.SETTINGS)
        val da = document(a, SyncDomain.SETTINGS)
        val db = document(b, SyncDomain.SETTINGS)
        val dc = document(c, SyncDomain.SETTINGS)
        assertEquals(WebDavMerge.merge(da, db), WebDavMerge.merge(db, da))
        assertEquals(WebDavMerge.merge(WebDavMerge.merge(da, db), dc), WebDavMerge.merge(da, WebDavMerge.merge(db, dc)))
        assertEquals(da, WebDavMerge.merge(da, da))
        val merged = WebDavProjection.applyLibrary(original, mapOf(SyncDomain.SETTINGS to WebDavMerge.merge(WebDavMerge.merge(da, db), dc)), setOf(SyncDomain.SETTINGS))
        assertTrue(merged.reducedMotion)
        assertTrue(merged.theme in setOf("light", "dark"))
    }

    @Test fun causalUpdateAfterPartialMergeCannotEraseUnobservedConcurrentVersion() {
        val original = LibraryState(theme = "system")
        val baseline = replica("a", original, SyncDomain.SETTINGS)
        val a = edit(baseline, original, original.copy(theme = "light"), SyncDomain.SETTINGS)
        val b = edit(baseline.copy(deviceId = "b"), original, original.copy(theme = "dark"), SyncDomain.SETTINGS)
        val c = edit(a.copy(deviceId = "c"), original.copy(theme = "light"), original.copy(theme = "system"), SyncDomain.SETTINGS)
        val da = document(a, SyncDomain.SETTINGS); val db = document(b, SyncDomain.SETTINGS); val dc = document(c, SyncDomain.SETTINGS)
        assertEquals(WebDavMerge.merge(WebDavMerge.merge(da, db), dc), WebDavMerge.merge(da, WebDavMerge.merge(db, dc)))
        val register = WebDavMerge.merge(WebDavMerge.merge(da, db), dc).records.getValue("@global").fields.getValue("theme")
        assertEquals(2, register.candidates.size)
    }

    @Test fun deletionDoesNotReviveWhenOldDeviceReturnsAndObservedReAddWorks() {
        val old = LibraryState(books = listOf(SavedBook(BookCard(BookRef.fromKey(key), "书"))))
        val baseline = replica("a", old, SyncDomain.FAVORITES)
        val deleted = edit(baseline, old, old.copy(books = emptyList()), SyncDomain.FAVORITES)
        val merged = WebDavMerge.merge(document(deleted, SyncDomain.FAVORITES), document(baseline, SyncDomain.FAVORITES))
        assertFalse(key in WebDavMerge.materialize(merged))
        val recreated = edit(deleted.merge(merged), old.copy(books = emptyList()), old, SyncDomain.FAVORITES)
        val final = WebDavMerge.merge(document(recreated, SyncDomain.FAVORITES), merged)
        assertTrue(key in WebDavMerge.materialize(final))
    }

    @Test fun concurrentDeleteWinsOverOfflineEdit() {
        val old = LibraryState(notes = listOf(Note("note1", key, "c1", 0, "摘录", "初稿")))
        val baseline = replica("a", old, SyncDomain.NOTES)
        val deleted = edit(baseline, old, old.copy(notes = emptyList()), SyncDomain.NOTES)
        val edited = edit(baseline.copy(deviceId = "b"), old, old.copy(notes = listOf(old.notes[0].copy(text = "离线编辑"))), SyncDomain.NOTES)
        val merged = WebDavMerge.merge(document(deleted, SyncDomain.NOTES), document(edited, SyncDomain.NOTES))
        assertFalse("note1" in WebDavMerge.materialize(merged))
        assertTrue(WebDavMerge.conflicts(merged).isEmpty())
        assertTrue(merged.records.getValue("note1").fields.getValue("text").candidates.any { it.value == JsonPrimitive("离线编辑") })
    }

    @Test fun concurrentNoteBodiesRemainSelectableAndResolutionDominatesBoth() {
        val old = LibraryState(notes = listOf(Note("note1", key, "c1", 0, "摘录", "初稿")))
        val baseline = replica("a", old, SyncDomain.NOTES)
        val a = edit(baseline, old, old.copy(notes = listOf(old.notes[0].copy(text = "甲"))), SyncDomain.NOTES)
        val b = edit(baseline.copy(deviceId = "b"), old, old.copy(notes = listOf(old.notes[0].copy(text = "乙"))), SyncDomain.NOTES)
        val merged = WebDavMerge.merge(document(a, SyncDomain.NOTES), document(b, SyncDomain.NOTES))
        val conflict = WebDavMerge.conflicts(merged).single()
        assertEquals(setOf(JsonPrimitive("甲"), JsonPrimitive("乙")), conflict.candidates.map { it.value }.toSet())
        val chosenIndex = conflict.candidates.indexOfFirst { it.value == JsonPrimitive("甲") }
        val resolved = WebDavMerge.resolve(merged, "note1", "text", chosenIndex, "a", a.clock + 1)
        assertTrue(WebDavMerge.conflicts(WebDavMerge.merge(resolved, merged)).isEmpty())
        assertEquals(JsonPrimitive("甲"), WebDavMerge.materialize(resolved).getValue("note1")["text"])
    }

    @Test fun latestObservedProgressCanMoveBackwardsForRereading() {
        val old = LibraryState(positions = mapOf(key to Position("chapter10", sourceParagraph = 20)))
        val baseline = replica("a", old, SyncDomain.PROGRESS)
        val latest = edit(baseline, old, old.copy(positions = mapOf(key to Position("chapter1", sourceParagraph = 2))), SyncDomain.PROGRESS)
        val merged = WebDavMerge.merge(document(baseline, SyncDomain.PROGRESS), document(latest, SyncDomain.PROGRESS))
        assertEquals("chapter1", WebDavProjection.applyLibrary(old, mapOf(SyncDomain.PROGRESS to merged), setOf(SyncDomain.PROGRESS)).positions.getValue(key).chapterId)
    }

    @Test fun progressConflictPreservesLocalPositionUntilUserSelects() {
        val old = LibraryState(positions = mapOf(key to Position("c0", sourceParagraph = 0)))
        val baseline = replica("a", old, SyncDomain.PROGRESS)
        val local = old.copy(positions = mapOf(key to Position("c1", sourceParagraph = 2)))
        val remote = old.copy(positions = mapOf(key to Position("c2", sourceParagraph = 5)))
        val a = edit(baseline, old, local, SyncDomain.PROGRESS)
        val b = edit(baseline.copy(deviceId = "b"), old, remote, SyncDomain.PROGRESS)
        val merged = WebDavMerge.merge(document(a, SyncDomain.PROGRESS), document(b, SyncDomain.PROGRESS))
        assertEquals(1, WebDavMerge.conflicts(merged).size)
        val result = WebDavProjection.applyLibrary(local, mapOf(SyncDomain.PROGRESS to merged), setOf(SyncDomain.PROGRESS))
        assertEquals(local.positions, result.positions)
    }

    @Test fun firstDomainEditSeedsUnchangedExistingRecords() {
        val old = LibraryState(positions = mapOf(key to Position("c1"), "kakuyomu/2" to Position("c2")))
        val next = old.copy(positions = old.positions + (key to Position("c3")))
        val tracked = SyncReplica(deviceId = "a").track(WebDavProjection.library(old, setOf(SyncDomain.PROGRESS)), WebDavProjection.library(next, setOf(SyncDomain.PROGRESS)))
        assertEquals(setOf(key, "kakuyomu/2"), WebDavMerge.materialize(document(tracked, SyncDomain.PROGRESS)).keys)
        assertEquals("c3", WebDavProjection.applyLibrary(old, tracked.documents, setOf(SyncDomain.PROGRESS)).positions.getValue(key).chapterId)
    }

    @Test fun concurrentKeywordFieldsMergeIndependently() {
        val old = KeywordLibrary(listOf(KeywordEntry("原文", "译文")), categories = listOf("其他", "类别"))
        val projection = WebDavProjection.keywords(old)
        val baseline = SyncReplica(deviceId = "a").track(emptyMap(), projection).bind("dataset")
        val a = baseline.track(projection, WebDavProjection.keywords(old.copy(entries = listOf(old.entries[0].copy(translation = "新译文", translationEdited = true)))))
        val b = baseline.copy(deviceId = "b").track(projection, WebDavProjection.keywords(old.copy(entries = listOf(old.entries[0].copy(category = "类别", categoryEdited = true)))))
        val merged = WebDavMerge.merge(document(a, SyncDomain.KEYWORDS), document(b, SyncDomain.KEYWORDS))
        val result = WebDavProjection.applyKeywords(old, merged)
        assertEquals("新译文", result.entries.single().translation)
        assertEquals("类别", result.entries.single().category)
        assertTrue(result.entries.single().translationEdited)
        assertTrue(result.entries.single().categoryEdited)
    }

    @Test fun recordsFromDifferentDatasetCannotMerge() {
        val first = document(replica("a", LibraryState(), SyncDomain.SETTINGS), SyncDomain.SETTINGS)
        assertThrows(IllegalArgumentException::class.java) { WebDavMerge.merge(first, first.copy(datasetId = "other")) }
    }

    @Test fun invalidHiddenConcurrentCandidateIsRejected() {
        val first = document(replica("a", LibraryState(), SyncDomain.SETTINGS), SyncDomain.SETTINGS)
        val record = first.records.getValue("@global")
        val valid = record.fields.getValue("theme").candidates.single()
        val corrupt = valid.copy(value = JsonPrimitive("unknown"), writer = "b", counter = 1, version = mapOf("b" to 1))
        val altered = first.copy(context = first.context + ("b" to 1), records = first.records + ("@global" to record.copy(
            fields = record.fields + ("theme" to SyncCell(listOf(valid, corrupt))))))
        assertThrows(IllegalArgumentException::class.java) { WebDavMerge.validate(altered) }
    }

    @Test fun initialDefaultKeywordCannotReviveRemoteExplicitEmptyTranslation() {
        val local = KeywordLibrary(listOf(KeywordEntry("原文", "内置译文")), categories = listOf("其他"))
        val remote = local.copy(entries = listOf(KeywordEntry("原文", "", translationEdited = true)))
        val localDocument = SyncReplica(deviceId = "local-device").track(emptyMap(), WebDavProjection.keywords(local)).bind("dataset").documents.getValue(SyncDomain.KEYWORDS)
        val remoteDocument = SyncReplica(deviceId = "remote-device").track(emptyMap(), WebDavProjection.keywords(remote)).bind("dataset").documents.getValue(SyncDomain.KEYWORDS)
        val merged = WebDavMerge.bootstrapKeywords(localDocument, remoteDocument, "local-device")
        val final = WebDavMerge.merge(merged, localDocument)
        val result = WebDavProjection.applyKeywords(local, final)
        assertEquals("", result.entries.single().translation)
        assertTrue(result.entries.single().translationEdited)
    }

    @Test fun firstKeywordJoinPreservesRemoteCategoryChoiceAndLocalExplicitEdits() {
        val local = KeywordLibrary(listOf(KeywordEntry("原文", "本机编辑", translationEdited = true)), categories = listOf("其他", "类别"))
        val remote = local.copy(entries = listOf(KeywordEntry("原文", "默认译文", category = "类别", categoryEdited = true)))
        fun document(device: String, library: KeywordLibrary) = SyncReplica(deviceId = device).track(emptyMap(), WebDavProjection.keywords(library)).bind("dataset").documents.getValue(SyncDomain.KEYWORDS)
        val merged = WebDavMerge.bootstrapKeywords(document("a", local), document("b", remote), "a")
        val result = WebDavProjection.applyKeywords(local, merged)
        assertEquals("本机编辑", result.entries.single().translation)
        assertEquals("类别", result.entries.single().category)
        assertTrue(result.entries.single().translationEdited); assertTrue(result.entries.single().categoryEdited)
    }

    @Test fun pureLocalTombstoneAndMissingCausalContextAreRejected() {
        val deleted = SyncValue(deleted = true, version = mapOf("a" to 1), writer = "a", counter = 1)
        val corruptDocument = SyncDocument(datasetId = "dataset", domain = SyncDomain.SETTINGS,
            records = mapOf("@book/local/file" to SyncRecord("@book/local/file", SyncCell(listOf(deleted)))), context = mapOf("a" to 1))
        assertThrows(IllegalArgumentException::class.java) { WebDavMerge.validate(corruptDocument) }
        val valid = document(replica("a", LibraryState(), SyncDomain.SETTINGS), SyncDomain.SETTINGS)
        assertThrows(IllegalArgumentException::class.java) { WebDavMerge.validate(valid.copy(context = emptyMap())) }
    }

    @Test fun invalidReplicaClockAndMismatchedDomainAreRejected() {
        val original = replica("a", LibraryState(), SyncDomain.SETTINGS)
        original.validate()
        assertThrows(IllegalArgumentException::class.java) { original.copy(clock = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(documents = mapOf(SyncDomain.PROGRESS to document(original, SyncDomain.SETTINGS))).validate()
        }
    }

    @Test fun firstSettingsJoinSelectsPreferredValuesAndAlwaysCombinesBlockedMembers() {
        fun snapshot(device: String, state: LibraryState) = replica(device, state, SyncDomain.SETTINGS).documents.getValue(SyncDomain.SETTINGS)
        val local = LibraryState(theme = "light", blockedTags = setOf("甲"), bookSettings = mapOf(key to cc.novelia.app.data.model.ReaderSettings(fontSize = 25f)))
        val remote = LibraryState(theme = "dark", blockedTags = setOf("乙"))
        val ld = snapshot("a", local); val rd = snapshot("b", remote)
        val preferred = WebDavMerge.bootstrapSettings(ld, rd, "a", preferLocal = false)
        val result = WebDavProjection.applyLibrary(local, mapOf(SyncDomain.SETTINGS to WebDavMerge.merge(preferred, ld)), setOf(SyncDomain.SETTINGS))
        assertEquals("dark", result.theme)
        assertEquals(setOf("甲", "乙"), result.blockedTags)
        // 缺少书目覆盖是未知资料，不是远端删除。
        assertEquals(25f, result.bookSettings.getValue(key).fontSize)
        val localPreferred = WebDavMerge.bootstrapSettings(ld, rd, "a", preferLocal = true)
        assertEquals("light", WebDavProjection.applyLibrary(local, mapOf(SyncDomain.SETTINGS to localPreferred), setOf(SyncDomain.SETTINGS)).theme)
    }

    @Test fun firstSettingsJoinRespectsPreferredExplicitBookOverrideDeletion() {
        val old = LibraryState(bookSettings = mapOf(key to cc.novelia.app.data.model.ReaderSettings(fontSize = 25f)))
        val baseline = replica("a", old, SyncDomain.SETTINGS)
        val deleted = edit(baseline.copy(deviceId = "b"), old, old.copy(bookSettings = emptyMap()), SyncDomain.SETTINGS)
        val chosen = WebDavMerge.bootstrapSettings(document(baseline, SyncDomain.SETTINGS), document(deleted, SyncDomain.SETTINGS), "a", preferLocal = false)
        val final = WebDavMerge.merge(chosen, document(baseline, SyncDomain.SETTINGS))
        assertFalse(key in WebDavProjection.applyLibrary(old, mapOf(SyncDomain.SETTINGS to final), setOf(SyncDomain.SETTINGS)).bookSettings)
    }
}

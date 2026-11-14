package cc.novelia.app.data.webdav

import cc.novelia.app.data.library.*
import cc.novelia.app.data.model.*
import cc.novelia.app.data.updates.withSavedBook
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReadingStatusSyncTest {
    private val ref = BookRef("syosetu", "n-status-sync")
    private val key = ref.key
    private val domain = SyncDomain.FAVORITES
    private val point = Position("last", chapterIndex = 9, chapterCount = 10, paragraphCount = 2)
    private fun initial() = LibraryState().withSavedBook(BookCard(ref, "同步状态", total = 10)).withStableFolderIds()
    private fun projection(state: LibraryState) = WebDavProjection.library(state, setOf(domain))
    private fun baseline(state: LibraryState) = SyncReplica(deviceId = "a").track(emptyMap(), projection(state)).bind("dataset")
    private fun edit(replica: SyncReplica, before: LibraryState, after: LibraryState) = replica.track(projection(before), projection(after))
    private fun doc(replica: SyncReplica) = replica.documents.getValue(domain)
    private fun apply(document: SyncDocument) = WebDavProjection.applyLibrary(LibraryState(), mapOf(domain to document), setOf(domain))
    private fun status(document: SyncDocument) = apply(document).books.single()
    private fun LibraryState.read(end: Boolean = true) = withReadingActivity(ref, readingStatusToken(ref)!!, point, end, true)

    @Test fun concurrentManualWinsOverAutomaticAndAtomicManagementMatchesItsStatus() {
        val old = initial(); val base = baseline(old)
        val manual = edit(base, old, old.withReadingStatus(setOf(key), "想读"))
        val automatic = edit(base.copy(deviceId = "z"), old, old.read())
        val merged = WebDavMerge.merge(doc(manual), doc(automatic))
        assertEquals(merged, WebDavMerge.merge(doc(automatic), doc(manual)))
        assertEquals("想读", status(merged).status)
        assertFalse(status(merged).readingStatusControl!!.automatic)
        assertTrue(WebDavMerge.conflicts(merged).isEmpty())
        assertEquals(2, merged.schemaVersion)
    }

    @Test fun manualIntentSurvivesUnrelatedCausalContextAndHigherAutomaticCounter() {
        val old = initial(); val base = baseline(old)
        val manual = doc(edit(base, old, old.withReadingStatus(setOf(key), "想读")))
        val offline = doc(edit(base.copy(deviceId = "z"), old, old.read()))
        val record = offline.records.getValue(key)
        val value = record.fields.getValue("status").candidates.single()
        val context = WebDavMerge.joinVector(manual.context, offline.context) + ("z" to 100L)
        val late = offline.copy(context = context, records = offline.records + (key to record.copy(fields = record.fields +
            ("status" to SyncCell(listOf(value.copy(counter = 100, version = context)))))))
        assertEquals("想读", status(WebDavMerge.merge(manual, late)).status)
    }

    @Test fun explicitlyRestoringAutomaticSupersedesOldGenerationIncludingOfflineCompletion() {
        val old = initial(); val base = baseline(old)
        val fixed = old.withReadingStatus(setOf(key), "想读")
        val manual = edit(base, old, fixed)
        val restoredState = fixed.withAutomaticReadingStatus(setOf(key))
        val restored = edit(manual, fixed, restoredState)
        val offline = edit(base.copy(deviceId = "z"), old, old.read())
        val merged = WebDavMerge.merge(doc(restored), doc(offline))
        assertEquals("想读", status(merged).status)
        assertTrue(status(merged).readingStatusControl!!.automatic)
        val reading = edit(restored.merge(merged), restoredState, restoredState.read())
        assertEquals("读完", status(WebDavMerge.merge(doc(reading), doc(manual))).status)
    }

    @Test fun concurrentManualStatesRemainSelectableAndResolutionNeverRevivesOldCandidates() {
        val old = initial(); val base = baseline(old)
        val a = doc(edit(base, old, old.withReadingStatus(setOf(key), "想读")))
        val b = doc(edit(base.copy(deviceId = "b"), old, old.withReadingStatus(setOf(key), "读完")))
        val merged = WebDavMerge.merge(a, b)
        val conflict = WebDavMerge.conflicts(merged).single()
        assertEquals("status", conflict.field)
        val index = conflict.candidates.indexOfFirst { WebDavReadingStatus.status(it.value) == "想读" }
        val resolved = WebDavMerge.resolve(merged, key, "status", index, "c", 100)
        assertEquals("想读", status(resolved).status)
        assertFalse(status(resolved).readingStatusControl!!.automatic)
        assertTrue(WebDavMerge.conflicts(WebDavMerge.merge(resolved, merged)).isEmpty())
        val selected = apply(resolved).copy(syncReplica = SyncReplica("c", 100, mapOf(domain to resolved)))
        val restored = selected.withAutomaticReadingStatus(setOf(key))
        val next = doc(edit(selected.syncReplica, selected, restored))
        assertEquals("想读", status(WebDavMerge.merge(next, merged)).status)
        assertTrue(status(WebDavMerge.merge(next, merged)).readingStatusControl!!.automatic)
    }

    @Test fun threeDeviceMergeIsAssociativeCommutativeIdempotentWithManualAndAutoVersions() {
        val old = initial(); val base = baseline(old)
        val a = doc(edit(base, old, old.withReadingStatus(setOf(key), "想读")))
        val b = doc(edit(base.copy(deviceId = "b"), old, old.read()))
        val c = doc(edit(base.copy(deviceId = "c"), old, old.withReadingStatus(setOf(key), "读完")))
        val left = WebDavMerge.merge(WebDavMerge.merge(a, b), c)
        val right = WebDavMerge.merge(a, WebDavMerge.merge(b, c))
        assertEquals(left, right)
        assertEquals(left, WebDavMerge.merge(c, WebDavMerge.merge(b, a)))
        assertEquals(left, WebDavMerge.merge(left, left))
        assertEquals(1, WebDavMerge.conflicts(left).size)
    }

    @Test fun concurrentAutomaticRereadingCannotEraseCompletionButNewCatalogCan() {
        val old = initial(); val base = baseline(old)
        val complete = doc(edit(base, old, old.read()))
        val started = doc(edit(base.copy(deviceId = "z"), old, old.read(false)))
        assertEquals("读完", status(WebDavMerge.merge(complete, started)).status)
        val newCatalog = old.withSavedBook(old.books.single().book.copy(total = 11)).read(false)
        val newer = doc(edit(base.copy(deviceId = "z"), old, newCatalog))
        assertEquals("在读", status(WebDavMerge.merge(complete, newer)).status)
    }

    @Test fun legacyFavoriteTextRemainsProtectedAndCanMergeWithV2() {
        val old = LibraryState(books = listOf(SavedBook(BookCard(ref, "旧书"), status = "读完"))).withStableFolderIds()
        val base = baseline(old)
        assertEquals(1, doc(base).schemaVersion)
        assertEquals("读完", status(doc(base)).status)
        assertNull(status(doc(base)).readingStatusControl)
        val restoredState = old.withAutomaticReadingStatus(setOf(key))
        val v2 = doc(edit(base, old, restoredState))
        val merged = WebDavMerge.merge(doc(base), v2)
        assertEquals(merged, WebDavMerge.merge(v2, doc(base)))
        assertEquals(2, merged.schemaVersion)
        assertTrue(status(merged).readingStatusControl!!.automatic)
    }

    @Test fun restoringAfterConcurrentLegacyAndNewManualEditsObservesBothIntents() {
        val old = LibraryState(books = listOf(SavedBook(BookCard(ref, "旧书"), status = "在读"))).withStableFolderIds()
        val base = baseline(old)
        val legacyEdit = doc(edit(base.copy(deviceId = "b"), old, old.copy(books = listOf(old.books.single().copy(status = "想读")))))
        val modernEdit = doc(edit(base.copy(deviceId = "z"), old, old.withReadingStatus(setOf(key), "读完")))
        val merged = WebDavMerge.merge(legacyEdit, modernEdit)
        val state = apply(merged).copy(syncReplica = SyncReplica("resume-device", 0, mapOf(domain to merged)))
        assertNotNull(state.books.single().readingStatusControl)
        val restored = state.withAutomaticReadingStatus(setOf(key))
        val updated = doc(edit(state.syncReplica, state, restored))
        val final = WebDavMerge.merge(updated, merged)
        assertTrue(status(final).readingStatusControl!!.automatic)
        assertTrue(WebDavMerge.conflicts(final).isEmpty())
    }

    @Test fun generationReplacementRemainsAssociativeAcrossLateOfflineEvents() {
        val old = initial(); val base = baseline(old)
        val fixed = old.withReadingStatus(setOf(key), "想读")
        val manual = edit(base, old, fixed)
        val resumed = fixed.withAutomaticReadingStatus(setOf(key))
        val a = doc(edit(manual, fixed, resumed))
        val b = doc(edit(base.copy(deviceId = "b"), old, old.read()))
        val c = doc(edit(base.copy(deviceId = "c"), old, old.withReadingStatus(setOf(key), "读完")))
        val left = WebDavMerge.merge(WebDavMerge.merge(a, b), c)
        assertEquals(left, WebDavMerge.merge(a, WebDavMerge.merge(b, c)))
        assertEquals(left, WebDavMerge.merge(c, WebDavMerge.merge(b, a)))
        assertEquals("读完", status(left).status)
        assertFalse(status(left).readingStatusControl!!.automatic)
    }

    @Test fun malformedAtomicGroupsAndUnannouncedProtocolUpgradeAreRejected() {
        val document = doc(baseline(initial()))
        assertThrows(IllegalArgumentException::class.java) { WebDavMerge.validate(document.copy(schemaVersion = 1)) }
        val value = WebDavMerge.materialize(document).getValue(key).getValue("status").jsonObject
        assertThrows(IllegalArgumentException::class.java) { WebDavReadingStatus.validate(JsonObject(value - "automatic")) }
        assertThrows(IllegalArgumentException::class.java) { WebDavReadingStatus.validate(JsonObject(value + ("status" to JsonPrimitive("未知")))) }
        assertThrows(IllegalArgumentException::class.java) { WebDavReadingStatus.validate(JsonObject(value + ("completedChapterCount" to JsonPrimitive(12)))) }
    }

    @Test fun syncingProgressDoesNotInventStatusAndGlobalSwitchRemainsDeviceLocal() {
        val old = initial().withAutoReadingStatus(false)
        val remote = LibraryState(positions = mapOf(key to point.copy(chapterCompleted = true)))
        val progress = SyncReplica(deviceId = "b").track(emptyMap(), WebDavProjection.library(remote, setOf(SyncDomain.PROGRESS))).bind("dataset")
        val applied = WebDavProjection.applyLibrary(old, progress.documents, setOf(SyncDomain.PROGRESS))
        assertEquals("想读", applied.books.single().status)
        assertFalse(applied.autoReadingStatus)
        val settings = WebDavProjection.library(old, setOf(SyncDomain.SETTINGS), true)
        assertFalse(settings.getValue(SyncDomain.SETTINGS).getValue("@global").containsKey("autoReadingStatus"))
        val local = LibraryState().withSavedBook(BookCard(BookRef("local", "doc"), "本地文件"))
        assertFalse(projection(local).getValue(domain).containsKey("local/doc"))
    }
}

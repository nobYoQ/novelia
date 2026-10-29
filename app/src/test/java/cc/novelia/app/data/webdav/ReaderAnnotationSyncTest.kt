package cc.novelia.app.data.webdav

import cc.novelia.app.data.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReaderAnnotationSyncTest {
    private val key = "syosetu/n1"
    private fun documents(state: LibraryState, devices: Boolean = false) = SyncReplica(deviceId = "reader-test")
        .track(emptyMap(), WebDavProjection.library(state, includeDevicePreferences = devices)).bind("dataset").documents

    @Test fun autoReadPaceSyncsWithoutAnyRunningStateAndLegacyRecordsKeepLocalValues() {
        val settings = ReaderSettings(autoReadSpeed = 45f, autoReadIntervalSeconds = 25)
        val docs = documents(LibraryState(reader = settings, bookSettings = mapOf(key to settings.copy(autoReadSpeed = 10f))))
        docs.values.forEach { WebDavProjection.validateRecords(it) }
        val restored = WebDavProjection.applyLibrary(LibraryState(), docs, setOf(SyncDomain.SETTINGS))
        assertEquals(45f, restored.reader.autoReadSpeed)
        assertEquals(25, restored.reader.autoReadIntervalSeconds)
        assertEquals(10f, restored.bookSettings.getValue(key).autoReadSpeed)
        val legacy = docs.getValue(SyncDomain.SETTINGS).let { doc -> doc.copy(records = doc.records.mapValues { (_, record) ->
            record.copy(fields = record.fields - setOf("autoReadSpeed", "autoReadIntervalSeconds"))
        }) }
        WebDavProjection.validateRecords(legacy)
        assertEquals(45f, WebDavProjection.applyLibrary(LibraryState(reader = settings), mapOf(SyncDomain.SETTINGS to legacy), setOf(SyncDomain.SETTINGS)).reader.autoReadSpeed)
        for(value in listOf(4, 121)) assertThrows(IllegalArgumentException::class.java) {
            WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "autoReadIntervalSeconds", JsonPrimitive(value))
        }
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "autoReadSpeed", JsonPrimitive(0)) }
    }

    @Test fun highlightsWithoutNoteTextSyncAndDeletingNotesDoesNotDeleteBookmarks() {
        val highlight = Note("highlight", key, "one", 3, "选文", "", bookmarked = false, selection = NoteSelection("gpt", start = 2, end = 4))
        val bookmark = Note("bookmark", key, "one", 3, "整个段落", "")
        val state = LibraryState(notes = listOf(highlight, bookmark))
        val docs = documents(state)
        docs.values.forEach { WebDavMerge.validate(it, "dataset"); WebDavProjection.validateRecords(it) }
        val restored = WebDavProjection.applyLibrary(LibraryState(), docs, setOf(SyncDomain.BOOKMARKS, SyncDomain.NOTES))
        assertEquals(state.notes.toSet(), restored.notes.toSet())
        val emptyNotes = documents(LibraryState(notes = listOf(bookmark)))
        assertEquals(listOf(bookmark), WebDavProjection.applyLibrary(restored, emptyNotes, setOf(SyncDomain.NOTES)).notes)
        assertEquals(restored.notes.toSet(), WebDavProjection.applyLibrary(restored, emptyNotes, setOf(SyncDomain.BOOKMARKS)).notes.toSet())
    }

    @Test fun legacyNotesWithoutSelectionsRemainReadableAndMalformedSelectionsAreRejected() {
        val bookmark = Note("bookmark", key, "one", 3, "旧摘录", "旧笔记")
        val docs = documents(LibraryState(notes = listOf(bookmark))).mapValues { (_, doc) ->
            doc.copy(records = doc.records.mapValues { (_, record) -> record.copy(fields = record.fields - "selection") })
        }
        docs.values.forEach { WebDavProjection.validateRecords(it) }
        assertEquals(bookmark, WebDavProjection.applyLibrary(LibraryState(), docs, setOf(SyncDomain.NOTES, SyncDomain.BOOKMARKS)).notes.single())
        val invalid = buildJsonObject { put("source", "gpt"); put("occurrence", 0); put("start", 5); put("end", 2) }
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.NOTES, "note", "selection", invalid) }
    }

    @Test fun marginsSyncWhileTheSidebarUsesOptionalDevicePreferences() {
        val settings = ReaderSettings(horizontalMargin = 48f, verticalMargin = 0f, showWideToc = false, orientation = "landscape")
        val remote = LibraryState(reader = settings, bookSettings = mapOf(key to settings.copy(horizontalMargin = 8f)))
        val docs = documents(remote, devices = true)
        docs.values.forEach { WebDavProjection.validateRecords(it) }
        val portable = WebDavProjection.applyLibrary(LibraryState(), docs, setOf(SyncDomain.SETTINGS))
        assertEquals(48f, portable.reader.horizontalMargin)
        assertEquals(8f, portable.bookSettings.getValue(key).horizontalMargin)
        assertTrue(portable.reader.showWideToc)
        assertEquals("system", portable.reader.orientation)
        val withDevices = WebDavProjection.applyLibrary(LibraryState(), docs, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true)
        assertEquals(settings, withDevices.reader)
        val legacy = documents(remote).getValue(SyncDomain.SETTINGS).let { doc -> doc.copy(records = doc.records.mapValues { (_, record) ->
            record.copy(fields = record.fields - setOf("horizontalMargin", "verticalMargin"))
        }) }
        WebDavProjection.validateRecords(legacy)
        val local = LibraryState(reader = settings.copy(horizontalMargin = 12f))
        assertEquals(12f, WebDavProjection.applyLibrary(local, mapOf(SyncDomain.SETTINGS to legacy), setOf(SyncDomain.SETTINGS)).reader.horizontalMargin)
        val group = WebDavProjection.library(remote, includeDevicePreferences = true).getValue(SyncDomain.SETTINGS).getValue("@reader").getValue("devicePreferences") as JsonObject
        WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "devicePreferences", JsonObject(group - setOf("showWideToc", "tapZoneMap", "orientation")))
        assertThrows(IllegalArgumentException::class.java) {
            WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "devicePreferences", JsonObject(group + ("orientation" to JsonPrimitive("invalid"))))
        }
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "horizontalMargin", JsonPrimitive(65)) }
    }
}

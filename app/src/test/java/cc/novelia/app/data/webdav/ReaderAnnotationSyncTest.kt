package cc.novelia.app.data.webdav

import cc.novelia.app.data.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReaderAnnotationSyncTest {
    private val key = "syosetu/n1"
    private fun documents(state: LibraryState, devices: Boolean = false) = SyncReplica(deviceId = "reader-test")
        .track(emptyMap(), WebDavProjection.library(state, includeDevicePreferences = devices)).bind("dataset").documents

    @Test fun partialLinePreferenceSyncsAndLegacyRecordsKeepLocalChoices() {
        val hidden = ReaderSettings(rowShowPartialLines = false)
        val remote = LibraryState(reader = hidden, bookSettings = mapOf(key to ReaderSettings()))
        val docs = documents(remote)
        docs.values.forEach { WebDavProjection.validateRecords(it) }
        val local = LibraryState(bookSettings = mapOf(key to hidden))
        val restored = WebDavProjection.applyLibrary(local, docs, setOf(SyncDomain.SETTINGS))
        assertFalse(restored.reader.rowShowPartialLines)
        assertTrue(restored.bookSettings.getValue(key).rowShowPartialLines)
        val legacy = docs.getValue(SyncDomain.SETTINGS).let { doc -> doc.copy(records = doc.records.mapValues { (_, record) ->
            record.copy(fields = record.fields - "rowShowPartialLines")
        }) }
        WebDavProjection.validateRecords(legacy)
        val legacyRestored = WebDavProjection.applyLibrary(local, mapOf(SyncDomain.SETTINGS to legacy), setOf(SyncDomain.SETTINGS))
        assertTrue(legacyRestored.reader.rowShowPartialLines)
        assertFalse(legacyRestored.bookSettings.getValue(key).rowShowPartialLines)
        assertThrows(IllegalArgumentException::class.java) {
            WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "rowShowPartialLines", JsonPrimitive("false"))
        }
    }

    @Test fun partialLinePreferenceSyncsIndependentModesAndAcceptsLegacySnapshots() {
        val reader = ReaderSettings(rowShowPartialLines = false).withEInkMode(true).copy(rowShowPartialLines = true)
        val docs = documents(LibraryState(reader = reader), devices = true)
        docs.values.forEach { WebDavProjection.validateRecords(it) }
        val restored = WebDavProjection.applyLibrary(LibraryState(), docs, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true).reader
        assertTrue(restored.rowShowPartialLines)
        assertFalse(restored.withEInkMode(false).rowShowPartialLines)

        val projection = WebDavProjection.library(LibraryState(reader = reader), includeDevicePreferences = true)
        val records = projection.getValue(SyncDomain.SETTINGS).toMutableMap()
        val group = records.getValue("@reader").getValue("devicePreferences").jsonObject
        val legacyGroup = JsonObject(group.mapValues { (field, value) ->
            if(field in setOf("activeModeSettings", "beforeEInk", "eInkPreferences") && value is JsonObject)
                JsonObject(value - "rowShowPartialLines") else value
        })
        records["@reader"] = JsonObject((records.getValue("@reader") - "rowShowPartialLines") + ("devicePreferences" to legacyGroup))
        val legacyDocs = SyncReplica(deviceId = "old").track(emptyMap(), mapOf(SyncDomain.SETTINGS to records)).bind("dataset").documents
        WebDavProjection.validateRecords(legacyDocs.getValue(SyncDomain.SETTINGS))
        val local = ReaderSettings(rowShowPartialLines = true).withEInkMode(true).copy(rowShowPartialLines = false)
        val legacyRestored = WebDavProjection.applyLibrary(LibraryState(reader = local), legacyDocs, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true).reader
        assertFalse(legacyRestored.rowShowPartialLines)
        assertTrue(legacyRestored.withEInkMode(false).rowShowPartialLines)
    }

    @Test fun unlimitedTimerSyncsInDefaultAndBookPreferences() {
        val custom = ReaderSettings(speechMinutes = 0)
        val docs = documents(LibraryState(reader = custom, bookSettings = mapOf(key to custom)))
        docs.values.forEach { WebDavProjection.validateRecords(it) }
        val restored = WebDavProjection.applyLibrary(LibraryState(), docs, setOf(SyncDomain.SETTINGS))
        assertEquals(custom, restored.reader)
        assertEquals(custom, restored.bookSettings.getValue(key))
        assertThrows(IllegalArgumentException::class.java) {
            WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "speechMinutes", JsonPrimitive(-1))
        }
    }

    @Test fun customIndentSyncsWithOptionalLegacyFields() {
        val custom = ReaderSettings(indentSize = 7)
        val docs = documents(LibraryState(reader = custom, bookSettings = mapOf(key to custom)))
        docs.values.forEach { WebDavProjection.validateRecords(it) }
        val restored = WebDavProjection.applyLibrary(LibraryState(), docs, setOf(SyncDomain.SETTINGS))
        assertEquals(custom, restored.reader)
        assertEquals(custom, restored.bookSettings.getValue(key))
        val legacy = docs.getValue(SyncDomain.SETTINGS).let { doc -> doc.copy(records = doc.records.mapValues { (_, record) ->
            record.copy(fields = record.fields - "indentSize")
        }) }
        WebDavProjection.validateRecords(legacy)
        val local = LibraryState(reader = custom.copy(indentSize = 1))
        assertEquals(1, WebDavProjection.applyLibrary(local, mapOf(SyncDomain.SETTINGS to legacy), setOf(SyncDomain.SETTINGS)).reader.indentSize)
        for((field, value) in listOf("indentSize" to -1, "indentSize" to 21)) {
            assertThrows(IllegalArgumentException::class.java) {
                WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", field, JsonPrimitive(value))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "indentSize", JsonPrimitive(1.5))
        }
    }

    @Test fun legacyActiveAndInactiveModesWithoutIndentSizeRemainValid() {
        val reader = ReaderSettings(indentSize = 1).withEInkMode(true).copy(indentSize = 7)
        val projection = WebDavProjection.library(LibraryState(reader = reader), includeDevicePreferences = true)
        val group = projection.getValue(SyncDomain.SETTINGS).getValue("@reader").getValue("devicePreferences").jsonObject
        val legacy = JsonObject(group.mapValues { (field, value) ->
            if(field in setOf("activeModeSettings", "beforeEInk", "eInkPreferences") && value is JsonObject) JsonObject(value - "indentSize") else value
        })
        WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "devicePreferences", legacy)
        val records = projection.getValue(SyncDomain.SETTINGS).toMutableMap()
        records["@reader"] = JsonObject((records.getValue("@reader") - "indentSize") + ("devicePreferences" to legacy))
        val docs = SyncReplica(deviceId = "old").track(emptyMap(), mapOf(SyncDomain.SETTINGS to records)).bind("dataset").documents
        WebDavProjection.validateRecords(docs.getValue(SyncDomain.SETTINGS))
        val restored = WebDavProjection.applyLibrary(LibraryState(reader = reader), docs, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true).reader
        assertEquals(7, restored.indentSize)
        assertEquals(1, restored.withEInkMode(false).indentSize)
    }

    @Test fun epubDisplaySwitchesSyncAndLegacyRecordsPreserveLocalChoices() {
        val custom = ReaderSettings(showEpubRuby = false, epubFootnotePopup = false, showEpubStyles = false)
        val remote = LibraryState(reader = custom, bookSettings = mapOf(key to custom.copy(showEpubRuby = true)))
        val docs = documents(remote)
        docs.values.forEach { WebDavProjection.validateRecords(it) }
        val restored = WebDavProjection.applyLibrary(LibraryState(), docs, setOf(SyncDomain.SETTINGS))
        assertEquals(custom, restored.reader)
        assertEquals(remote.bookSettings, restored.bookSettings)
        val fields = setOf("showEpubRuby", "epubFootnotePopup", "showEpubStyles")
        val legacy = docs.getValue(SyncDomain.SETTINGS).let { doc -> doc.copy(records = doc.records.mapValues { (_, record) -> record.copy(fields = record.fields - fields) }) }
        WebDavProjection.validateRecords(legacy)
        assertEquals(custom, WebDavProjection.applyLibrary(LibraryState(reader = custom), mapOf(SyncDomain.SETTINGS to legacy), setOf(SyncDomain.SETTINGS)).reader)
        fields.forEach { field -> assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", field, JsonPrimitive("false")) } }
    }

    @Test fun replacementRulesSyncAndOldRecordsPreserveLocalRules() {
        val settings = ReaderSettings(textReplacements = mapOf("原译名" to "新译名"))
        val docs = documents(LibraryState(bookSettings = mapOf(key to settings)))
        docs.values.forEach { WebDavProjection.validateRecords(it) }
        assertEquals(settings, WebDavProjection.applyLibrary(LibraryState(), docs, setOf(SyncDomain.SETTINGS)).bookSettings.getValue(key))
        val legacy = docs.getValue(SyncDomain.SETTINGS).let { doc -> doc.copy(records = doc.records.mapValues { (_, record) ->
            record.copy(fields = record.fields - setOf("textReplacements", "textReplacementsEnabled"))
        }) }
        WebDavProjection.validateRecords(legacy)
        assertEquals(settings, WebDavProjection.applyLibrary(LibraryState(bookSettings = mapOf(key to settings)), mapOf(SyncDomain.SETTINGS to legacy), setOf(SyncDomain.SETTINGS)).bookSettings.getValue(key))
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "textReplacements", buildJsonObject { put("", "bad") }) }
    }

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

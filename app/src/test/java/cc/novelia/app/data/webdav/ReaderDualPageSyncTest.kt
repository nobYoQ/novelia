package cc.novelia.app.data.webdav

import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.ReaderSettings
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReaderDualPageSyncTest {
    @Test fun dualPageFollowsDevicePreferencesAndLegacyModeGroupsRemainValid() {
        val enabled = ReaderSettings(paginationMode = "auto", horizontalDualPage = true)
        val source = LibraryState(reader = enabled, bookSettings = mapOf("syosetu/example" to enabled))
        val projection = WebDavProjection.library(source, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true)
        val docs = SyncReplica(deviceId = "dual-page-test").track(emptyMap(), projection).bind("dataset").documents
        WebDavProjection.validateRecords(docs.getValue(SyncDomain.SETTINGS))
        val target = LibraryState()
        assertFalse(WebDavProjection.applyLibrary(target, docs, setOf(SyncDomain.SETTINGS)).reader.horizontalDualPage)
        val restored = WebDavProjection.applyLibrary(target, docs, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true)
        assertTrue(restored.reader.horizontalDualPage)
        assertTrue(restored.bookSettings.getValue("syosetu/example").horizontalDualPage)
        val device = projection.getValue(SyncDomain.SETTINGS).getValue("@reader").getValue("devicePreferences").jsonObject
        val legacy = JsonObject((device - "horizontalDualPage").mapValues { (key, value) ->
            if(key == "activeModeSettings") JsonObject(value.jsonObject - "horizontalDualPage") else value
        })
        WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "devicePreferences", legacy)
    }
}

package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class StickerPackSettingsTest {
    @Test fun oldDataUsesTheExistingPackAndSelectedIdsSurviveSettingsAndLibraryBackups() {
        assertEquals(DEFAULT_STICKER_PACK_ID, appJson.decodeFromString<LibraryState>("{}").stickerPackId)
        assertEquals(DEFAULT_STICKER_PACK_ID, appJson.decodeFromString<SettingsBackup>("{}").stickerPackId)
        for(id in listOf(DEFAULT_STICKER_PACK_ID, "future-pack")) {
            val library = LibraryState(stickerPackId = id)
            assertEquals(id, appJson.decodeFromString<LibraryState>(appJson.encodeToString(library)).stickerPackId)
            val backup = SettingsBackup(stickerPackId = id)
            assertEquals(backup, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)))
        }
    }

    @Test fun packSelectionIsLocalAndDoesNotChangeTheWebDavProtocol() {
        val before = LibraryState()
        val after = before.copy(stickerPackId = "another-pack")
        assertTrue(WebDavProjection.affectedLibraryDomains(before, after).isEmpty())
        assertEquals(WebDavProjection.library(before), WebDavProjection.library(after))
    }
}

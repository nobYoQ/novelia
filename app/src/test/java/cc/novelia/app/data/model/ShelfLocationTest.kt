package cc.novelia.app.data.model

import cc.novelia.app.data.network.cloudFolderChoices
import cc.novelia.app.data.storage.LibraryStateCodec
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.WebDavProjection
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ShelfLocationTest {
    private fun library(): LibraryState = LibraryState().createShelfFolder("待读").let {
        it.copy(lastShelfLocation = ShelfLocation(tab = 2, localFolderId = it.folderIds.getValue("待读"), cloud = mapOf(
            "alice" to CloudShelfLocation(kind = 1, folderId = "wenku-folder"),
            "bob" to CloudShelfLocation(kind = 0, folderId = "web-folder"),
        )))
    }

    @Test fun oldLibrariesEnableRememberingAndSettingsBackupPreservesTheSwitch() {
        val legacy = appJson.decodeFromString<LibraryState>("{}")
        assertTrue(legacy.rememberLastShelfFolder)
        assertEquals(ShelfLocation(), legacy.restoredShelfLocation())
        assertTrue(appJson.decodeFromString<SettingsBackup>("{}").rememberLastShelfFolder)
        val backup = SettingsBackup(rememberLastShelfFolder = false)
        assertFalse(appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)).rememberLastShelfFolder)
    }

    @Test fun diskRestartRestoresTheSectionAndFoldersButDisabledStartsAtDefaults() {
        val directory = Files.createTempDirectory("shelf-location").toFile()
        try {
            fun codec() = LibraryStateCodec(directory, { it.readText(Charsets.UTF_8) }, { file, text -> file.writeText(text, Charsets.UTF_8) })
            for(enabled in listOf(true, false)) {
                val state = library().copy(rememberLastShelfFolder = enabled)
                val restored = codec().decode(codec().encode(state))
                assertEquals(state, restored)
                assertEquals(if(enabled) state.lastShelfLocation else ShelfLocation(), restored.restoredShelfLocation())
                assertEquals(if(enabled) CloudShelfLocation(1, "wenku-folder") else CloudShelfLocation(), restored.restoredCloudShelfLocation("alice"))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun localFolderRenameKeepsSelectionAndDeletionFallsBackToAll() {
        val state = library()
        val renamed = state.renameShelfFolder("待读", "下次阅读")
        assertEquals(renamed.folderIds.getValue("下次阅读"), renamed.restoredShelfLocation().localFolderId)
        val deleted = renamed.deleteShelfFolder("下次阅读")
        assertNull(deleted.restoredShelfLocation().localFolderId)
        assertNull(deleted.createShelfFolder("待读").restoredShelfLocation().localFolderId)
    }

    @Test fun cloudFoldersStayWithinTheirAccountAndMissingFoldersUseTheNormalDefault() {
        val state = library()
        assertEquals(CloudShelfLocation(1, "wenku-folder"), state.restoredCloudShelfLocation("alice"))
        assertEquals(CloudShelfLocation(0, "web-folder"), state.restoredCloudShelfLocation("bob"))
        assertEquals(CloudShelfLocation(), state.restoredCloudShelfLocation("guest"))
        val choices = cloudFolderChoices(listOf(Folder("default", "默认"), Folder("wenku-folder", "文库")))
        assertEquals("wenku-folder", state.restoredCloudShelfLocation("alice").resolveFolder(choices)?.id)
        assertEquals(choices.first(), CloudShelfLocation(1, "deleted").resolveFolder(choices))
        assertEquals("default", CloudShelfLocation(1, "deleted").resolveFolder(choices.drop(1).take(1))?.id)
        assertNull(CloudShelfLocation().resolveFolder(emptyList()))
    }

    @Test fun locationIsDeviceLocalAndMalformedTabsFallBackSafely() {
        val state = library()
        val changed = state.copy(rememberLastShelfFolder = false, lastShelfLocation = ShelfLocation())
        assertTrue(WebDavProjection.affectedLibraryDomains(state, changed).isEmpty())
        assertEquals(WebDavProjection.library(state), WebDavProjection.library(changed))
        val invalid = state.copy(lastShelfLocation = ShelfLocation(tab = 99, cloud = mapOf("alice" to CloudShelfLocation(-1, "bad"))))
        assertEquals(0, invalid.restoredShelfLocation().tab)
        assertEquals(CloudShelfLocation(), invalid.restoredCloudShelfLocation("alice"))
    }
}

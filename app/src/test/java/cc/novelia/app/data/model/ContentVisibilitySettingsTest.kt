package cc.novelia.app.data.model

import cc.novelia.app.data.backup.mergeLibraryBackup
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ContentVisibilitySettingsTest {
    @Test fun missingPreferencesKeepExistingBehaviorAndAllCombinationsRoundTrip() {
        val legacy = appJson.decodeFromString<LibraryState>("{}")
        assertFalse(legacy.hideUnavailableComments || legacy.hideLocalFavorites || legacy.hideCloudFavorites)
        val oldBackup = appJson.decodeFromString<SettingsBackup>("{}")
        assertFalse(oldBackup.hideUnavailableComments || oldBackup.hideLocalFavorites || oldBackup.hideCloudFavorites)
        for(mask in 0..7) {
            val state = legacy.copy(hideUnavailableComments = mask and 1 != 0,
                hideLocalFavorites = mask and 2 != 0, hideCloudFavorites = mask and 4 != 0)
            assertEquals(state, appJson.decodeFromString<LibraryState>(appJson.encodeToString(state)))
            val backup = SettingsBackup(hideUnavailableComments = state.hideUnavailableComments,
                hideLocalFavorites = state.hideLocalFavorites, hideCloudFavorites = state.hideCloudFavorites)
            assertEquals(backup, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)))
        }
    }

    @Test fun libraryBackupRestoresNewPreferencesIntoEmptyLibrariesAndKeepsExistingPreferences() {
        val imported = LibraryState(hideUnavailableComments = true, hideLocalFavorites = true, hideCloudFavorites = true)
        val empty = mergeLibraryBackup(LibraryState(), imported)
        assertTrue(empty.hideUnavailableComments && empty.hideLocalFavorites && empty.hideCloudFavorites)
        val current = LibraryState(books = listOf(SavedBook(BookCard(BookRef("syosetu", "n1"), "本地收藏"))))
        val merged = mergeLibraryBackup(current, imported)
        assertFalse(merged.hideUnavailableComments || merged.hideLocalFavorites || merged.hideCloudFavorites)
    }
}

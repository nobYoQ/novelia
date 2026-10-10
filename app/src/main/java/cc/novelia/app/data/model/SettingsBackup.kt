package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class SettingsBackup(val version: Int = 1, val reader: ReaderSettings = ReaderSettings(), val theme: String = "system", val reducedMotion: Boolean = false, val blockedBooks: Set<String> = emptySet(), val blockedTags: Set<String> = emptySet(), val blockedAuthors: Set<String> = emptySet(), val blockedUsers: Set<String> = emptySet(), val hideNovelComments: Boolean = false, val wifiOnly: Boolean = false, val autoCollapseCloudFilters: Boolean = true, val autoSaveCloudFavoritesLocally: Boolean = true, val clipboardLinkHints: Boolean = true, val keywordLimit: Int? = null,
    val deleteDownloadAfterImport: Boolean = false, val deleteLocalCopyOnShelfRemoval: Boolean = false,
    val autoCheckAppUpdates: Boolean = true, val squareCorners: Boolean = false)

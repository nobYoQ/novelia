package cc.novelia.app.data.model

import cc.novelia.app.data.library.withAutoReadingStatus
import kotlinx.serialization.Serializable

@Serializable data class SettingsBackup(val version: Int = 1, val reader: ReaderSettings = ReaderSettings(), val theme: String = "system", val reducedMotion: Boolean = false, val blockedBooks: Set<String> = emptySet(), val blockedTags: Set<String> = emptySet(), val blockedAuthors: Set<String> = emptySet(), val blockedUsers: Set<String> = emptySet(), val hideNovelComments: Boolean = false, val wifiOnly: Boolean = false, val autoCollapseCloudFilters: Boolean = true, val autoSaveCloudFavoritesLocally: Boolean = true, val clipboardLinkHints: Boolean = true, val keywordLimit: Int? = null,
    val deleteDownloadAfterImport: Boolean = false, val deleteLocalCopyOnShelfRemoval: Boolean = false,
    val autoCheckAppUpdates: Boolean = true, val squareCorners: Boolean = false, val amoledBlack: Boolean = false,
    val autoSaveLocalFavoritesToCloud: Boolean = false,
    val discoverWenkuGrid: Boolean = true,
    val rememberSavedSearchPage: Boolean = false,
    val stickerPackId: String = DEFAULT_STICKER_PACK_ID,
    val shelfGrid: Boolean = false, val localFilesGrid: Boolean = false, val cloudShelfGrid: Boolean = false,
    val shelfGridDensity: ShelfGridDensity = ShelfGridDensity.Auto,
    val shelfTapToRead: Boolean = false,
    val rememberLastShelfFolder: Boolean = true,
    val autoReadingStatus: Boolean = true,
    val edgeToolbar: EdgeToolbarSettings = EdgeToolbarSettings(),
    val hideUnavailableComments: Boolean = false,
    val hideLocalFavorites: Boolean = false, val hideCloudFavorites: Boolean = false,
    val forumDefaultCategory: String? = null, val forumSkipPinned: Boolean = false,
    val forumDefaultSort: String = "active", val forumDefaultTagIds: Map<String, Long> = emptyMap(),
    val ttsEnginePackage: String? = null,
    val themePresets: ThemePresets? = null,
    val readerThemePresets: Map<String, ReaderThemePalette>? = null)

internal fun LibraryState.settingsBackup() = SettingsBackup(
    readerThemePresets = readerThemePresets,
    reader = reader, theme = theme, themePresets = themePresets, reducedMotion = reducedMotion, squareCorners = squareCorners,
    amoledBlack = amoledBlack, stickerPackId = stickerPackId, discoverWenkuGrid = discoverWenkuGrid,
    rememberSavedSearchPage = rememberSavedSearchPage, shelfGrid = shelfGrid, localFilesGrid = localFilesGrid,
    cloudShelfGrid = cloudShelfGrid, shelfGridDensity = shelfGridDensity, shelfTapToRead = shelfTapToRead, rememberLastShelfFolder = rememberLastShelfFolder,
    autoReadingStatus = autoReadingStatus, edgeToolbar = edgeToolbar.normalized(), blockedBooks = blockedBooks,
    blockedTags = blockedTags, blockedAuthors = blockedAuthors, blockedUsers = blockedUsers, hideNovelComments = hideNovelComments,
    hideUnavailableComments = hideUnavailableComments, hideLocalFavorites = hideLocalFavorites, hideCloudFavorites = hideCloudFavorites,
    forumDefaultCategory = forumDefaultCategory, forumSkipPinned = forumSkipPinned, ttsEnginePackage = ttsEnginePackage,
    forumDefaultSort = forumDefaultSort, forumDefaultTagIds = forumDefaultTagIds,
    wifiOnly = wifiOnly, autoCollapseCloudFilters = autoCollapseCloudFilters, autoSaveCloudFavoritesLocally = autoSaveCloudFavoritesLocally,
    autoSaveLocalFavoritesToCloud = autoSaveLocalFavoritesToCloud,
    clipboardLinkHints = clipboardLinkHints, autoCheckAppUpdates = autoCheckAppUpdates, keywordLimit = keywordLimit,
    deleteDownloadAfterImport = deleteDownloadAfterImport, deleteLocalCopyOnShelfRemoval = deleteLocalCopyOnShelfRemoval,
)

internal fun SettingsBackup.requireValid() {
    themePresets?.requireValid()
    readerThemePresets?.requireValidReaderThemes()
    require(edgeToolbar.valid && version == 1 && theme in APP_THEME_MODES &&
        (keywordLimit == null || keywordLimit > 0) && reader.modeConfigurations().all { r ->
            r.fontSize in ReaderSettings.FONT_SIZE_RANGE && r.lineHeight in ReaderSettings.LINE_HEIGHT_RANGE &&
                r.paragraphSpacing in ReaderSettings.PARAGRAPH_SPACING_RANGE && r.width in 300f..900f &&
                r.engines.toSet() == setOf("sakura", "gpt", "youdao") && r.theme in ReaderSettings.THEMES &&
                r.customColors.valid && r.validFont && r.validNightReading && r.horizontalMargin in 0f..64f &&
                r.verticalMargin in 0f..64f && r.orientation in ReaderSettings.ORIENTATIONS && r.validAutoRead &&
                r.validIndentSize && r.validSpeechTimer && r.validTextReplacements && r.validReadingStatusItems && r.validToolbarTransparency
        }) { "普通设置文件无效" }
}

internal fun SettingsBackup.applyTo(state: LibraryState): LibraryState = state.copy(
    readerThemePresets = readerThemePresets ?: state.readerThemePresets,
    reader = reader, theme = theme, themePresets = themePresets ?: state.themePresets,
    reducedMotion = reducedMotion, squareCorners = squareCorners, amoledBlack = amoledBlack, stickerPackId = stickerPackId,
    discoverWenkuGrid = discoverWenkuGrid, rememberSavedSearchPage = rememberSavedSearchPage, shelfGrid = shelfGrid,
    localFilesGrid = localFilesGrid, cloudShelfGrid = cloudShelfGrid, shelfGridDensity = shelfGridDensity, shelfTapToRead = shelfTapToRead,
    rememberLastShelfFolder = rememberLastShelfFolder, edgeToolbar = edgeToolbar, blockedBooks = blockedBooks,
    blockedTags = blockedTags, blockedAuthors = blockedAuthors, blockedUsers = blockedUsers, hideNovelComments = hideNovelComments,
    hideUnavailableComments = hideUnavailableComments, hideLocalFavorites = hideLocalFavorites, hideCloudFavorites = hideCloudFavorites,
    forumDefaultCategory = forumDefaultCategory, forumSkipPinned = forumSkipPinned, ttsEnginePackage = ttsEnginePackage,
    forumDefaultSort = forumDefaultSort, forumDefaultTagIds = forumDefaultTagIds,
    wifiOnly = wifiOnly, autoCollapseCloudFilters = autoCollapseCloudFilters, autoSaveCloudFavoritesLocally = autoSaveCloudFavoritesLocally,
    autoSaveLocalFavoritesToCloud = autoSaveLocalFavoritesToCloud,
    clipboardLinkHints = clipboardLinkHints, autoCheckAppUpdates = autoCheckAppUpdates, keywordLimit = keywordLimit,
    deleteDownloadAfterImport = deleteDownloadAfterImport, deleteLocalCopyOnShelfRemoval = deleteLocalCopyOnShelfRemoval,
).withAutoReadingStatus(autoReadingStatus)

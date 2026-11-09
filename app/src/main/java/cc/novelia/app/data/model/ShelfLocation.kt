package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

/** 本地收藏夹保存稳定 ID，改名后仍能恢复；云端 ID 只在所属账号内使用。 */
@Serializable data class ShelfLocation(
    val tab: Int = 0,
    val localFolderId: String? = null,
    val cloud: Map<String, CloudShelfLocation> = emptyMap(),
    val medium: BookMedium = BookMedium.Novel,
    val comicFolderId: String? = null,
)

@Serializable data class CloudShelfLocation(val kind: Int = 0, val folderId: String = "") {
    fun resolveFolder(choices: List<Folder>): Folder? = choices.find { it.id == folderId } ?: choices.firstOrNull()
}

fun LibraryState.restoredShelfLocation(): ShelfLocation = if(rememberLastShelfFolder) lastShelfLocation.copy(
    tab = lastShelfLocation.tab.takeIf { it in 0..2 } ?: 0,
    localFolderId = lastShelfLocation.localFolderId?.takeIf { it in folderIds.values },
    comicFolderId = lastShelfLocation.comicFolderId?.takeIf { it in folderIds.values },
) else ShelfLocation()

fun LibraryState.restoredCloudShelfLocation(account: String): CloudShelfLocation =
    restoredShelfLocation().cloud[account]?.takeIf { it.kind in 0..1 } ?: CloudShelfLocation()

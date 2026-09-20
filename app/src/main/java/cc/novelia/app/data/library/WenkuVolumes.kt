package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook

/** Mounts only local reading copies beneath an existing Wenku favorite. */
fun LibraryState.withWenkuVolumes(parentKey: String, volumeKeys: Set<String>): LibraryState {
    val parent = books.firstOrNull { it.book.ref.key == parentKey && it.book.ref.isWenku }
    require(parent != null) { "请先收藏目标文库小说" }
    require(volumeKeys.all { key -> books.any { it.book.ref.key == key && it.book.ref.isLocal } }) { "只能挂载已导入的本地分卷" }
    return copy(books = books.map { saved ->
        when {
            saved.book.ref.key == parentKey -> saved.copy(volumesExpanded = volumeKeys.isNotEmpty())
            saved.book.ref.key in volumeKeys -> saved.copy(parentWenkuKey = parentKey, folder = parent.folder)
            saved.parentWenkuKey == parentKey -> saved.copy(parentWenkuKey = null, folder = parent.folder)
            else -> saved
        }
    }).pruneVolumeOrders()
}

fun LibraryState.withVolumeParent(volumeKey: String, parentKey: String?): LibraryState {
    require(books.any { it.book.ref.key == volumeKey && it.book.ref.isLocal }) { "只能挂载已导入的本地分卷" }
    if(parentKey == null) return copy(books = books.map { saved ->
        if(saved.book.ref.key == volumeKey) saved.copy(parentWenkuKey = null, folder = books.firstOrNull { it.book.ref.key == saved.parentWenkuKey }?.folder ?: saved.folder) else saved
    }).pruneVolumeOrders()
    val siblings = books.filter { it.parentWenkuKey == parentKey }.map { it.book.ref.key }.toSet()
    return withWenkuVolumes(parentKey, siblings + volumeKey)
}

fun LibraryState.withoutBook(ref: BookRef): LibraryState = copy(books = books.filterNot { it.book.ref == ref }.map {
    if(it.parentWenkuKey == ref.key) it.copy(parentWenkuKey = null, folder = books.firstOrNull { saved -> saved.book.ref == ref }?.folder ?: it.folder) else it
}).pruneVolumeOrders()

fun LibraryState.moveShelfBooks(keys: Set<String>, folder: String): LibraryState = copy(books = books.map { saved ->
    if(saved.book.ref.key in keys) saved.copy(folder = folder, parentWenkuKey = saved.parentWenkuKey?.takeIf { it in keys }) else saved
}).pruneVolumeOrders()

/** Stores a complete sibling order; it cannot move books between Wenku favorites. */
fun LibraryState.withWenkuVolumeOrder(parentKey: String, volumeKeys: List<String>): LibraryState {
    require(books.any { it.book.ref.key == parentKey && it.book.ref.isWenku }) { "请先收藏目标文库小说" }
    val siblings = books.filter { it.book.ref.isLocal && it.parentWenkuKey == parentKey }.map { it.book.ref.key }.toSet()
    require(volumeKeys.size == siblings.size && volumeKeys.toSet() == siblings) { "只能调整同一文库下的分卷顺序" }
    return copy(books = books.map { if(it.book.ref.key == parentKey) it.copy(volumeOrder = volumeKeys.toList()) else it })
}

private fun LibraryState.pruneVolumeOrders(): LibraryState {
    val mounted = books.filter { it.book.ref.isLocal }.groupBy { it.parentWenkuKey }
    return copy(books = books.map { saved ->
        if(saved.volumeOrder.isEmpty()) saved else {
            val keys = mounted[saved.book.ref.key].orEmpty().map { it.book.ref.key }.toSet()
            saved.copy(volumeOrder = saved.volumeOrder.filter { it in keys }.distinct())
        }
    })
}

private fun orderedVolumes(parent: SavedBook, volumes: List<SavedBook>): List<SavedBook> {
    val ranks = parent.volumeOrder.withIndex().associate { it.value to it.index }
    return volumes.sortedWith { a, b ->
        val order = (ranks[a.book.ref.key] ?: Int.MAX_VALUE).compareTo(ranks[b.book.ref.key] ?: Int.MAX_VALUE)
        if(order != 0) order else compareVolumeTitles(a.book.title, b.book.title)
    }
}

data class ShelfGroup(val saved: SavedBook, val volumes: List<SavedBook> = emptyList())

/** Search retains the parent context; mounted volumes follow their parent's folder. */
fun LibraryState.shelfGroups(localOnly: Boolean, folder: String, query: String, sort: Int): List<ShelfGroup> {
    val parents = books.filter { it.book.ref.isWenku }.associateBy { it.book.ref.key }
    fun parentOf(saved: SavedBook) = if(saved.book.ref.isLocal) parents[saved.parentWenkuKey] else null
    val mounted = books.filter { parentOf(it) != null }.groupBy { it.parentWenkuKey }
    val groups = books.asSequence().filter { if(localOnly) it.book.ref.isLocal else parentOf(it) == null }
        .filter { folder == "全部" || (parentOf(it)?.folder ?: it.folder) == folder }
        .map { saved ->
            val volumes = if(localOnly) emptyList() else orderedVolumes(saved, mounted[saved.book.ref.key].orEmpty())
            if(saved.book.title.contains(query, true)) ShelfGroup(saved, volumes)
            else ShelfGroup(saved, volumes.filter { it.book.title.contains(query, true) })
        }
        .filter { it.saved.book.title.contains(query, true) || it.volumes.isNotEmpty() }
    fun recent(group: ShelfGroup): Long = (listOf(group.saved) + group.volumes).maxOf { positions[it.book.ref.key]?.updatedAt ?: it.addedAt }
    return groups.sortedWith(compareByDescending<ShelfGroup> { it.saved.pinned }
        .thenByDescending { if(sort == 0) recent(it) else if(sort == 1) it.saved.addedAt else 0 }
        .thenBy { it.saved.book.title }).toList()
}

// Numeric filenames should read 1, 2, 10, even when they have no zero padding.
private val volumeTitleTokens = Regex("\\d+|\\D+")
internal fun compareVolumeTitles(left: String, right: String): Int {
    val a = volumeTitleTokens.findAll(left).map { it.value }.toList()
    val b = volumeTitleTokens.findAll(right).map { it.value }.toList()
    for(index in 0 until minOf(a.size, b.size)) {
        val x = a[index]; val y = b[index]
        val comparison = if(x.first().isDigit() && y.first().isDigit()) {
            val xn = x.trimStart('0'); val yn = y.trimStart('0')
            xn.length.compareTo(yn.length).takeIf { it != 0 } ?: xn.compareTo(yn)
        } else x.compareTo(y, ignoreCase = true)
        if(comparison != 0) return comparison
    }
    return a.size.compareTo(b.size).takeIf { it != 0 } ?: left.compareTo(right)
}

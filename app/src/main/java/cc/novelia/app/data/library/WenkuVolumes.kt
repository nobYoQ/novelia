package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook

/**
 * 用给定集合替换某个文库条目的挂载分卷，只有已导入的本地书目可作为子卷。
 * 新挂载及脱离的分卷继承父条目文件夹，随后清理失效排序键；操作只改变书架关系，不改正文文件。
 */
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

/** 保存同一父书目下的完整分卷顺序，不用于跨文库收藏移动书籍。 */
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

enum class ShelfBookType(val label: String) { All("全部类型"), Wenku("文库小说"), Web("网络小说"), Local("本地小说") }

val readingStatuses = listOf("想读", "在读", "读完")

fun LibraryState.withReadingStatus(keys: Set<String>, status: String): LibraryState {
    require(status in readingStatuses) { "不支持的阅读状态" }
    return copy(books = books.map { if(it.book.ref.key in keys) it.copy(status = status) else it })
}

/** 不匹配筛选条件的父项仅作分卷上下文显示，不应被批量全选。 */
data class ShelfGroup(val saved: SavedBook, val volumes: List<SavedBook> = emptyList(), val matchesFilters: Boolean = true)

/**
 * 构造书架显示分组：普通书架将分卷放在文库父项下，本地视图则平铺本地书。
 * 搜索命中子卷时保留父项作为上下文，父项命中时保留全部子卷；文件夹按有效父项判断。
 * 最近阅读排序取当前分组中父项和子卷的最新时间，置顶优先于其他排序条件。
 */
fun LibraryState.shelfGroups(localOnly: Boolean, folder: String, query: String, sort: Int,
    type: ShelfBookType = ShelfBookType.All, status: String = "全部"): List<ShelfGroup> {
    val parents = books.filter { it.book.ref.isWenku }.associateBy { it.book.ref.key }
    fun parentOf(saved: SavedBook) = if(saved.book.ref.isLocal) parents[saved.parentWenkuKey] else null
    val keyword = query.trim()
    fun matchesQuery(saved: SavedBook): Boolean = saved.book.let { book ->
        book.title.contains(keyword, true) || book.originalTitle.contains(keyword, true) || book.authors.any { it.contains(keyword, true) }
    }
    fun matchesStatus(saved: SavedBook) = status == "全部" || saved.status == status
    fun matchesType(saved: SavedBook) = when(type) {
        ShelfBookType.All -> true
        ShelfBookType.Wenku -> saved.book.ref.isWenku || parentOf(saved) != null
        ShelfBookType.Web -> !saved.book.ref.isWenku && !saved.book.ref.isLocal
        ShelfBookType.Local -> saved.book.ref.isLocal && parentOf(saved) == null
    }
    val mounted = books.filter { parentOf(it) != null }.groupBy { it.parentWenkuKey }
    val groups = books.asSequence().filter { if(localOnly) it.book.ref.isLocal else parentOf(it) == null }
        .filter(::matchesType)
        .filter { folder == "全部" || (parentOf(it)?.folder ?: it.folder) == folder }
        .map { saved ->
            val volumes = if(localOnly) emptyList() else orderedVolumes(saved, mounted[saved.book.ref.key].orEmpty())
            val parentMatchesQuery = matchesQuery(saved) || (parentOf(saved)?.let(::matchesQuery) == true)
            ShelfGroup(saved, volumes.filter { matchesStatus(it) && (parentMatchesQuery || matchesQuery(it)) },
                matchesFilters = parentMatchesQuery && matchesStatus(saved))
        }
        .filter { it.matchesFilters || it.volumes.isNotEmpty() }
    fun recent(group: ShelfGroup): Long = (listOf(group.saved) + group.volumes).maxOf { positions[it.book.ref.key]?.updatedAt ?: it.addedAt }
    return groups.sortedWith(compareByDescending<ShelfGroup> { it.saved.pinned }
        .thenByDescending { if(sort == 0) recent(it) else if(sort == 1) it.saved.addedAt else 0 }
        .thenBy { it.saved.book.title }).toList()
}

// 文件名中的数字按 1、2、10 排序，即使没有补齐前导零。
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

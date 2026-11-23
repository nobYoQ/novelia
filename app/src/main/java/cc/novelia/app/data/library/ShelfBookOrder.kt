package cc.novelia.app.data.library

import cc.novelia.app.data.model.LibraryState

internal const val CUSTOM_SHELF_SORT = 3

internal fun LibraryState.withShelfSort(sort: Int): LibraryState {
    val order = if(sort == CUSTOM_SHELF_SORT && shelfBookOrder.isEmpty()) shelfKeys(shelfSort)
        else shelfBookOrder
    return copy(shelfSort = sort, shelfBookOrder = order)
}

/** 按当前书库合并拖放结果，保留未显示书籍之间的相对顺序。 */
internal fun LibraryState.withShelfBookMoved(key: String, target: String, after: Boolean): LibraryState {
    if(key == target || shelfSort != CUSTOM_SHELF_SORT) return this
    val visibleOrder = shelfKeys(CUSTOM_SHELF_SORT)
    if(key !in visibleOrder || target !in visibleOrder) return this
    // 分卷在本地文件页单独排序。这里按全局排名重新合并，保留该页已有的分卷顺序。
    val existing = visibleOrder.toHashSet()
    val order = shelfBookOrder.filter { it in existing }.distinct().toMutableList()
    val ranked = order.toHashSet()
    order += visibleOrder.filterNot { it in ranked }
    order.remove(key)
    order.add(order.indexOf(target) + if(after) 1 else 0, key)
    return copy(shelfBookOrder = order)
}

private fun LibraryState.shelfKeys(sort: Int): List<String> =
    shelfGroups(false, "全部", "", sort).flatMap { group ->
        listOf(group.saved.book.ref.key) + group.volumes.map { it.book.ref.key }
    }

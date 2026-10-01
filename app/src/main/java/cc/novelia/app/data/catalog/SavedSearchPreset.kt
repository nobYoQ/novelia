package cc.novelia.app.data.catalog

import cc.novelia.app.data.model.LibraryState
import java.util.UUID
import kotlinx.serialization.Serializable

/** 保存用户实际选择的完整条件；应用时仍由发现页按当前账号权限限制分级。 */
@Serializable
data class SavedSearchPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val query: String = "",
    val category: Int = 1,
    val source: String = "",
    val type: Int = 0,
    val translate: Int = 0,
    val sort: Int = 0,
    val webLevel: Int = 0,
    val wenkuLevel: Int = 0,
) {
    fun normalized() = copy(
        name = name.trim().take(80), query = query.trim(), category = category.coerceIn(1, 2),
        source = source.split(',').map(String::trim).filter { it in providers }.distinct().sorted().joinToString(","),
        type = type.coerceIn(0, 3), translate = translate.coerceIn(0, 2), sort = sort.coerceIn(0, 2),
        webLevel = webLevel.coerceIn(0, 2), wenkuLevel = wenkuLevel.coerceIn(0, 6),
    )

    fun hasSameConditions(other: SavedSearchPreset): Boolean =
        normalized().copy(id = "", name = "") == other.normalized().copy(id = "", name = "")

    fun summary(): String = normalized().let { value ->
        buildList {
            add(if(value.category == 2) "文库小说" else "网络小说")
            if(value.query.isNotBlank()) add(value.query)
            if(value.category == 1) {
                add(value.source.split(',').mapNotNull { providers[it] }.joinToString("、").ifBlank { "全部书源" })
                add(listOf("全部状态", "连载中", "已完结", "短篇")[value.type])
                add(listOf("全部译文", "GPT", "Sakura")[value.translate])
                add(listOf("更新时间", "点击量", "相关度")[value.sort])
                if(value.webLevel != 0) add(listOf("全部分级", "一般向", "R18")[value.webLevel])
            } else add(listOf("全部小说", "轻小说", "轻文学", "文学", "非小说", "R18男性向", "R18女性向")[value.wenkuLevel])
        }.joinToString(" · ")
    }
}

/** 旧表达式以稳定 ID 迁移，可重复执行，且不会在备份合并后产生重复项。 */
internal fun LibraryState.withMigratedSearchPresets(): LibraryState {
    if(savedSearches.isEmpty()) return this
    val migrated = savedSearches.filter(String::isNotBlank).map { query ->
        SavedSearchPreset(id = "legacy-" + UUID.nameUUIDFromBytes(query.toByteArray(Charsets.UTF_8)), name = query.take(80), query = query).normalized()
    }
    return copy(savedSearchPresets = (savedSearchPresets + migrated).distinctBy { it.id }, savedSearches = emptyList())
}

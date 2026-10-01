package cc.novelia.app.data.catalog

import cc.novelia.app.data.storage.appJson
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray

/**
 * 与账号无关的本机标签库快照，分类索引与词条一起保存，独立 JSON 导出也沿用此结构。
 * “其他”是不可删除的兜底分类；分类改名或删除同步修改词条并标记用户编辑，
 * 防止后续观察内置标签或恢复默认映射时撤销用户整理结果。
 */
@Serializable data class KeywordLibrary(
    val entries: List<KeywordEntry>,
    val categories: List<String> = KeywordCatalog.defaultCategories,
    val format: String = "novelia-keywords",
    val version: Int = 1,
) {
    fun createCategory(name: String): KeywordLibrary {
        requireCategoryName(name)
        require(name !in categories) { "分类名称已存在" }
        require(categories.size < MAX_CATEGORIES) { "分类最多 $MAX_CATEGORIES 个" }
        return copy(categories = categories + name)
    }

    fun renameCategory(old: String, name: String): KeywordLibrary {
        require(old in categories && old != OTHER) { "不能修改默认的其他分类" }
        requireCategoryName(name)
        if(name == old) return this
        require(name !in categories) { "分类名称已存在" }
        return copy(categories = categories.map { if(it == old) name else it },
            entries = entries.map { if(it.category == old) it.copy(category = name, categoryEdited = true) else it })
    }

    fun deleteCategory(name: String): KeywordLibrary {
        require(name in categories && name != OTHER) { "不能删除默认的其他分类" }
        return copy(categories = categories - name,
            entries = entries.map { if(it.category == name) it.copy(category = OTHER, categoryEdited = true) else it })
    }

    fun editEntry(original: String, translation: String, category: String): KeywordLibrary {
        require(original.isNotBlank() && original.length <= KeywordCatalog.MAX_TEXT_LENGTH && translation.length <= KeywordCatalog.MAX_TEXT_LENGTH) { "标签原文和翻译最多 ${KeywordCatalog.MAX_TEXT_LENGTH} 字符" }
        require(category in categories) { "分类已不存在，请重新选择" }
        val previous = entries.firstOrNull { it.original == original } ?: KeywordEntry(original)
        val edited = previous.copy(translation = translation, category = category,
            translationEdited = previous.translationEdited || translation != previous.translation,
            categoryEdited = previous.categoryEdited || category != previous.category)
        return copy(entries = KeywordCatalog.bounded(entries.filterNot { it.original == original } + edited))
    }

    /** 再次观察到内置标签时，不恢复用户已删除的分类。 */
    fun withEntries(updated: List<KeywordEntry>): KeywordLibrary = copy(entries = updated.map {
        if(it.category in categories) it else it.copy(category = OTHER)
    })

    /** 先检查合并后的分类和词条总量，超限则整体拒绝；已有用户编辑按字段分别优先。 */
    fun merge(incoming: KeywordLibrary): KeywordLibrary {
        KeywordLibraryFormat.validate(incoming)
        val combinedCategories = (categories + incoming.categories).distinct()
        require(combinedCategories.size <= MAX_CATEGORIES) { "合并后分类超过 $MAX_CATEGORIES 个，请先整理分类" }
        require((entries.map { it.original } + incoming.entries.map { it.original }).distinct().size <= KeywordCatalog.MAX_ENTRIES) {
            "合并后标签超过 ${KeywordCatalog.MAX_ENTRIES} 个，未导入任何内容"
        }
        // 用户编辑优先；未修改的内置翻译和分类可由默认词表补回。
        val merged = KeywordCatalog.merge(entries, incoming.entries, addDefaults = false)
        return copy(categories = combinedCategories).withEntries(merged)
    }

    companion object {
        const val OTHER = "其他"
        const val MAX_CATEGORIES = 100
        const val MAX_CATEGORY_LENGTH = 40
        fun defaults() = KeywordLibrary(KeywordCatalog.common)
        fun fromLegacy(entries: List<KeywordEntry>, addDefaults: Boolean = true): KeywordLibrary {
            val vocabulary = if(addDefaults) KeywordCatalog.withDefaults(entries) else entries
            return KeywordLibrary(vocabulary, (KeywordCatalog.defaultCategories + vocabulary.map { it.category }).distinct())
        }
        fun requireCategoryName(name: String) {
            require(name.isNotBlank() && name == name.trim() && name.length <= MAX_CATEGORY_LENGTH && name != "全部" && name.none(Char::isISOControl)) {
                "分类需为 1–$MAX_CATEGORY_LENGTH 个字符，不能使用“全部”或控制字符"
            }
        }
    }
}

/** 本地持久化、独立 JSON 导入导出和完整阅读资料备份共用此编解码器。 */
object KeywordLibraryFormat {
    const val MAX_BYTES = 32 * 1024 * 1024

    fun validate(library: KeywordLibrary) {
        require(library.format == "novelia-keywords" && library.version == 1) { "不是支持的 Novelia 标签库文件" }
        require(library.categories.size in 1..KeywordLibrary.MAX_CATEGORIES && library.categories.distinct().size == library.categories.size && KeywordLibrary.OTHER in library.categories) { "标签分类索引无效" }
        library.categories.forEach(KeywordLibrary::requireCategoryName)
        require(library.entries.size <= KeywordCatalog.MAX_ENTRIES && library.entries.map { it.original }.distinct().size == library.entries.size) { "标签数量过多或原文重复" }
        require(library.entries.all { it.original.isNotBlank() && it.original == it.original.trim() && it.original.length <= KeywordCatalog.MAX_TEXT_LENGTH &&
            it.translation.length <= KeywordCatalog.MAX_TEXT_LENGTH && it.category in library.categories && it.lastUsedAt >= 0 }) { "标签原文、翻译或分类无效" }
    }

    /** 兼容旧版词条数组；导入只解析和校验，不擅自补默认词条或删除重复项。 */
    fun decode(text: String): KeywordLibrary {
        val tree = appJson.parseToJsonElement(text.removePrefix("\uFEFF"))
        val result = if(tree is JsonArray) {
            val entries = appJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(KeywordEntry.serializer()), tree)
            // 保留旧数组的完整条目交给统一校验，不先去重或裁剪，以免掩盖非法导入。
            KeywordLibrary.fromLegacy(entries, addDefaults = false)
        } else appJson.decodeFromJsonElement(KeywordLibrary.serializer(), tree)
        validate(result)
        return result
    }

    fun read(input: InputStream): KeywordLibrary {
        val bytes = input.readBytesLimited(MAX_BYTES)
        return decode(bytes.toString(Charsets.UTF_8))
    }

    fun write(output: OutputStream, library: KeywordLibrary) {
        validate(library)
        val bytes = appJson.encodeToString(library).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "标签库超过 32 MB，无法导出" }
        output.write(bytes)
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while(true) {
            val count = read(buffer)
            if(count < 0) break
            require(output.size().toLong() + count <= limit) { "标签库文件超过 32 MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

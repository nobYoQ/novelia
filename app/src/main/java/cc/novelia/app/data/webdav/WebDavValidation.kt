package cc.novelia.app.data.webdav

import cc.novelia.app.data.catalog.KeywordCatalog
import cc.novelia.app.data.catalog.KeywordLibrary
import cc.novelia.app.data.model.DEFAULT_FOLDER
import cc.novelia.app.data.model.DEFAULT_FOLDER_ID
import cc.novelia.app.data.model.ReaderSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

import cc.novelia.app.data.webdav.WebDavSchema.FOLDER
import cc.novelia.app.data.webdav.WebDavSchema.CATEGORY
import cc.novelia.app.data.webdav.WebDavSchema.ORDER
import cc.novelia.app.data.webdav.WebDavSchema.ENTRY
import cc.novelia.app.data.webdav.WebDavSchema.BOOK_SETTING
import cc.novelia.app.data.webdav.WebDavSchema.GLOBAL
import cc.novelia.app.data.webdav.WebDavSchema.READER
import cc.novelia.app.data.webdav.WebDavSchema.BLOCKED
import cc.novelia.app.data.webdav.WebDavSchema.keywordEntryFields
import cc.novelia.app.data.webdav.WebDavSchema.categoryFields
import cc.novelia.app.data.webdav.WebDavSchema.orderFields
import cc.novelia.app.data.webdav.WebDavSchema.portableReaderFields
import cc.novelia.app.data.webdav.WebDavSchema.deviceReaderFields
import cc.novelia.app.data.webdav.WebDavSchema.portableGlobalFields
import cc.novelia.app.data.webdav.WebDavSchema.portableBookFields
import cc.novelia.app.data.webdav.WebDavSchema.positionFields
import cc.novelia.app.data.webdav.WebDavSchema.annotationFields
import cc.novelia.app.data.webdav.WebDavSchema.historyFields
import cc.novelia.app.data.webdav.WebDavSchema.isNetworkKey
import cc.novelia.app.data.webdav.WebDavSchema.blockedKey
import cc.novelia.app.data.webdav.WebDavSchema.arrayStrings

/** 校验每个同步候选、完整记录与字段值，不负责应用或生成业务投影。 */
internal object WebDavValidation {
    /** 校验所有候选而非只校验赢家，损坏或本地文件引用不能隐蔽地重新上传。 */
    fun validateField(domain: SyncDomain, key: String, field: String, value: JsonElement) {
        validateKey(domain, key)
        validateFieldName(domain, key, field)
        validateFieldValue(domain, key, field, value)
    }

    /** 整份文档验证已检查记录身份和字段名，候选值不再重复分配同一份白名单。 */
    internal fun validateFieldValue(domain: SyncDomain, key: String, field: String, value: JsonElement) {
        when (domain) {
            SyncDomain.SETTINGS -> when {
                key == GLOBAL -> {
                    require(field in portableGlobalFields) { "设置包含不允许同步的字段" }
                    when (field) {
                        "theme" -> enumText(value, setOf("system", "light", "dark"))
                        "keywordLimit" -> if (value != JsonNull) positiveInt(value)
                        else -> boolean(value)
                    }
                }
                key.startsWith(BLOCKED) -> {
                    val kind = key.removePrefix(BLOCKED).substringBefore('/')
                    if (field == "kind") require(text(value, 20, false) == kind) { "屏蔽成员类型不一致" }
                    else {
                        val member = text(value, if (kind == "book") 600 else 512, false)
                        require(blockedKey(kind, member) == key && (kind != "book" || isNetworkKey(member))) { "屏蔽成员身份无效或包含本地文件" }
                    }
                }
                key == READER || key.startsWith(BOOK_SETTING) -> readerField(field, value)
                else -> error("同步设置记录无效")
            }
            SyncDomain.FAVORITES -> when {
                key == ORDER -> { require(field == "ids"); idList(value, 1_000) }
                key.startsWith(FOLDER) -> { require(field == "name"); name(value, 200) }
                else -> when (field) {
                    "book" -> validateBook(value, key)
                    "folderId" -> id(value)
                    "pinned" -> boolean(value)
                    "status" -> enumText(value, setOf("在读", "读完", "想读"))
                    "addedAt" -> nonnegativeLong(value)
                    else -> error("收藏包含不允许同步的字段")
                }
            }
            SyncDomain.KEYWORDS -> when {
                key == ORDER -> { require(field == "ids"); idList(value, KeywordLibrary.MAX_CATEGORIES) }
                key.startsWith(CATEGORY) -> { require(field == "name"); name(value, KeywordLibrary.MAX_CATEGORY_LENGTH) }
                else -> when (field) {
                    "original" -> text(value, KeywordCatalog.MAX_TEXT_LENGTH, false).also { require(it == key.removePrefix(ENTRY) && it == it.trim()) { "标签原文身份无效" } }
                    "translation" -> text(value, KeywordCatalog.MAX_TEXT_LENGTH)
                    "categoryId" -> id(value)
                    "common", "translationEdited", "categoryEdited" -> boolean(value)
                    else -> error("标签包含不允许同步的字段")
                }
            }
            SyncDomain.PROGRESS -> {
                require(field == "position" && value is JsonObject && value.keys.containsAll(positionFields - "anchorTextHash") &&
                    value.keys.all { it in positionFields }) { "阅读位置包含无效字段" }
                text(value.getValue("chapterId"), 512, false)
                nonnegativeInt(value.getValue("sourceParagraph"), 10_000_000)
                nonnegativeInt(value.getValue("textOffset"), 10_000_000)
                text(value.getValue("title"), 2_000)
                nonnegativeLong(value.getValue("updatedAt"))
                text(value.getValue("anchorSource"), 256, false)
                value["anchorTextHash"]?.takeUnless { it == JsonNull }?.let { require(text(it, 64, false).matches(Regex("[a-f0-9]{64}"))) { "阅读内容指纹无效" } }
                listOf("chapterIndex", "chapterCount", "paragraphCount").forEach { property -> if (value.getValue(property) != JsonNull) nonnegativeInt(value.getValue(property), 10_000_000) }
                boolean(value.getValue("chapterCompleted"))
            }
            SyncDomain.BOOKMARKS, SyncDomain.NOTES -> {
                require(field in annotationFields || domain == SyncDomain.NOTES && field == "text") { "书签或笔记包含无效字段" }
                when (field) {
                    "key" -> require(isNetworkKey(text(value, 600, false))) { "书签或笔记不能引用本地文件" }
                    "chapterId" -> text(value, 512, false)
                    "paragraph" -> nonnegativeInt(value, 10_000_000)
                    "createdAt" -> nonnegativeLong(value)
                    "quote" -> text(value, 100_000)
                    "text" -> text(value, 1_000_000)
                    "selection" -> if(value != JsonNull) {
                        require(value is JsonObject && value.keys == setOf("source", "occurrence", "start", "end")) { "批注选区不完整" }
                        text(value.getValue("source"), 80, false)
                        nonnegativeInt(value.getValue("occurrence"), 1000)
                        nonnegativeInt(value.getValue("start"), 10_000_000)
                        nonnegativeInt(value.getValue("end"), 10_000_000)
                        require(value.getValue("end").jsonPrimitive.intOrNull!! > value.getValue("start").jsonPrimitive.intOrNull!!) { "批注选区无效" }
                    }
                    else -> text(value, 2_000)
                }
            }
            SyncDomain.HISTORY -> {
                require(field in historyFields) { "历史包含无效字段" }
                when (field) {
                    "bookKey" -> require(text(value, 600, false) == key && isNetworkKey(key)) { "历史书目身份不一致" }
                    "chapterId" -> text(value, 512, false)
                    "lastReadAt" -> nonnegativeLong(value)
                    else -> text(value, 2_000)
                }
            }
        }
    }

    fun validateKey(domain: SyncDomain, key: String) {
        val valid = when (domain) {
            SyncDomain.SETTINGS -> key == GLOBAL || key == READER || key.startsWith(BOOK_SETTING) && isNetworkKey(key.removePrefix(BOOK_SETTING)) ||
                key.matches(Regex("@blocked/(book|tag|author|user)/[a-f0-9]{64}"))
            SyncDomain.FAVORITES -> key == ORDER || key.startsWith(FOLDER) && WebDavMerge.validId(key.removePrefix(FOLDER)) || isNetworkKey(key)
            SyncDomain.KEYWORDS -> key == ORDER || key.startsWith(CATEGORY) && WebDavMerge.validId(key.removePrefix(CATEGORY)) ||
                key.startsWith(ENTRY) && key.removePrefix(ENTRY).let { it.isNotBlank() && it.length <= KeywordCatalog.MAX_TEXT_LENGTH }
            SyncDomain.PROGRESS, SyncDomain.HISTORY -> isNetworkKey(key)
            SyncDomain.BOOKMARKS, SyncDomain.NOTES -> key.isNotBlank() && key.length <= 128 && key.none(Char::isISOControl) && '/' !in key
        }
        require(valid) { "同步记录身份无效或包含本地文件引用" }
    }

    fun validateFieldName(domain: SyncDomain, key: String, field: String) {
        val allowed = when (domain) {
            SyncDomain.SETTINGS -> if (key == GLOBAL) portableGlobalFields else if (key.startsWith(BLOCKED)) setOf("kind", "value") else portableReaderFields + "devicePreferences"
            SyncDomain.FAVORITES -> if (key == ORDER) setOf("ids") else if (key.startsWith(FOLDER)) setOf("name") else setOf("book", "folderId", "pinned", "status", "addedAt")
            SyncDomain.KEYWORDS -> if (key == ORDER) orderFields else if (key.startsWith(CATEGORY)) categoryFields else keywordEntryFields
            SyncDomain.PROGRESS -> setOf("position")
            SyncDomain.BOOKMARKS -> annotationFields
            SyncDomain.NOTES -> annotationFields + "text"
            SyncDomain.HISTORY -> historyFields
        }
        require(field in allowed) { "同步记录包含未知、设备专属或本地文件字段" }
    }

    fun validateRecords(document: SyncDocument) {
        // 系统默认分类不可删除或改名，避免所有引用失去有效兜底。
        val defaultKey = when (document.domain) {
            SyncDomain.FAVORITES -> FOLDER + DEFAULT_FOLDER_ID
            SyncDomain.KEYWORDS -> CATEGORY + KeywordLibrary.legacyCategoryId(KeywordLibrary.OTHER)
            else -> null
        }
        defaultKey?.let { key -> document.records[key]?.let { record ->
            require(record.existence.candidates.none { it.deleted }) { "不能删除系统默认分类或收藏夹" }
            val defaultName = if (document.domain == SyncDomain.FAVORITES) DEFAULT_FOLDER else KeywordLibrary.OTHER
            require(record.fields["name"]?.candidates?.all { !it.deleted && it.value == JsonPrimitive(defaultName) } == true) { "不能修改系统默认分类或收藏夹" }
        } }
        // 逐条检查有效字段即可；不排序、复制并物化整个标签库的 JSON 投影。
        document.records.forEach { (key, record) ->
            if(WebDavMerge.winner(record.existence, existence = true)?.deleted != false) return@forEach
            val required = when (document.domain) {
                SyncDomain.SETTINGS -> if (key == GLOBAL) portableGlobalFields else if (key.startsWith(BLOCKED)) setOf("kind", "value") else portableReaderFields - setOf("customColors", "showReadingStatusBar", "horizontalMargin", "verticalMargin")
                SyncDomain.FAVORITES -> if (key == ORDER) setOf("ids") else if (key.startsWith(FOLDER)) setOf("name") else setOf("book", "folderId", "pinned", "status", "addedAt")
                SyncDomain.KEYWORDS -> if (key == ORDER) orderFields else if (key.startsWith(CATEGORY)) categoryFields else keywordEntryFields
                SyncDomain.PROGRESS -> setOf("position")
                SyncDomain.BOOKMARKS -> annotationFields - "selection"
                SyncDomain.NOTES -> (annotationFields - "selection") + "text"
                SyncDomain.HISTORY -> historyFields
            }
            // 老版同步快照可能缺少新增偏好，保留本机选择；字段存在时仍完整校验。
            val optional = when {
                document.domain == SyncDomain.SETTINGS && (key == READER || key.startsWith(BOOK_SETTING)) -> setOf("devicePreferences", "customColors", "showReadingStatusBar", "horizontalMargin", "verticalMargin")
                document.domain == SyncDomain.NOTES || document.domain == SyncDomain.BOOKMARKS -> setOf("selection")
                else -> emptySet()
            }
            require(required.all { field -> record.fields[field]?.let { WebDavMerge.winner(it)?.deleted == false } == true } &&
                record.fields.all { (field, cell) -> WebDavMerge.winner(cell)?.deleted != false || field in required || field in optional }) {
                "同步记录字段不完整，请恢复有效的远端数据"
            }
        }
    }

    private fun readerField(field: String, value: JsonElement) {
        if (field == "devicePreferences") {
            require(value is JsonObject && value.keys.containsAll(deviceReaderFields - setOf("tapZoneMap", "showWideToc", "orientation")) && value.keys.all { it in deviceReaderFields }) { "设备偏好原子组字段不完整" }
            value.forEach { (deviceField, deviceValue) -> deviceReaderField(deviceField, deviceValue) }
            return
        }
        require(field in portableReaderFields) { "包含设备专属阅读设置" }
        when (field) {
            "mode" -> enumText(value, setOf("zh", "jp", "zh-jp", "jp-zh"))
            "engines" -> arrayStrings(value).also { require(it.size == 3 && it.toSet() == setOf("sakura", "gpt", "youdao")) { "译源列表无效" } }
            "theme" -> enumText(value, ReaderSettings.THEMES.toSet())
            "customColors" -> {
                require(value is JsonObject && value.keys == setOf("text", "background", "toolbar")) { "阅读配色组不完整" }
                value.values.forEach { color -> require(color is JsonPrimitive && !color.isString && color.longOrNull in 0L..0xFFFFFFL) { "阅读颜色必须是有效 RGB 值" } }
            }
            "fontSize" -> number(value, 14.0, 32.0)
            "lineHeight" -> number(value, ReaderSettings.MIN_LINE_HEIGHT.toDouble(), 2.6)
            "width" -> number(value, 300.0, 900.0)
            "secondaryAlpha" -> number(value, 0.0, 1.0)
            "speechRate" -> number(value, 0.5, 2.0)
            "speechMinutes" -> positiveInt(value, 1_440)
            "speechLanguage" -> enumText(value, setOf("auto", "zh", "jp"))
            "toolbarTransparency" -> number(value, 0.0, 1.0)
            "paragraphSpacing" -> number(value, 0.0, 32.0)
            "horizontalMargin", "verticalMargin" -> number(value, 0.0, 64.0)
            else -> boolean(value)
        }
    }

    private fun deviceReaderField(field: String, value: JsonElement) {
        when (field) {
            "brightness" -> number(value, -1.0, 1.0)
            "paginationMode" -> enumText(value, setOf("scroll", "auto"))
            "orientation" -> enumText(value, ReaderSettings.ORIENTATIONS.toSet())
            "prefetchChapters" -> nonnegativeInt(value, 20)
            // 点击分区方案的编码串：限长避免同步文件被塞入超大数据，内容本身在解析时再校验。
            "tapZoneMap" -> text(value, 2_048, true)
            "beforeEInk", "eInkPreferences" -> if (value != JsonNull) {
                val fields = setOf("paginationMode", "scrollPageTurn", "horizontalPageTurn", "showPageButtons", "volumeKeys")
                require(value is JsonObject && value.keys == fields) { "设备翻页偏好组无效" }
                value.forEach { (property, propertyValue) -> if (property == "paginationMode") enumText(propertyValue, setOf("scroll", "auto")) else boolean(propertyValue) }
            }
            else -> boolean(value)
        }
    }

    private fun validateBook(value: JsonElement, key: String) {
        require(value is JsonObject && value.keys == portableBookFields) { "书目包含账号、文件或未知字段" }
        val ref = value.getValue("ref")
        require(ref is JsonObject && ref.keys == setOf("provider", "id")) { "书目引用无效" }
        val provider = text(ref.getValue("provider"), 80, false)
        val bookId = text(ref.getValue("id"), 500, false)
        require("$provider/$bookId" == key && isNetworkKey(key)) { "书目引用包含本地文件或身份不一致" }
        listOf("title", "originalTitle", "subtitle").forEach { text(value.getValue(it), 4_000) }
        value.getValue("cover").takeUnless { it == JsonNull }?.let { cover ->
            val address = text(cover, 8_192)
            require(address.startsWith("https://") || address.startsWith("http://")) { "封面不能引用本地文件" }
        }
        listOf("tags", "authors").forEach { textList(value.getValue(it), 1_000, 512) }
        textList(value.getValue("volumeIds"), 10_000, 512).also { require(arrayStrings(value.getValue("volumeIds")).none { it.startsWith("local/") || it.contains('/') }) { "分卷目录包含本地文件" } }
        listOf("translated", "total").forEach { nonnegativeInt(value.getValue(it), 10_000_000) }
        value.getValue("updateAt").takeUnless { it == JsonNull }?.let(::nonnegativeLong)
        val translations = value.getValue("translations")
        require(translations is JsonObject && translations.size <= 32) { "译文统计无效" }
        translations.forEach { (engine, count) -> require(engine in setOf("sakura", "gpt", "youdao")); nonnegativeInt(count, 10_000_000) }
        value.getValue("novelType").takeUnless { it == JsonNull }?.let { text(it, 100) }
        value.getValue("attentions").takeUnless { it == JsonNull }?.let { textList(it, 1_000, 512) }
        value.getValue("totalCharacters").takeUnless { it == JsonNull }?.let(::nonnegativeLong)
    }

    private fun text(value: JsonElement, maxLength: Int, allowEmpty: Boolean = true): String {
        require(value is JsonPrimitive && value.isString && value.content.length <= maxLength && (allowEmpty || value.content.isNotBlank())) { "同步文本类型或长度无效" }
        return value.content
    }
    private fun name(value: JsonElement, maxLength: Int) {
        val name = text(value, maxLength, false)
        require(name == name.trim() && name != "全部" && name.none(Char::isISOControl)) { "同步分类或收藏夹名称无效" }
    }
    private fun id(value: JsonElement) { require(WebDavMerge.validId(text(value, 128, false))) { "同步分类标识无效" } }
    private fun idList(value: JsonElement, max: Int) {
        val ids = arrayStrings(value)
        require(ids.size <= max && ids.distinct().size == ids.size && ids.all(WebDavMerge::validId)) { "同步排序索引无效" }
    }
    private fun textList(value: JsonElement, max: Int, maxLength: Int) {
        val values = arrayStrings(value)
        require(values.size <= max && values.all { it.length <= maxLength }) { "同步文本列表超过限制" }
    }
    private fun boolean(value: JsonElement) { require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) { "同步布尔值无效" } }
    private fun enumText(value: JsonElement, allowed: Set<String>) { require(text(value, 100, false) in allowed) { "同步选项值无效" } }
    private fun number(value: JsonElement, minimum: Double, maximum: Double) {
        require(value is JsonPrimitive && !value.isString && value.doubleOrNull?.let { it.isFinite() && it in minimum..maximum } == true) { "同步数值超出范围" }
    }
    private fun nonnegativeInt(value: JsonElement, maximum: Int = Int.MAX_VALUE) {
        require(value is JsonPrimitive && !value.isString && value.intOrNull?.let { it in 0..maximum } == true) { "同步整数无效" }
    }
    private fun positiveInt(value: JsonElement, maximum: Int = Int.MAX_VALUE) {
        require(value is JsonPrimitive && !value.isString && value.intOrNull?.let { it in 1..maximum } == true) { "同步正整数无效" }
    }
    private fun nonnegativeLong(value: JsonElement) {
        require(value is JsonPrimitive && !value.isString && value.longOrNull?.let { it >= 0 } == true) { "同步时间或计数无效" }
    }
}

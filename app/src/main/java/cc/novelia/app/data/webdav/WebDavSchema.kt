package cc.novelia.app.data.webdav

import cc.novelia.app.data.model.BookRef
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** 投影与校验共用的协议字段和记录身份；修改时必须兼容已有同步资料。 */
internal object WebDavSchema {
    const val FOLDER = "@folder/"
    const val CATEGORY = "@category/"
    const val ORDER = "@order"
    const val ENTRY = "@entry/"
    const val BOOK_SETTING = "@book/"
    const val GLOBAL = "@global"
    const val READER = "@reader"
    const val BLOCKED = "@blocked/"
    val keywordEntryFields = setOf("original", "translation", "categoryId", "common", "translationEdited", "categoryEdited")
    val categoryFields = setOf("name")
    val orderFields = setOf("ids")
    val portableReaderFields = setOf("mode", "engines", "parallel", "fontSize", "lineHeight", "weight", "width",
        "indent", "theme", "secondaryAlpha", "underline", "speechRate", "speechMinutes", "traditional", "speechLanguage",
        "toolbarTransparency", "paragraphSpacing", "showScrollPageButtons", "showProgressBar", "showReadingStatusBar", "speechContinueChapters", "customColors",
        "horizontalMargin", "verticalMargin", "autoReadSpeed", "autoReadIntervalSeconds", "textReplacements", "textReplacementsEnabled")
    // fontFamily/customFont 依赖本机字体文件，两种同步模式均保留接收端的字体选择。
    val deviceReaderFields = setOf("keepScreenOn", "volumeKeys", "paged", "eInkMode", "monochrome", "scrollPageTurn",
        "horizontalPageTurn", "brightness", "paginationMode", "showPageButtons", "beforeEInk", "eInkPreferences", "prefetchChapters",
        "prefetchWifiOnly", "speechNetworkContinuation", "showEInkScreenButtons", "hideStatusBar", "tapPageTurn", "tapZoneMap", "showWideToc", "orientation")
    val portableGlobalFields = setOf("theme", "reducedMotion", "historyPaused", "hideNovelComments",
        "autoCollapseCloudFilters", "autoSaveCloudFavoritesLocally", "clipboardLinkHints", "keywordLimit")
    val portableBookFields = setOf("ref", "title", "originalTitle", "cover", "subtitle", "tags", "translated", "total",
        "updateAt", "translations", "volumeIds", "authors", "novelType", "attentions", "totalCharacters")
    val positionFields = setOf("chapterId", "sourceParagraph", "textOffset", "title", "updatedAt", "anchorSource", "chapterIndex",
        "chapterCount", "paragraphCount", "chapterCompleted", "anchorTextHash")
    val annotationFields = setOf("key", "chapterId", "paragraph", "quote", "createdAt", "bookTitle", "chapterTitle", "selection")
    val historyFields = setOf("bookKey", "bookTitle", "chapterId", "chapterTitle", "lastReadAt")

    fun isNetworkKey(key: String): Boolean {
        val ref = BookRef.fromKey(key)
        return !ref.isLocal && ref.provider.isNotBlank() && ref.provider.matches(Regex("[A-Za-z0-9_-]{1,80}")) &&
            ref.id.isNotBlank() && ref.id.length <= 500 && ref.id.none(Char::isISOControl) && !ref.id.contains('/')
    }

    fun blockedKey(kind: String, value: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "$BLOCKED$kind/$hash"
    }
    fun arrayStrings(value: JsonElement): List<String> {
        require(value is JsonArray && value.all { it is JsonPrimitive && it.isString }) { "同步列表必须是文本数组" }
        return value.map { it.jsonPrimitive.content }
    }
}

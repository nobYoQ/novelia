package cc.novelia.app.data.webdav

import cc.novelia.app.data.catalog.KeywordCatalog
import cc.novelia.app.data.catalog.KeywordEntry
import cc.novelia.app.data.catalog.KeywordLibrary
import cc.novelia.app.data.catalog.KeywordLibraryFormat
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DEFAULT_FOLDER
import cc.novelia.app.data.model.DEFAULT_FOLDER_ID
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.ReadingHistoryEntry
import cc.novelia.app.data.model.legacyFolderId
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 同步唯一的业务边界。白名单保证本地文件、分卷挂载与账号资料永远不会进入协议。 */
object WebDavProjection {
    val libraryDomains = SyncDomain.entries.toSet() - SyncDomain.KEYWORDS
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private const val FOLDER = "@folder/"
    private const val CATEGORY = "@category/"
    private const val ORDER = "@order"
    private const val ENTRY = "@entry/"
    private const val BOOK_SETTING = "@book/"
    private const val GLOBAL = "@global"
    private const val READER = "@reader"
    private const val BLOCKED = "@blocked/"
    private val portableReaderFields = setOf("mode", "engines", "parallel", "fontSize", "lineHeight", "weight", "width",
        "indent", "theme", "secondaryAlpha", "underline", "speechRate", "speechMinutes", "traditional", "speechLanguage",
        "toolbarTransparency", "paragraphSpacing", "showScrollPageButtons", "showProgressBar", "speechContinueChapters", "customColors")
    private val deviceReaderFields = setOf("keepScreenOn", "volumeKeys", "paged", "eInkMode", "monochrome", "scrollPageTurn",
        "horizontalPageTurn", "brightness", "paginationMode", "showPageButtons", "beforeEInk", "eInkPreferences", "prefetchChapters",
        "prefetchWifiOnly", "speechNetworkContinuation", "showEInkScreenButtons", "hideStatusBar", "tapPageTurn")
    private val portableGlobalFields = setOf("theme", "reducedMotion", "historyPaused", "hideNovelComments",
        "autoCollapseCloudFilters", "autoSaveCloudFavoritesLocally", "clipboardLinkHints", "keywordLimit")
    private val portableBookFields = setOf("ref", "title", "originalTitle", "cover", "subtitle", "tags", "translated", "total",
        "updateAt", "translations", "volumeIds", "authors", "novelType", "attentions", "totalCharacters")
    private val positionFields = setOf("chapterId", "sourceParagraph", "textOffset", "title", "updatedAt", "anchorSource", "chapterIndex",
        "chapterCount", "paragraphCount", "chapterCompleted", "anchorTextHash")
    private val annotationFields = setOf("key", "chapterId", "paragraph", "quote", "createdAt", "bookTitle", "chapterTitle")
    private val historyFields = setOf("bookKey", "bookTitle", "chapterId", "chapterTitle", "lastReadAt")

    fun anchorSource(settings: ReaderSettings): String =
        "${settings.mode}:${settings.engines.joinToString(",")}:${settings.parallel}:${settings.traditional}:${settings.indent}"

    /** 避免阅读器每次保存位置时编码书架、设置和笔记的完整快照。 */
    fun affectedLibraryDomains(before: LibraryState, after: LibraryState): Set<SyncDomain> = buildSet {
        if (before.books != after.books || before.folders != after.folders || before.folderIds != after.folderIds) add(SyncDomain.FAVORITES)
        if (before.positions != after.positions) add(SyncDomain.PROGRESS)
        if (before.readingHistory != after.readingHistory) add(SyncDomain.HISTORY)
        if (before.notes != after.notes) { add(SyncDomain.BOOKMARKS); add(SyncDomain.NOTES) }
        if (before.reader != after.reader || before.bookSettings != after.bookSettings || before.theme != after.theme ||
            before.reducedMotion != after.reducedMotion || before.historyPaused != after.historyPaused ||
            before.blockedBooks != after.blockedBooks || before.blockedTags != after.blockedTags || before.blockedAuthors != after.blockedAuthors ||
            before.blockedUsers != after.blockedUsers || before.hideNovelComments != after.hideNovelComments ||
            before.autoCollapseCloudFilters != after.autoCollapseCloudFilters || before.autoSaveCloudFavoritesLocally != after.autoSaveCloudFavoritesLocally ||
            before.clipboardLinkHints != after.clipboardLinkHints || before.keywordLimit != after.keywordLimit) add(SyncDomain.SETTINGS)
    }

    fun library(state: LibraryState, domains: Set<SyncDomain> = libraryDomains, includeDevicePreferences: Boolean = false): SyncProjection = domains.filter { it in libraryDomains }.associateWith { domain ->
        when (domain) {
            SyncDomain.SETTINGS -> settings(state, includeDevicePreferences)
            SyncDomain.FAVORITES -> favorites(state)
            SyncDomain.PROGRESS -> state.positions.filterKeys(::isNetworkKey).mapValues { (key, value) ->
                val settings = state.bookSettings[key] ?: state.reader
                val position = json.encodeToJsonElement(Position.serializer(), value).jsonObject.filterKeys { it in positionFields }.toMutableMap()
                position["sourceParagraph"] = (value.sourceParagraph ?: (value.index - 1).coerceAtLeast(0)).let(::JsonPrimitive)
                position["anchorSource"] = JsonPrimitive(value.anchorSource ?: anchorSource(settings))
                mapOf("position" to JsonObject(position)).asObject()
            }
            SyncDomain.BOOKMARKS, SyncDomain.NOTES -> state.notes.filter { isNetworkKey(it.key) &&
                (if (domain == SyncDomain.BOOKMARKS) it.bookmarked else it.text.isNotEmpty()) }.associate { note ->
                val fields = json.encodeToJsonElement(Note.serializer(), note).jsonObject.filterKeys { it in annotationFields }.toMutableMap()
                if (domain == SyncDomain.NOTES) fields["text"] = JsonPrimitive(note.text)
                note.id to JsonObject(fields)
            }
            SyncDomain.HISTORY -> state.readingHistory.filter { (key, value) -> isNetworkKey(key) && value.bookKey == key }
                .mapValues { (_, value) -> json.encodeToJsonElement(ReadingHistoryEntry.serializer(), value).jsonObject }
            SyncDomain.KEYWORDS -> emptyMap()
        }
    }

    private fun settings(state: LibraryState, includeDevicePreferences: Boolean): Map<String, JsonObject> = buildMap {
        put(GLOBAL, mapOf(
            "theme" to JsonPrimitive(state.theme), "reducedMotion" to JsonPrimitive(state.reducedMotion),
            "historyPaused" to JsonPrimitive(state.historyPaused), "hideNovelComments" to JsonPrimitive(state.hideNovelComments),
            "autoCollapseCloudFilters" to JsonPrimitive(state.autoCollapseCloudFilters),
            "autoSaveCloudFavoritesLocally" to JsonPrimitive(state.autoSaveCloudFavoritesLocally),
            "clipboardLinkHints" to JsonPrimitive(state.clipboardLinkHints), "keywordLimit" to (state.keywordLimit?.let(::JsonPrimitive) ?: JsonNull)
        ).asObject())
        put(READER, reader(state.reader, includeDevicePreferences))
        state.bookSettings.filterKeys(::isNetworkKey).toSortedMap().forEach { (key, value) -> put(BOOK_SETTING + key, reader(value, includeDevicePreferences)) }
        mapOf("book" to state.blockedBooks.filter(::isNetworkKey).toSet(), "tag" to state.blockedTags,
            "author" to state.blockedAuthors, "user" to state.blockedUsers).forEach { (kind, values) ->
            values.sorted().forEach { value -> put(blockedKey(kind, value), mapOf("kind" to JsonPrimitive(kind), "value" to JsonPrimitive(value)).asObject()) }
        }
    }

    private fun reader(settings: ReaderSettings, includeDevicePreferences: Boolean = false): JsonObject {
        val encoded = json.encodeToJsonElement(ReaderSettings.serializer(), settings).jsonObject
        val portable = encoded.filterKeys { it in portableReaderFields }
        return JsonObject(if (includeDevicePreferences) portable + ("devicePreferences" to JsonObject(encoded.filterKeys { it in deviceReaderFields })) else portable)
    }

    private fun favorites(state: LibraryState): Map<String, JsonObject> = buildMap {
        val folderIds = state.folders.associateWith { if (it == DEFAULT_FOLDER) DEFAULT_FOLDER_ID else state.folderIds[it] ?: legacyFolderId(it) }
        val previous = state.syncReplica.documents[SyncDomain.FAVORITES]
        val included = folderIds.filter { (name, id) ->
            val deleted = previous?.records?.get(FOLDER + id)?.let { WebDavMerge.winner(it.existence, true)?.deleted } == true
            !deleted || state.books.any { !it.book.ref.isLocal && (it.folderId == id || it.folder == name) }
        }
        included.forEach { (name, id) -> put(FOLDER + id, mapOf("name" to JsonPrimitive(name)).asObject()) }
        put(ORDER, mapOf("ids" to strings(included.values.toList())).asObject())
        state.books.filterNot { it.book.ref.isLocal }.sortedBy { it.book.ref.key }.forEach { saved ->
            val portable = saved.book.copy(cover = saved.book.cover?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                volumeIds = saved.book.volumeIds.filter { !it.startsWith("local/") && '/' !in it })
            val book = json.encodeToJsonElement(BookCard.serializer(), portable).jsonObject.filterKeys { it in portableBookFields }
            put(saved.book.ref.key, mapOf("book" to JsonObject(book),
                "folderId" to JsonPrimitive(saved.folderId ?: folderIds[saved.folder] ?: DEFAULT_FOLDER_ID),
                "pinned" to JsonPrimitive(saved.pinned), "status" to JsonPrimitive(saved.status), "addedAt" to JsonPrimitive(saved.addedAt)).asObject())
        }
    }

    fun keywords(library: KeywordLibrary): SyncProjection {
        val ids = library.categories.associateWith { library.categoryIds[it] ?: KeywordLibrary.legacyCategoryId(it) }
        val records = buildMap {
            ids.forEach { (name, id) -> put(CATEGORY + id, mapOf("name" to JsonPrimitive(name)).asObject()) }
            put(ORDER, mapOf("ids" to strings(ids.values.toList())).asObject())
            library.entries.forEach { entry -> put(ENTRY + entry.original, mapOf(
                "original" to JsonPrimitive(entry.original), "translation" to JsonPrimitive(entry.translation),
                "categoryId" to JsonPrimitive(ids[entry.category] ?: KeywordLibrary.legacyCategoryId(KeywordLibrary.OTHER)),
                "common" to JsonPrimitive(entry.common), "translationEdited" to JsonPrimitive(entry.translationEdited),
                "categoryEdited" to JsonPrimitive(entry.categoryEdited)).asObject()) }
        }
        return mapOf(SyncDomain.KEYWORDS to records)
    }

    fun applyLibrary(state: LibraryState, documents: Map<SyncDomain, SyncDocument>, selected: Set<SyncDomain>, includeDevicePreferences: Boolean = false): LibraryState {
        val chosen = documents.filterKeys { it in selected && it in libraryDomains }
        chosen.forEach { (domain, document) ->
            require(document.domain == domain) { "同步类型不一致" }
            WebDavMerge.validate(document)
            validateRecords(document)
        }
        var result = state
        chosen[SyncDomain.SETTINGS]?.let { result = applySettings(result, WebDavMerge.materialize(it), includeDevicePreferences) }
        chosen[SyncDomain.FAVORITES]?.let { result = applyFavorites(result, WebDavMerge.materialize(it)) }
        chosen[SyncDomain.PROGRESS]?.let { document ->
            val conflicts = WebDavMerge.conflicts(document).map { it.recordKey }.toSet()
            val positions = result.positions.filterKeys { !isNetworkKey(it) }.toMutableMap()
            WebDavMerge.materialize(document).forEach { (key, fields) ->
                // 有并发续读候选时保留本机位置，待用户选择后再移动。
                if (key in conflicts) result.positions[key]?.let { positions[key] = it }
                else positions[key] = decodePosition(fields.getValue("position").jsonObject, result.bookSettings[key] ?: result.reader)
            }
            result = result.copy(positions = positions)
        }
        chosen[SyncDomain.HISTORY]?.let { document ->
            val history = WebDavMerge.materialize(document).mapValues { (_, fields) -> json.decodeFromJsonElement(ReadingHistoryEntry.serializer(), fields) }
            result = result.copy(readingHistory = result.readingHistory.filterKeys { !isNetworkKey(it) } + history, historyMigrated = true)
        }
        if (SyncDomain.BOOKMARKS in chosen || SyncDomain.NOTES in chosen) result = applyAnnotations(result, chosen)
        return result
    }

    private fun applySettings(state: LibraryState, records: Map<String, JsonObject>, includeDevicePreferences: Boolean): LibraryState {
        val values = records[GLOBAL]
        fun bool(name: String, old: Boolean) = values?.get(name)?.jsonPrimitive?.booleanOrNull ?: old
        val blockedRecords = records.filterKeys { it.startsWith(BLOCKED) }.values.groupBy { it.getValue("kind").jsonPrimitive.content }
        fun blocked(kind: String) = blockedRecords[kind].orEmpty().map { it.getValue("value").jsonPrimitive.content }.toSet()
        val globalReader = records[READER]?.let { applyReader(state.reader, it, includeDevicePreferences) } ?: state.reader
        val bookSettings = state.bookSettings.filterKeys { !isNetworkKey(it) }.toMutableMap()
        records.filterKeys { it.startsWith(BOOK_SETTING) }.forEach { (recordKey, value) ->
            val key = recordKey.removePrefix(BOOK_SETTING)
            bookSettings[key] = applyReader(state.bookSettings[key] ?: globalReader, value, includeDevicePreferences)
        }
        return state.copy(theme = values?.get("theme")?.jsonPrimitive?.content ?: state.theme,
            reducedMotion = bool("reducedMotion", state.reducedMotion), historyPaused = bool("historyPaused", state.historyPaused),
            blockedBooks = state.blockedBooks.filterNot(::isNetworkKey).toSet() + blocked("book"),
            blockedTags = blocked("tag"), blockedAuthors = blocked("author"),
            blockedUsers = blocked("user"), hideNovelComments = bool("hideNovelComments", state.hideNovelComments),
            autoCollapseCloudFilters = bool("autoCollapseCloudFilters", state.autoCollapseCloudFilters),
            autoSaveCloudFavoritesLocally = bool("autoSaveCloudFavoritesLocally", state.autoSaveCloudFavoritesLocally),
            clipboardLinkHints = bool("clipboardLinkHints", state.clipboardLinkHints),
            keywordLimit = if (values != null && "keywordLimit" in values) values["keywordLimit"]?.jsonPrimitive?.intOrNull else state.keywordLimit,
            reader = globalReader, bookSettings = bookSettings)
    }

    private fun applyReader(local: ReaderSettings, remote: JsonObject, includeDevicePreferences: Boolean): ReaderSettings {
        val preferences = if (includeDevicePreferences) remote["devicePreferences"]?.jsonObject.orEmpty() else emptyMap()
        return json.decodeFromJsonElement(ReaderSettings.serializer(), JsonObject(
            json.encodeToJsonElement(ReaderSettings.serializer(), local).jsonObject + remote.filterKeys { it in portableReaderFields } + preferences))
    }

    private fun applyFavorites(state: LibraryState, records: Map<String, JsonObject>): LibraryState {
        val rawNames = records.filterKeys { it.startsWith(FOLDER) }.mapKeys { it.key.removePrefix(FOLDER) }
            .mapValues { it.value.getValue("name").jsonPrimitive.content } + (DEFAULT_FOLDER_ID to DEFAULT_FOLDER)
        val names = uniqueNames(rawNames, DEFAULT_FOLDER_ID, DEFAULT_FOLDER, 200)
        val order = orderedIds(records[ORDER], names.keys, DEFAULT_FOLDER_ID)
        val folders = order.map { names.getValue(it) }.toMutableList()
        val folderIds = names.entries.associate { it.value to it.key }.toMutableMap()
        // 本地文件仍保留其文件、分卷挂载、收藏夹等关联资料。
        val localBooks = state.books.filter { it.book.ref.isLocal }.map { saved ->
            val id = saved.folderId ?: state.folderIds[saved.folder] ?: legacyFolderId(saved.folder)
            val folderName = names[id] ?: saved.folder
            if (folderName !in folders) { folders += folderName; folderIds[folderName] = id }
            if (folderName == saved.folder) saved else saved.copy(folder = folderName)
        }
        val localByKey = state.books.associateBy { it.book.ref.key }
        val remote = records.filterKeys { !it.startsWith("@") }.map { (key, fields) ->
            val book = json.decodeFromJsonElement(BookCard.serializer(), fields.getValue("book"))
            require(book.ref.key == key && !book.ref.isLocal) { "收藏书目身份不一致" }
            val folderId = fields.getValue("folderId").jsonPrimitive.content.takeIf { it in names } ?: DEFAULT_FOLDER_ID
            val old = localByKey[key]
            (old ?: cc.novelia.app.data.model.SavedBook(book)).copy(
                // 原站关系与本机分卷整理状态不由 WebDAV 改写。
                book = book.copy(favored = old?.book?.favored, cloudReading = old?.book?.cloudReading),
                folder = names.getValue(folderId), folderId = folderId, pinned = fields.getValue("pinned").jsonPrimitive.booleanOrNull!!,
                status = fields.getValue("status").jsonPrimitive.content, addedAt = fields.getValue("addedAt").jsonPrimitive.longOrNull!!)
        }
        return state.copy(books = localBooks + remote, folders = folders, folderIds = folderIds)
    }

    private fun applyAnnotations(state: LibraryState, documents: Map<SyncDomain, SyncDocument>): LibraryState {
        val bookmarks = documents[SyncDomain.BOOKMARKS]?.let(WebDavMerge::materialize)
        val notes = documents[SyncDomain.NOTES]?.let(WebDavMerge::materialize)
        val existing = state.notes.filter { isNetworkKey(it.key) }.associateBy { it.id }
        val keys = existing.keys + bookmarks.orEmpty().keys + notes.orEmpty().keys
        val merged = keys.sorted().mapNotNull { id ->
            val old = existing[id]
            val bookmarked = if (bookmarks == null) old?.bookmarked ?: false else id in bookmarks
            val text = if (notes == null) old?.text.orEmpty() else notes[id]?.get("text")?.jsonPrimitive?.content.orEmpty()
            if (!bookmarked && text.isEmpty()) return@mapNotNull null
            val anchor = notes?.get(id) ?: bookmarks?.get(id)
            if (anchor == null) old?.copy(bookmarked = bookmarked, text = text)
            else Note(id, anchor.getValue("key").jsonPrimitive.content, anchor.getValue("chapterId").jsonPrimitive.content,
                anchor.getValue("paragraph").jsonPrimitive.intOrNull!!, anchor.getValue("quote").jsonPrimitive.content, text,
                anchor.getValue("createdAt").jsonPrimitive.longOrNull!!, anchor.getValue("bookTitle").jsonPrimitive.content,
                anchor.getValue("chapterTitle").jsonPrimitive.content, bookmarked)
        }
        return state.copy(notes = state.notes.filter { !isNetworkKey(it.key) } + merged)
    }

    private fun decodePosition(remote: JsonObject, settings: ReaderSettings): Position {
        val source = remote.getValue("sourceParagraph").jsonPrimitive.intOrNull!!
        val sameText = remote["anchorSource"]?.jsonPrimitive?.content == anchorSource(settings)
        val portable = JsonObject(remote + mapOf("index" to JsonPrimitive(source + 1), "offset" to JsonPrimitive(0),
            "textOffset" to JsonPrimitive(if (sameText) remote["textOffset"]?.jsonPrimitive?.intOrNull ?: 0 else 0)))
        return json.decodeFromJsonElement(Position.serializer(), portable)
    }

    fun applyKeywords(library: KeywordLibrary, document: SyncDocument, limit: Int? = null): KeywordLibrary {
        require(document.domain == SyncDomain.KEYWORDS) { "不是标签库同步文件" }
        WebDavMerge.validate(document)
        validateRecords(document)
        val records = WebDavMerge.materialize(document)
        val otherId = KeywordLibrary.legacyCategoryId(KeywordLibrary.OTHER)
        val rawNames = records.filterKeys { it.startsWith(CATEGORY) }.mapKeys { it.key.removePrefix(CATEGORY) }
            .mapValues { it.value.getValue("name").jsonPrimitive.content } + (otherId to KeywordLibrary.OTHER)
        require(rawNames.size <= KeywordLibrary.MAX_CATEGORIES) { "同步后的分类数量超过限制" }
        val names = uniqueNames(rawNames, otherId, KeywordLibrary.OTHER, KeywordLibrary.MAX_CATEGORY_LENGTH)
        val categories = orderedIds(records[ORDER], names.keys, otherId).map { names.getValue(it) }
        val existingEntries = library.entries.associateBy { it.original }
        val entries = records.filterKeys { it.startsWith(ENTRY) }.map { (_, fields) ->
            val original = fields.getValue("original").jsonPrimitive.content
            KeywordEntry(original, fields.getValue("translation").jsonPrimitive.content,
                names[fields.getValue("categoryId").jsonPrimitive.content] ?: KeywordLibrary.OTHER,
                fields.getValue("common").jsonPrimitive.booleanOrNull!!,
                existingEntries[original]?.lastUsedAt ?: 0,
                fields.getValue("translationEdited").jsonPrimitive.booleanOrNull!!,
                fields.getValue("categoryEdited").jsonPrimitive.booleanOrNull!!)
        }
        KeywordCatalog.requireCapacity(library.entries.size, entries.size, limit)
        return library.copy(entries = entries, categories = categories,
            categoryIds = names.entries.associate { it.value to it.key }).also(KeywordLibraryFormat::validate)
    }

    private fun uniqueNames(values: Map<String, String>, reservedId: String, reservedName: String, maxLength: Int): Map<String, String> {
        val used = mutableSetOf(reservedName)
        return buildMap {
            put(reservedId, reservedName)
            values.toSortedMap().filterKeys { it != reservedId }.forEach { (id, name) ->
                var unique = name
                if (unique in used) {
                    val suffix = " (${id.take(8)})"
                    unique = name.take((maxLength - suffix.length).coerceAtLeast(1)) + suffix
                    var duplicate = 2
                    while (unique in used) {
                        val numberedSuffix = " (${id.take(8)}-${duplicate++})"
                        unique = name.take((maxLength - numberedSuffix.length).coerceAtLeast(1)) + numberedSuffix
                    }
                }
                put(id, unique)
                used += unique
            }
        }
    }

    private fun orderedIds(order: JsonObject?, ids: Set<String>, defaultId: String): List<String> {
        val requested = order?.get("ids")?.let(::arrayStrings).orEmpty()
        val ordered = requested.filter { it in ids }.distinct()
        val missing = (ids - ordered.toSet()).sorted()
        return if (defaultId in ordered) ordered + missing else listOf(defaultId) + ordered + (missing - defaultId)
    }

    fun isNetworkKey(key: String): Boolean {
        val ref = BookRef.fromKey(key)
        return !ref.isLocal && ref.provider.isNotBlank() && ref.provider.matches(Regex("[A-Za-z0-9_-]{1,80}")) &&
            ref.id.isNotBlank() && ref.id.length <= 500 && ref.id.none(Char::isISOControl) && !ref.id.contains('/')
    }

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun blockedKey(kind: String, value: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "$BLOCKED$kind/$hash"
    }
    private fun Map<String, JsonElement>.asObject() = JsonObject(this)
    private fun arrayStrings(value: JsonElement): List<String> {
        require(value is JsonArray && value.all { it is JsonPrimitive && it.isString }) { "同步列表必须是文本数组" }
        return value.map { it.jsonPrimitive.content }
    }

    /** 校验所有候选而非只校验赢家，损坏或本地文件引用不能隐蔽地重新上传。 */
    fun validateField(domain: SyncDomain, key: String, field: String, value: JsonElement) {
        validateKey(domain, key)
        validateFieldName(domain, key, field)
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
            SyncDomain.KEYWORDS -> if (key == ORDER) setOf("ids") else if (key.startsWith(CATEGORY)) setOf("name") else setOf("original", "translation", "categoryId", "common", "translationEdited", "categoryEdited")
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
        WebDavMerge.materialize(document).forEach { (key, value) ->
            val required = when (document.domain) {
                SyncDomain.SETTINGS -> if (key == GLOBAL) portableGlobalFields else if (key.startsWith(BLOCKED)) setOf("kind", "value") else portableReaderFields - "customColors"
                SyncDomain.FAVORITES -> if (key == ORDER) setOf("ids") else if (key.startsWith(FOLDER)) setOf("name") else setOf("book", "folderId", "pinned", "status", "addedAt")
                SyncDomain.KEYWORDS -> if (key == ORDER) setOf("ids") else if (key.startsWith(CATEGORY)) setOf("name") else setOf("original", "translation", "categoryId", "common", "translationEdited", "categoryEdited")
                SyncDomain.PROGRESS -> setOf("position")
                SyncDomain.BOOKMARKS -> annotationFields
                SyncDomain.NOTES -> annotationFields + "text"
                SyncDomain.HISTORY -> historyFields
            }
            // 老版同步快照没有配色组，保留本机配色；新快照的配色组仍作为原子字段校验。
            val optional = if (document.domain == SyncDomain.SETTINGS && (key == READER || key.startsWith(BOOK_SETTING))) setOf("devicePreferences", "customColors") else emptySet()
            require(value.keys.containsAll(required) && value.keys.all { it in required || it in optional }) { "同步记录字段不完整，请恢复有效的远端数据" }
        }
    }

    private fun readerField(field: String, value: JsonElement) {
        if (field == "devicePreferences") {
            require(value is JsonObject && value.keys == deviceReaderFields) { "设备偏好原子组字段不完整" }
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
            else -> boolean(value)
        }
    }

    private fun deviceReaderField(field: String, value: JsonElement) {
        when (field) {
            "brightness" -> number(value, -1.0, 1.0)
            "paginationMode" -> enumText(value, setOf("scroll", "auto"))
            "prefetchChapters" -> nonnegativeInt(value, 20)
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

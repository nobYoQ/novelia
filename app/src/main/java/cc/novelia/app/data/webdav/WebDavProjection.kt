package cc.novelia.app.data.webdav

import cc.novelia.app.data.catalog.KeywordCatalog
import cc.novelia.app.data.webdav.WebDavSchema.FOLDER
import cc.novelia.app.data.webdav.WebDavSchema.CATEGORY
import cc.novelia.app.data.webdav.WebDavSchema.ORDER
import cc.novelia.app.data.webdav.WebDavSchema.ENTRY
import cc.novelia.app.data.webdav.WebDavSchema.BOOK_SETTING
import cc.novelia.app.data.webdav.WebDavSchema.GLOBAL
import cc.novelia.app.data.webdav.WebDavSchema.READER
import cc.novelia.app.data.webdav.WebDavSchema.BLOCKED
import cc.novelia.app.data.webdav.WebDavSchema.portableReaderFields
import cc.novelia.app.data.webdav.WebDavSchema.deviceReaderFields
import cc.novelia.app.data.webdav.WebDavSchema.portableBookFields
import cc.novelia.app.data.webdav.WebDavSchema.positionFields
import cc.novelia.app.data.webdav.WebDavSchema.annotationFields
import cc.novelia.app.data.webdav.WebDavSchema.blockedKey
import cc.novelia.app.data.webdav.WebDavSchema.arrayStrings
import cc.novelia.app.data.catalog.KeywordEntry
import cc.novelia.app.data.catalog.KeywordLibrary
import cc.novelia.app.data.catalog.KeywordLibraryFormat
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.DEFAULT_FOLDER
import cc.novelia.app.data.model.DEFAULT_FOLDER_ID
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.ReadingHistoryEntry
import cc.novelia.app.data.model.legacyFolderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 同步业务入口：生成和应用资料投影；共享字段与校验保证本地文件及账号资料不进入协议。 */
object WebDavProjection {
    val libraryDomains = SyncDomain.entries.toSet() - SyncDomain.KEYWORDS
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    fun anchorSource(settings: ReaderSettings): String =
        "${settings.mode}:${settings.engines.joinToString(",")}:${settings.parallel}:${settings.traditional}:${settings.indent}" +
            settings.activeTextReplacements.takeIf { it.isNotEmpty() }?.let { ":replace:${it.hashCode()}" }.orEmpty()

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
                (if (domain == SyncDomain.BOOKMARKS) it.bookmarked else it.text.isNotEmpty() || it.selection != null) }.associate { note ->
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

    /** 仅投影新增、删除或同步字段改变的标签；使用时间变化不产生同步工作。 */
    fun trackKeywords(previous: KeywordLibrary, current: KeywordLibrary): SyncReplica {
        val before = linkedMapOf<String, JsonObject>()
        val after = linkedMapOf<String, JsonObject>()
        fun ids(library: KeywordLibrary) = library.categories.associateWith {
            library.categoryIds[it] ?: KeywordLibrary.legacyCategoryId(it)
        }
        val oldIds = ids(previous)
        val newIds = ids(current)
        fun categories(index: Map<String, String>) = buildMap {
            index.forEach { (name, id) -> put(CATEGORY + id, mapOf("name" to JsonPrimitive(name)).asObject()) }
            put(ORDER, mapOf("ids" to strings(index.values.toList())).asObject())
        }
        val oldCategories = categories(oldIds)
        val newCategories = categories(newIds)
        (oldCategories.keys + newCategories.keys).forEach { key ->
            if(oldCategories[key] != newCategories[key]) {
                oldCategories[key]?.let { before[key] = it }
                newCategories[key]?.let { after[key] = it }
            }
        }
        fun categoryId(entry: KeywordEntry, index: Map<String, String>) = index[entry.category]
            ?: KeywordLibrary.legacyCategoryId(KeywordLibrary.OTHER)
        fun fields(entry: KeywordEntry, index: Map<String, String>) = mapOf(
            "original" to JsonPrimitive(entry.original), "translation" to JsonPrimitive(entry.translation),
            "categoryId" to JsonPrimitive(categoryId(entry, index)), "common" to JsonPrimitive(entry.common),
            "translationEdited" to JsonPrimitive(entry.translationEdited), "categoryEdited" to JsonPrimitive(entry.categoryEdited),
        ).asObject()
        if(previous.entries !== current.entries || oldIds != newIds) {
            val remaining = previous.entries.associateByTo(LinkedHashMap()) { it.original }
            current.entries.forEach { entry ->
                val old = remaining.remove(entry.original)
                val same = old != null && old.translation == entry.translation && old.common == entry.common &&
                    old.translationEdited == entry.translationEdited && old.categoryEdited == entry.categoryEdited &&
                    categoryId(old, oldIds) == categoryId(entry, newIds)
                if(!same) {
                    val key = ENTRY + entry.original
                    if(old != null) before[key] = fields(old, oldIds)
                    after[key] = fields(entry, newIds)
                }
            }
            remaining.forEach { (original, entry) -> before[ENTRY + original] = fields(entry, oldIds) }
        }
        if(before.isEmpty() && after.isEmpty()) return previous.syncReplica
        // 首次真正修改时仍登记已有资料，保留离线编辑、删除和多设备因果合并语义。
        val replica = if(SyncDomain.KEYWORDS in previous.syncReplica.documents) previous.syncReplica
            else previous.syncReplica.track(emptyMap(), keywords(previous))
        return replica.track(mapOf(SyncDomain.KEYWORDS to before), mapOf(SyncDomain.KEYWORDS to after))
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
            val anchor = notes?.get(id) ?: bookmarks?.get(id)
            val selection = if(notes == null && old?.selection != null) old.selection else anchor?.get("selection")?.takeUnless { it == JsonNull }?.let {
                json.decodeFromJsonElement(cc.novelia.app.data.model.NoteSelection.serializer(), it)
            }
            if (!bookmarked && text.isEmpty() && selection == null) return@mapNotNull null
            if (anchor == null) old?.copy(bookmarked = bookmarked, text = text, selection = selection)
            else Note(id, anchor.getValue("key").jsonPrimitive.content, anchor.getValue("chapterId").jsonPrimitive.content,
                anchor.getValue("paragraph").jsonPrimitive.intOrNull!!, anchor.getValue("quote").jsonPrimitive.content, text,
                anchor.getValue("createdAt").jsonPrimitive.longOrNull!!, anchor.getValue("bookTitle").jsonPrimitive.content,
                anchor.getValue("chapterTitle").jsonPrimitive.content, bookmarked, selection)
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

    fun isNetworkKey(key: String): Boolean = WebDavSchema.isNetworkKey(key)

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun Map<String, JsonElement>.asObject() = JsonObject(this)
    /** 保留业务边界入口；字段、候选和完整记录校验共用 WebDavValidation。 */
    fun validateField(domain: SyncDomain, key: String, field: String, value: JsonElement) =
        WebDavValidation.validateField(domain, key, field, value)

    internal fun validateFieldValue(domain: SyncDomain, key: String, field: String, value: JsonElement) =
        WebDavValidation.validateFieldValue(domain, key, field, value)

    fun validateKey(domain: SyncDomain, key: String) = WebDavValidation.validateKey(domain, key)

    fun validateFieldName(domain: SyncDomain, key: String, field: String) =
        WebDavValidation.validateFieldName(domain, key, field)

    fun validateRecords(document: SyncDocument) = WebDavValidation.validateRecords(document)
}

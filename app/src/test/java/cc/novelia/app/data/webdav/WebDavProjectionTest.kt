package cc.novelia.app.data.webdav

import cc.novelia.app.data.catalog.KeywordEntry
import cc.novelia.app.data.catalog.KeywordLibrary
import cc.novelia.app.data.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class WebDavProjectionTest {
    private val web = BookRef("syosetu", "n1")
    private val local = BookRef("local", "file1")
    private fun documents(state: LibraryState) = SyncReplica(deviceId = "a").track(emptyMap(), WebDavProjection.library(state)).bind("dataset").documents

    @Test fun exactColorEditsAreTrackedAndRoundTripWithoutEnablingDevicePreferences() {
        val before = LibraryState(reader = ReaderSettings(theme = "custom"),
            bookSettings = mapOf(web.key to ReaderSettings(theme = "custom")))
        val colors = ReaderCustomColors(0x123ABC, 0x37BC91, 0xF12345)
        val bookColors = ReaderCustomColors(0xABCDEF, 0x2468AC, 0x0F1234)
        val defaultEdit = before.copy(reader = before.reader.copy(customColors = colors))
        val bookEdit = before.copy(bookSettings = mapOf(web.key to before.bookSettings.getValue(web.key).copy(customColors = bookColors)))
        assertEquals(setOf(SyncDomain.SETTINGS), WebDavProjection.affectedLibraryDomains(before, defaultEdit))
        assertEquals(setOf(SyncDomain.SETTINGS), WebDavProjection.affectedLibraryDomains(before, bookEdit))
        val after = defaultEdit.copy(bookSettings = bookEdit.bookSettings)
        fun projection(state: LibraryState) = WebDavProjection.library(state, setOf(SyncDomain.SETTINGS), includeDevicePreferences = false)
        val original = projection(before)
        val updated = projection(after)
        val document = SyncReplica(deviceId = "a").track(emptyMap(), original).bind("dataset")
            .track(original, updated).documents.getValue(SyncDomain.SETTINGS)
        val wireJson = Json { encodeDefaults = true; explicitNulls = false }
        val received = wireJson.decodeFromString(SyncDocument.serializer(), wireJson.encodeToString(SyncDocument.serializer(), document))
        WebDavMerge.validate(received, "dataset")
        WebDavProjection.validateRecords(received)
        val fields = WebDavMerge.materialize(received)
        listOf("@reader", "@book/${web.key}").forEach { key ->
            assertTrue(fields.getValue(key).getValue("customColors") is JsonObject)
            assertFalse("devicePreferences" in fields.getValue(key))
            assertEquals(1, received.records.getValue(key).fields.getValue("customColors").candidates.size)
        }
        val target = LibraryState(reader = ReaderSettings(brightness = .2f))
        val restored = WebDavProjection.applyLibrary(target, mapOf(SyncDomain.SETTINGS to received), setOf(SyncDomain.SETTINGS))
        assertEquals(colors, restored.reader.customColors)
        assertEquals(bookColors, restored.bookSettings.getValue(web.key).customColors)
        assertEquals("custom", restored.reader.theme)
        assertEquals("custom", restored.bookSettings.getValue(web.key).theme)
        assertEquals(.2f, restored.reader.brightness)
    }

    @Test fun customColorsSyncAsOneGroupAndLegacyRecordsRemainValid() {
        val colors = ReaderCustomColors(0xFFFFFF, 0x141A16, 0x050A07)
        val remote = LibraryState(reader = ReaderSettings(theme = "custom", customColors = colors),
            bookSettings = mapOf(web.key to ReaderSettings(theme = "custom", customColors = colors.copy(text = 0xDDE5DC))))
        val docs = documents(remote)
        WebDavProjection.validateRecords(docs.getValue(SyncDomain.SETTINGS))
        val result = WebDavProjection.applyLibrary(LibraryState(), docs, setOf(SyncDomain.SETTINGS))
        assertEquals(colors, result.reader.customColors)
        assertEquals("custom", result.reader.theme)
        assertEquals(remote.bookSettings[web.key]?.customColors, result.bookSettings[web.key]?.customColors)
        val legacy = docs.getValue(SyncDomain.SETTINGS).let { document -> document.copy(records = document.records.mapValues { (_, record) ->
            record.copy(fields = record.fields - "customColors")
        }) }
        WebDavProjection.validateRecords(legacy)
        val local = LibraryState(reader = ReaderSettings(customColors = colors))
        assertEquals(colors, WebDavProjection.applyLibrary(local, mapOf(SyncDomain.SETTINGS to legacy), setOf(SyncDomain.SETTINGS)).reader.customColors)
        val group = WebDavProjection.library(remote).getValue(SyncDomain.SETTINGS).getValue("@reader").getValue("customColors") as JsonObject
        WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "customColors", group)
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "customColors", JsonObject(group - "text")) }
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "customColors", JsonObject(group + ("text" to JsonPrimitive(-1)))) }
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "customColors", JsonObject(group + ("text" to JsonPrimitive("112233")))) }
    }

    @Test fun everyLocalFileAssociationIsExcludedAndPreservedOnImport() {
        val localBook = SavedBook(BookCard(local, "导入书", cover = "file:///cover"), parentWenkuKey = "wenku/parent", volumeOrder = listOf(local.key))
        val state = LibraryState(books = listOf(localBook, SavedBook(BookCard(web, "网络书", favored = "account-folder", cloudReading = CloudReadingProgress("账号")),
            volumeOrder = listOf(local.key))), positions = mapOf(local.key to Position("lc"), web.key to Position("wc")),
            notes = listOf(Note("local-note", local.key, "lc", 0, "摘录", "笔记"), Note("web-note", web.key, "wc", 0, "摘录", "笔记")),
            readingHistory = mapOf(local.key to ReadingHistoryEntry(local.key, "导入书", "lc", "章", 1)),
            bookSettings = mapOf(local.key to ReaderSettings(fontSize = 24f)), blockedBooks = setOf(local.key))
        val projection = WebDavProjection.library(state)
        val data = projection.toString()
        assertFalse(data.contains("local/"))
        assertFalse(data.contains("parentWenkuKey"))
        assertFalse(data.contains("volumeOrder"))
        assertFalse(data.contains("cloudReading"))
        assertFalse(data.contains("favored"))
        assertFalse(data.contains("hasUpdates"))
        val emptyRemote = documents(LibraryState())
        val result = WebDavProjection.applyLibrary(state, emptyRemote, WebDavProjection.libraryDomains)
        assertEquals(localBook, result.books.single())
        assertEquals(state.positions[local.key], result.positions[local.key])
        assertEquals(state.notes.first(), result.notes.single())
        assertEquals(state.bookSettings[local.key], result.bookSettings[local.key])
        assertEquals(state.readingHistory[local.key], result.readingHistory[local.key])
        assertTrue(local.key in result.blockedBooks)
    }

    @Test fun sevenDomainsRemainIndependent() {
        val old = LibraryState(books = listOf(SavedBook(BookCard(web, "旧书"))), positions = mapOf(web.key to Position("old")),
            notes = listOf(Note("n", web.key, "old", 1, "旧摘录", "旧笔记")),
            readingHistory = mapOf(web.key to ReadingHistoryEntry(web.key, "旧书", "old", "旧章", 1)))
        val remote = LibraryState(theme = "dark", books = listOf(SavedBook(BookCard(web, "新书"))), positions = mapOf(web.key to Position("new")),
            notes = listOf(Note("n", web.key, "new", 2, "新摘录", "新笔记")),
            readingHistory = mapOf(web.key to ReadingHistoryEntry(web.key, "新书", "new", "新章", 2)))
        val docs = documents(remote)
        val settings = WebDavProjection.applyLibrary(old, docs, setOf(SyncDomain.SETTINGS))
        assertEquals("dark", settings.theme)
        assertEquals(old.books, settings.books); assertEquals(old.positions, settings.positions); assertEquals(old.notes, settings.notes)
        val history = WebDavProjection.applyLibrary(old, docs, setOf(SyncDomain.HISTORY))
        assertEquals(remote.readingHistory, history.readingHistory)
        assertEquals(old.positions, history.positions); assertEquals(old.books, history.books)
        val favorites = WebDavProjection.applyLibrary(old, docs, setOf(SyncDomain.FAVORITES))
        assertEquals("新书", favorites.books.single().book.title)
        assertEquals(old.positions, favorites.positions); assertEquals(old.readingHistory, favorites.readingHistory)
        assertEquals(SyncDomain.entries.toSet(), (WebDavProjection.library(old) + WebDavProjection.keywords(KeywordLibrary(listOf(KeywordEntry("标签")), listOf("其他")))) .keys)
    }

    @Test fun bookmarksNeverExportNoteTextAndImportDoesNotEraseLocalBody() {
        val old = LibraryState(notes = listOf(Note("n", web.key, "c", 0, "摘录", "私有笔记")))
        val remote = old.copy(notes = listOf(old.notes[0].copy(paragraph = 3, text = "另一端正文")))
        val bookmarkDocument = documents(remote).getValue(SyncDomain.BOOKMARKS)
        assertFalse(WebDavMerge.materialize(bookmarkDocument).toString().contains("另一端正文"))
        assertFalse("text" in WebDavMerge.materialize(bookmarkDocument).getValue("n"))
        val result = WebDavProjection.applyLibrary(old, mapOf(SyncDomain.BOOKMARKS to bookmarkDocument), setOf(SyncDomain.BOOKMARKS))
        assertEquals("私有笔记", result.notes.single().text)
        assertEquals(3, result.notes.single().paragraph)
        assertTrue(result.notes.single().bookmarked)
    }

    @Test fun notesRestoreWithoutBookmarksOrFavoritesAndRemovalKeepsBookmark() {
        val remote = LibraryState(notes = listOf(Note("n", web.key, "c", 5, "摘录", "正文")))
        val imported = WebDavProjection.applyLibrary(LibraryState(), documents(remote), setOf(SyncDomain.NOTES))
        assertEquals("正文", imported.notes.single().text)
        assertFalse(imported.notes.single().bookmarked)
        assertTrue(imported.books.isEmpty())
        val bookmark = imported.copy(notes = listOf(imported.notes[0].copy(bookmarked = true)))
        val cleared = WebDavProjection.applyLibrary(bookmark, documents(LibraryState()), setOf(SyncDomain.NOTES))
        assertTrue(cleared.notes.single().bookmarked)
        assertEquals("", cleared.notes.single().text)
    }

    @Test fun clearingHistoryLeavesReadingProgress() {
        val state = LibraryState(positions = mapOf(web.key to Position("c")), readingHistory = mapOf(web.key to ReadingHistoryEntry(web.key, "书", "c", "章", 1)))
        val result = WebDavProjection.applyLibrary(state, documents(LibraryState()), setOf(SyncDomain.HISTORY))
        assertTrue(result.readingHistory.isEmpty())
        assertEquals(state.positions, result.positions)
    }

    @Test fun portableReaderSettingsPreserveDevicePreferences() {
        val localState = LibraryState(reader = ReaderSettings(brightness = .3f, eInkMode = true, volumeKeys = true, prefetchWifiOnly = false),
            wifiOnly = true, autoSync = false)
        val remote = LibraryState(reader = ReaderSettings(fontSize = 25f, brightness = .9f, eInkMode = false, volumeKeys = false),
            wifiOnly = false, autoSync = true)
        val result = WebDavProjection.applyLibrary(localState, documents(remote), setOf(SyncDomain.SETTINGS))
        assertEquals(25f, result.reader.fontSize)
        assertEquals(.3f, result.reader.brightness)
        assertTrue(result.reader.eInkMode); assertTrue(result.reader.volumeKeys)
        assertFalse(result.reader.prefetchWifiOnly)
        assertTrue(result.wifiOnly); assertFalse(result.autoSync)
    }

    @Test fun sourceParagraphRestoresAcrossLanguageChangesWithoutPixelOrTextOffset() {
        val sourceSettings = ReaderSettings(mode = "jp")
        val source = LibraryState(reader = sourceSettings, positions = mapOf(web.key to Position("c", index = 9, offset = 500, textOffset = 20,
            sourceParagraph = 7, anchorSource = WebDavProjection.anchorSource(sourceSettings))))
        val target = LibraryState(reader = ReaderSettings(mode = "zh"))
        val result = WebDavProjection.applyLibrary(target, documents(source), setOf(SyncDomain.PROGRESS)).positions.getValue(web.key)
        assertEquals(7, result.sourceParagraph)
        assertEquals(8, result.index)
        assertEquals(0, result.offset)
        assertEquals(0, result.textOffset)
        val same = WebDavProjection.applyLibrary(LibraryState(reader = sourceSettings), documents(source), setOf(SyncDomain.PROGRESS)).positions.getValue(web.key)
        assertEquals(20, same.textOffset)
    }

    @Test fun networkBookMetadataKeepsLocalCloudRelationshipAndVolumeOrder() {
        val card = BookCard(web, "书", favored = "账号收藏", cloudReading = CloudReadingProgress("账号", lastReadAt = 10))
        val state = LibraryState(books = listOf(SavedBook(card, volumeOrder = listOf(local.key), volumesExpanded = true, hasUpdates = true)))
        val remote = LibraryState(books = listOf(SavedBook(card.copy(title = "新名称", favored = null, cloudReading = null))))
        val result = WebDavProjection.applyLibrary(state, documents(remote), setOf(SyncDomain.FAVORITES)).books.single()
        assertEquals("新名称", result.book.title)
        assertEquals(card.favored, result.book.favored)
        assertEquals(card.cloudReading, result.book.cloudReading)
        assertEquals(state.books[0].volumeOrder, result.volumeOrder)
        assertTrue(result.volumesExpanded); assertTrue(result.hasUpdates)
    }

    @Test fun invalidRemoteLocalReferenceIsRejectedIncludingNonWinningCandidate() {
        val remote = documents(LibraryState(notes = listOf(Note("n", web.key, "c", 0, "摘录", "正文")))).getValue(SyncDomain.NOTES)
        val record = remote.records.getValue("n")
        val cell = record.fields.getValue("key")
        val corrupt = cell.candidates.single().copy(value = JsonPrimitive(local.key), writer = "b", counter = 1, version = mapOf("b" to 1))
        val document = remote.copy(context = remote.context + ("b" to 1), records = mapOf("n" to record.copy(fields = record.fields + ("key" to SyncCell(cell.candidates + corrupt)))))
        assertThrows(IllegalArgumentException::class.java) { WebDavMerge.validate(document) }
    }

    @Test fun keywordCapacityFailureDoesNotSilentlyTrimAndLocalUsageTimeRemainsLocal() {
        val old = KeywordLibrary(listOf(KeywordEntry("一", lastUsedAt = 123)), categories = listOf("其他"))
        val remote = old.copy(entries = listOf(KeywordEntry("一", translation = "one", lastUsedAt = 999), KeywordEntry("二")))
        val document = SyncReplica(deviceId = "a").track(emptyMap(), WebDavProjection.keywords(remote)).bind("dataset").documents.getValue(SyncDomain.KEYWORDS)
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.applyKeywords(old, document, 1) }
        val result = WebDavProjection.applyKeywords(old, document)
        assertEquals(123L, result.entries.first { it.original == "一" }.lastUsedAt)
        assertEquals(0L, result.entries.first { it.original == "二" }.lastUsedAt)
        assertFalse(WebDavMerge.materialize(document).toString().contains("lastUsedAt"))
    }

    @Test fun affectedDomainsAvoidUnrelatedPositionEncoding() {
        val old = LibraryState()
        assertEquals(setOf(SyncDomain.PROGRESS), WebDavProjection.affectedLibraryDomains(old, old.copy(positions = mapOf(web.key to Position("c")))))
        assertTrue(WebDavProjection.affectedLibraryDomains(old, old.copy(wifiOnly = true)).isEmpty())
        assertEquals(setOf(SyncDomain.SETTINGS), WebDavProjection.library(old, setOf(SyncDomain.SETTINGS)).keys)
    }

    @Test fun categoryRenameKeepsStableIdentityAndDeletionFallsBackToOther() {
        val old = KeywordLibrary(listOf(KeywordEntry("标签", category = "类别")), categories = listOf("其他", "类别")).withStableCategoryIds()
        val renamed = old.renameCategory("类别", "新名称")
        val replica = SyncReplica(deviceId = "a").track(emptyMap(), WebDavProjection.keywords(old)).bind("dataset")
            .track(WebDavProjection.keywords(old), WebDavProjection.keywords(renamed))
        val restored = WebDavProjection.applyKeywords(old, replica.documents.getValue(SyncDomain.KEYWORDS))
        assertEquals(old.categoryIds["类别"], restored.categoryIds["新名称"])
        assertEquals("新名称", restored.entries.single().category)
        val deleted = renamed.deleteCategory("新名称")
        val deletion = replica.track(WebDavProjection.keywords(renamed), WebDavProjection.keywords(deleted))
        val final = WebDavProjection.applyKeywords(old, deletion.documents.getValue(SyncDomain.KEYWORDS))
        assertEquals(listOf("其他"), final.categories)
        assertEquals("其他", final.entries.single().category)
    }

    @Test fun folderRenamePreservesStableIdentityAndMissingFolderUsesDefault() {
        val old = LibraryState(books = listOf(SavedBook(BookCard(web, "书"), folder = "夹")), folders = listOf(DEFAULT_FOLDER, "夹")).withStableFolderIds()
        val renamed = old.renameShelfFolder("夹", "新夹")
        val replica = SyncReplica(deviceId = "a").track(emptyMap(), WebDavProjection.library(old, setOf(SyncDomain.FAVORITES))).bind("dataset")
            .track(WebDavProjection.library(old, setOf(SyncDomain.FAVORITES)), WebDavProjection.library(renamed, setOf(SyncDomain.FAVORITES)))
        val restored = WebDavProjection.applyLibrary(old, replica.documents, setOf(SyncDomain.FAVORITES))
        assertEquals(old.folderIds["夹"], restored.folderIds["新夹"])
        assertEquals("新夹", restored.books.single().folder)
        val deleted = renamed.deleteShelfFolder("新夹")
        val deletion = replica.track(WebDavProjection.library(renamed, setOf(SyncDomain.FAVORITES)), WebDavProjection.library(deleted, setOf(SyncDomain.FAVORITES)))
        assertEquals(DEFAULT_FOLDER, WebDavProjection.applyLibrary(old, deletion.documents, setOf(SyncDomain.FAVORITES)).books.single().folder)
    }

    @Test fun invalidSettingsTypesOutOfRangeNumbersAndUnknownDeviceFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.SETTINGS, "@global", "reducedMotion", JsonPrimitive("true")) }
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "fontSize", JsonPrimitive(1000)) }
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "lineHeight", JsonPrimitive(Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "brightness", JsonPrimitive(.5)) }
        assertThrows(IllegalArgumentException::class.java) { WebDavProjection.validateField(SyncDomain.BOOKMARKS, "n", "text", JsonPrimitive("正文")) }
    }

    @Test fun indentationDifferenceAlsoInvalidatesTextOffset() {
        val sourceSettings = ReaderSettings(indent = true)
        val source = LibraryState(reader = sourceSettings, positions = mapOf(web.key to Position("c", textOffset = 5,
            sourceParagraph = 0, anchorSource = WebDavProjection.anchorSource(sourceSettings))))
        val result = WebDavProjection.applyLibrary(LibraryState(reader = ReaderSettings(indent = false)), documents(source), setOf(SyncDomain.PROGRESS))
        assertEquals(0, result.positions.getValue(web.key).textOffset)
    }

    @Test fun optionalDevicePreferenceGroupIsIgnoredByDefaultAndAppliedWhenSelected() {
        val source = LibraryState(reader = ReaderSettings().withEInkMode(true).copy(brightness = .7f, keepScreenOn = true))
        val projection = WebDavProjection.library(source, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true)
        val documents = SyncReplica(deviceId = "a").track(emptyMap(), projection).bind("dataset").documents
        val target = LibraryState(reader = ReaderSettings(brightness = .2f))
        val default = WebDavProjection.applyLibrary(target, documents, setOf(SyncDomain.SETTINGS))
        assertEquals(.2f, default.reader.brightness)
        assertFalse(default.reader.eInkMode)
        val selected = WebDavProjection.applyLibrary(target, documents, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true)
        assertEquals(source.reader, selected.reader)
        assertFalse("devicePreferences" in WebDavProjection.library(source).getValue(SyncDomain.SETTINGS).getValue("@reader"))
    }

    @Test fun concurrentDevicePreferencesStayAsCompleteAtomicGroup() {
        val original = LibraryState()
        fun projection(state: LibraryState) = WebDavProjection.library(state, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true)
        val baseline = SyncReplica(deviceId = "a").track(emptyMap(), projection(original)).bind("dataset")
        val first = original.copy(reader = original.reader.withEInkMode(true))
        val second = original.copy(reader = original.reader.copy(brightness = .5f, keepScreenOn = true))
        val a = baseline.track(projection(original), projection(first)).documents.getValue(SyncDomain.SETTINGS)
        val b = baseline.copy(deviceId = "b").track(projection(original), projection(second)).documents.getValue(SyncDomain.SETTINGS)
        val merged = WebDavMerge.merge(a, b)
        assertEquals(2, merged.records.getValue("@reader").fields.getValue("devicePreferences").candidates.size)
        val result = WebDavProjection.applyLibrary(original, mapOf(SyncDomain.SETTINGS to merged), setOf(SyncDomain.SETTINGS), includeDevicePreferences = true)
        assertTrue(result.reader == first.reader || result.reader == second.reader)
    }

    @Test fun concurrentBlockedMembersAreCombinedAndObservedDeletionDoesNotRevive() {
        val original = LibraryState(blockedTags = setOf("旧标签"))
        fun projection(state: LibraryState) = WebDavProjection.library(state, setOf(SyncDomain.SETTINGS))
        val baseline = SyncReplica(deviceId = "a").track(emptyMap(), projection(original)).bind("dataset")
        val first = original.copy(blockedTags = setOf("甲"), blockedAuthors = setOf("作者甲"), blockedBooks = setOf(web.key))
        val second = original.copy(blockedTags = setOf("旧标签", "乙"), blockedAuthors = setOf("作者乙"), blockedUsers = setOf("用户"))
        val a = baseline.track(projection(original), projection(first)).documents.getValue(SyncDomain.SETTINGS)
        val b = baseline.copy(deviceId = "b").track(projection(original), projection(second)).documents.getValue(SyncDomain.SETTINGS)
        val merged = WebDavMerge.merge(a, b)
        val result = WebDavProjection.applyLibrary(original, mapOf(SyncDomain.SETTINGS to merged), setOf(SyncDomain.SETTINGS))
        assertEquals(setOf("甲", "乙"), result.blockedTags)
        assertEquals(setOf("作者甲", "作者乙"), result.blockedAuthors)
        assertEquals(setOf(web.key), result.blockedBooks)
        assertEquals(setOf("用户"), result.blockedUsers)
        val oldReplica = baseline.documents.getValue(SyncDomain.SETTINGS)
        val final = WebDavMerge.merge(merged, oldReplica)
        assertEquals(result.blockedTags, WebDavProjection.applyLibrary(original, mapOf(SyncDomain.SETTINGS to final), setOf(SyncDomain.SETTINGS)).blockedTags)
        assertEquals(original.blockedTags, WebDavProjection.applyLibrary(original, mapOf(SyncDomain.SETTINGS to merged), setOf(SyncDomain.HISTORY)).blockedTags)
    }

    @Test fun readingContentHashIsPortableAndStrictlyValidatedWithLegacyCompatibility() {
        val hash = "a".repeat(64)
        val state = LibraryState(positions = mapOf(web.key to Position("c", sourceParagraph = 0, anchorTextHash = hash)))
        val document = documents(state).getValue(SyncDomain.PROGRESS)
        val result = WebDavProjection.applyLibrary(LibraryState(), mapOf(SyncDomain.PROGRESS to document), setOf(SyncDomain.PROGRESS))
        assertEquals(hash, result.positions.getValue(web.key).anchorTextHash)
        val position = WebDavMerge.materialize(document).getValue(web.key).getValue("position") as JsonObject
        WebDavProjection.validateField(SyncDomain.PROGRESS, web.key, "position", JsonObject(position - "anchorTextHash"))
        assertThrows(IllegalArgumentException::class.java) {
            WebDavProjection.validateField(SyncDomain.PROGRESS, web.key, "position", JsonObject(position + ("anchorTextHash" to JsonPrimitive("invalid"))))
        }
    }
}

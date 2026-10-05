package cc.novelia.app.data.webdav

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.SavedBook
import java.net.SocketTimeoutException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class WebDavExchangeTest {
    private val dataset = "exchange-dataset"
    private val domain = SyncDomain.NOTES
    private fun note(id: String) = Note(id, "syosetu/book", "chapter", 1, "摘录 $id", "正文 $id", createdAt = 1, bookmarked = false)
    private fun state(vararg ids: String) = LibraryState(notes = ids.map(::note))
    private fun replica(device: String, library: LibraryState, selected: SyncDomain = domain) = SyncReplica(deviceId = device)
        .track(emptyMap(), WebDavProjection.library(library, setOf(selected))).bind(dataset)
    private fun validate(document: SyncDocument) {
        WebDavMerge.validate(document, dataset)
        WebDavProjection.applyLibrary(LibraryState(), mapOf(document.domain to document), setOf(document.domain))
    }

    @Test fun conflictReloadMergesAnotherDeviceAndRecapturesLocalEditsMadeDuringTheRequest() = runBlocking {
        var localState = state("local-a")
        var local = replica("device-a", localState)
        val blankRemote = replica("device-b", state()).documents.getValue(domain)
        val remoteAddition = replica("device-b", state("remote-b")).documents.getValue(domain)
        val reads = mutableListOf<Boolean>()
        val uploads = mutableListOf<Pair<SyncDocument, String?>>()
        val result = exchangeWebDavDocument(
            readRemote = { fresh ->
                reads += fresh
                if(fresh) WebDavCachedDomain("\"remote-v2\"", remoteAddition) else WebDavCachedDomain("\"remote-v1\"", blankRemote)
            },
            captureLocal = { local.documents.getValue(domain) },
            combine = { captured, remote -> remote?.let { WebDavMerge.merge(captured, it) } ?: captured },
            validate = ::validate,
            upload = { merged, etag ->
                uploads += merged to etag
                if(uploads.size == 1) {
                    val newerState = state("local-a", "local-c")
                    local = local.track(WebDavProjection.library(localState, setOf(domain)), WebDavProjection.library(newerState, setOf(domain)))
                    localState = newerState
                    throw WebDavException(WebDavFailure.CONFLICT, "远端已被另一设备修改", 412)
                }
                "\"remote-v3\""
            },
            ensureCurrent = {}
        )
        assertEquals(listOf(false, true), reads)
        assertEquals(listOf("\"remote-v1\"", "\"remote-v2\""), uploads.map { it.second })
        assertEquals(setOf("local-a", "local-c", "remote-b"), WebDavMerge.materialize(result.document).keys)
        assertEquals(local.documents.getValue(domain), result.captured)
        assertEquals("\"remote-v3\"", result.etag)
        assertEquals(result.document, uploads.last().first)
    }

    @Test fun fourConsecutiveConflictsStopWithoutAnUnconditionalUpload() = runBlocking {
        val local = replica("device-a", state("a")).documents.getValue(domain)
        val remote = replica("device-b", state("b")).documents.getValue(domain)
        var reads = 0
        val etags = mutableListOf<String?>()
        val conflict = WebDavException(WebDavFailure.CONFLICT, "持续并发修改", 412)
        try {
            exchangeWebDavDocument(
                readRemote = { reads++; WebDavCachedDomain("\"version-$reads\"", remote) },
                captureLocal = { local }, combine = { own, other -> WebDavMerge.merge(own, requireNotNull(other)) },
                validate = ::validate,
                upload = { _, etag -> etags += etag; throw conflict }, ensureCurrent = {}
            )
            fail("持续冲突应返回错误")
        } catch(error: WebDavException) { assertSame(conflict, error) }
        assertEquals(4, reads)
        assertEquals(4, etags.size)
        assertTrue(etags.all { it != null })
    }

    @Test fun identicalRemoteDocumentReturnsItsEtagWithoutUploading() = runBlocking {
        val document = replica("device-a", state("already-synced")).documents.getValue(domain)
        var uploads = 0
        val result = exchangeWebDavDocument(
            readRemote = { WebDavCachedDomain("\"same\"", document) }, captureLocal = { document },
            combine = { own, other -> WebDavMerge.merge(own, requireNotNull(other)) }, validate = ::validate,
            upload = { _, _ -> uploads++; error("相同内容不应上传") }, ensureCurrent = {}
        )
        assertEquals(0, uploads)
        assertEquals(document, result.document)
        assertEquals("\"same\"", result.etag)
    }

    @Test fun configChangeBeforeCommitPreventsUploadingAndReturningAnAcknowledgement() = runBlocking {
        val local = replica("device-a", state("a")).documents.getValue(domain)
        var uploads = 0
        var acknowledged = false
        val changed = CancellationException("同步配置已更改")
        try {
            exchangeWebDavDocument(
                readRemote = { null }, captureLocal = { local }, combine = { own, _ -> own }, validate = ::validate,
                upload = { _, _ -> uploads++; "\"created\"" }, ensureCurrent = { throw changed }
            )
            acknowledged = true
            fail("配置切换应取消旧请求")
        } catch(error: CancellationException) { assertSame(changed, error) }
        assertEquals(0, uploads)
        assertFalse(acknowledged)
    }

    @Test fun configChangeWhileUploadingPreventsLocalAcknowledgementOfTheOldConfiguration() = runBlocking {
        val local = replica("device-a", state("a")).documents.getValue(domain)
        var current = true
        var uploads = 0
        var acknowledged = false
        try {
            exchangeWebDavDocument(
                readRemote = { null }, captureLocal = { local }, combine = { own, _ -> own }, validate = ::validate,
                upload = { _, _ -> uploads++; current = false; "\"old-config-uploaded\"" },
                ensureCurrent = { if(!current) throw CancellationException("同步配置已更改") }
            )
            acknowledged = true
            fail("旧配置上传完成后也不能确认本地提交")
        } catch(_: CancellationException) { }
        assertEquals(1, uploads)
        assertFalse(acknowledged)
    }

    @Test fun lostUploadResponsePropagatesWithoutRecreatingOrRetryingTheRemoteFavorites() = runBlocking {
        val favorites = SyncDomain.FAVORITES
        val library = LibraryState(books = listOf(SavedBook(BookCard(BookRef("syosetu", "favorite"), "收藏"))))
        val local = replica("device-a", library, favorites).documents.getValue(favorites)
        val blank = replica("device-b", LibraryState(), favorites).documents.getValue(favorites)
        var reads = 0
        var uploads = 0
        var actualRemote = blank
        val timeout = SocketTimeoutException("服务端已保存但响应丢失")
        try {
            exchangeWebDavDocument(
                readRemote = { reads++; WebDavCachedDomain("\"old\"", actualRemote) }, captureLocal = { local },
                combine = { own, other -> WebDavMerge.merge(own, requireNotNull(other)) }, validate = ::validate,
                upload = { merged, etag ->
                    uploads++
                    assertEquals("\"old\"", etag)
                    actualRemote = merged
                    throw timeout
                }, ensureCurrent = {}
            )
            fail("未知上传结果必须留待下一轮重新核对")
        } catch(error: SocketTimeoutException) { assertSame(timeout, error) }
        assertEquals(1, reads)
        assertEquals(1, uploads)
        assertTrue("syosetu/favorite" in WebDavMerge.materialize(actualRemote))
    }

    @Test fun remoteFileDisappearingAfterConflictIsNotRecreatedDuringFirstJoin() = runBlocking {
        val local = replica("device-a", state("a")).documents.getValue(domain)
        val remote = replica("device-b", state("b")).documents.getValue(domain)
        var reads = 0
        var uploads = 0
        try {
            exchangeWebDavDocument(
                readRemote = { fresh -> reads++; if(fresh) null else WebDavCachedDomain("\"before-delete\"", remote) },
                captureLocal = { local }, combine = { own, other -> other?.let { WebDavMerge.merge(own, it) } ?: own },
                validate = ::validate,
                upload = { _, etag ->
                    uploads++
                    if(etag != null) throw WebDavException(WebDavFailure.CONFLICT, "远端文件已删除", 412)
                    "\"recreated\""
                }, ensureCurrent = {}
            )
            fail("曾观察到的远端文件消失后应停止，而不能解释为空数据重建")
        } catch(error: WebDavException) {
            assertEquals(WebDavFailure.INVALID_DATA, error.failure)
            assertTrue(error.message.orEmpty().isNotBlank())
        }
        assertEquals(2, reads)
        assertEquals(1, uploads)
    }

    @Test fun bootstrapReplaysOnlyTheChangedGlobalFieldAndKeepsOtherRemoteSettings() {
        val before = LibraryState(theme = "light", reducedMotion = false, hideNovelComments = false)
        val after = before.copy(theme = "system")
        val remote = before.copy(theme = "dark", reducedMotion = true, hideNovelComments = true)
        fun projection(value: LibraryState) = WebDavProjection.library(value, setOf(SyncDomain.SETTINGS)).getValue(SyncDomain.SETTINGS)
        val rebased = rebaseProjectionChanges(projection(remote), projection(before), projection(after))
        assertEquals(projection(remote.copy(theme = "system")), rebased)
    }

    @Test fun bootstrapReplaysOneBookReaderFieldWithoutResettingItsRemoteLanguageOrSpacing() {
        val key = "syosetu/book"
        val initialReader = ReaderSettings(mode = "zh", fontSize = 19f, lineHeight = 1.8f)
        val remoteReader = initialReader.copy(mode = "jp", fontSize = 30f, lineHeight = 1.2f)
        val before = LibraryState(bookSettings = mapOf(key to initialReader))
        val after = before.copy(bookSettings = mapOf(key to initialReader.copy(fontSize = 24f)))
        val remote = before.copy(bookSettings = mapOf(key to remoteReader))
        fun projection(value: LibraryState) = WebDavProjection.library(value, setOf(SyncDomain.SETTINGS)).getValue(SyncDomain.SETTINGS)
        val rebased = rebaseProjectionChanges(projection(remote), projection(before), projection(after))
        assertEquals(projection(remote.copy(bookSettings = mapOf(key to remoteReader.copy(fontSize = 24f)))), rebased)
    }

    @Test fun bootstrapReplaysRecordRemovalAndAdditionWithoutDeletingRemoteOnlyBookSettings() {
        val removed = "syosetu/removed"
        val retained = "syosetu/retained"
        val added = "syosetu/added"
        val remoteOnly = "syosetu/remote-only"
        val before = LibraryState(bookSettings = mapOf(removed to ReaderSettings(fontSize = 20f), retained to ReaderSettings(fontSize = 21f)))
        val after = before.copy(bookSettings = before.bookSettings - removed + (added to ReaderSettings(fontSize = 22f)))
        val remote = before.copy(bookSettings = before.bookSettings + (remoteOnly to ReaderSettings(fontSize = 23f)), theme = "dark")
        fun projection(value: LibraryState) = WebDavProjection.library(value, setOf(SyncDomain.SETTINGS)).getValue(SyncDomain.SETTINGS)
        val rebased = rebaseProjectionChanges(projection(remote), projection(before), projection(after))
        val expected = remote.copy(bookSettings = remote.bookSettings - removed + (added to ReaderSettings(fontSize = 22f)))
        assertEquals(projection(expected), rebased)
    }

    @Test fun bootstrapFieldRemovalPreservesOtherFieldsEvenIfTheyWereAbsentLocally() {
        val key = "@global"
        val before = mapOf(key to JsonObject(mapOf("changed" to JsonPrimitive("old"), "untouched" to JsonPrimitive("local"))))
        val after = mapOf(key to JsonObject(mapOf("untouched" to JsonPrimitive("local"))))
        val base = mapOf(key to JsonObject(mapOf("changed" to JsonPrimitive("remote"), "untouched" to JsonPrimitive("remote"),
            "remoteOnly" to JsonPrimitive("retained"))))
        val result = rebaseProjectionChanges(base, before, after).getValue(key)
        assertFalse("changed" in result)
        assertEquals(JsonPrimitive("remote"), result["untouched"])
        assertEquals(JsonPrimitive("retained"), result["remoteOnly"])
    }

    @Test fun editingALocalOnlyBookSettingDuringBootstrapRecreatesACompleteReaderRecord() {
        val key = "syosetu/local-only-settings"
        val before = LibraryState(bookSettings = mapOf(key to ReaderSettings(mode = "jp", fontSize = 21f)))
        val after = before.copy(bookSettings = mapOf(key to before.bookSettings.getValue(key).copy(fontSize = 25f)))
        val remote = LibraryState(theme = "dark")
        fun projection(value: LibraryState) = WebDavProjection.library(value, setOf(SyncDomain.SETTINGS)).getValue(SyncDomain.SETTINGS)
        val rebased = rebaseProjectionChanges(projection(remote), projection(before), projection(after))
        val expected = remote.copy(bookSettings = after.bookSettings)
        assertEquals(projection(expected), rebased)
        val document = SyncReplica(deviceId = "rebased-device").track(emptyMap(), mapOf(SyncDomain.SETTINGS to rebased))
            .bind(dataset).documents.getValue(SyncDomain.SETTINGS)
        validate(document)
    }

    @Test fun reservingAnUploadClockDoesNotApplyItsBusinessDocumentAndFutureEditsUseHigherCounters() {
        val original = state("existing")
        val local = replica("device-a", original)
        val preparedState = state("existing", "uncommitted-upload")
        val preparedReplica = local.track(WebDavProjection.library(original, setOf(domain)),
            WebDavProjection.library(preparedState, setOf(domain)))
        val uploadDocument = preparedReplica.documents.getValue(domain)
        assertTrue(preparedReplica.clock > local.clock)
        val reserved = reserveWebDavClock(local, uploadDocument)
        assertEquals(local.deviceId, reserved.deviceId)
        assertEquals(local.documents, reserved.documents)
        assertEquals(preparedReplica.clock, reserved.clock)
        assertFalse("uncommitted-upload" in WebDavMerge.materialize(reserved.documents.getValue(domain)))
        reserved.validate()
        val laterState = state("existing", "next-user-edit")
        val later = reserved.track(WebDavProjection.library(original, setOf(domain)),
            WebDavProjection.library(laterState, setOf(domain)))
        assertTrue(later.clock > reserved.clock)
        val laterDocument = later.documents.getValue(domain)
        assertTrue(laterDocument.records.getValue("next-user-edit").existence.candidates.single().counter > reserved.clock)
        assertFalse("uncommitted-upload" in WebDavMerge.materialize(laterDocument))
        validate(laterDocument)
    }

    @Test fun suspendedCaptureFlushFailurePreventsEveryUploadAndAcknowledgement() = runBlocking {
        val remote = replica("device-b", state("remote")).documents.getValue(domain)
        var uploads = 0
        var combined = 0
        var acknowledged = false
        val failure = IOException("本地捕获版本无法落盘")
        try {
            exchangeWebDavDocument(
                readRemote = { WebDavCachedDomain("\"remote\"", remote) },
                captureLocal = { yield(); throw failure },
                combine = { own, other -> combined++; WebDavMerge.merge(own, requireNotNull(other)) }, validate = ::validate,
                upload = { _, _ -> uploads++; "\"must-not-upload\"" }, ensureCurrent = {}
            )
            acknowledged = true
            fail("未持久化的捕获版本不能进入网络提交")
        } catch(error: IOException) { assertSame(failure, error) }
        assertEquals(0, uploads)
        assertEquals(0, combined)
        assertFalse(acknowledged)
    }

    @Test fun versionAwareRebaseKeepsAnExplicitEditThatReturnsToItsCapturedValue() {
        val initial = LibraryState(theme = "light", reducedMotion = false)
        val intermediate = initial.copy(theme = "system")
        val remote = initial.copy(theme = "dark", reducedMotion = true)
        fun projection(value: LibraryState) = WebDavProjection.library(value, setOf(SyncDomain.SETTINGS))
        val before = replica("device-a", initial, SyncDomain.SETTINGS)
        val after = before.track(projection(initial), projection(intermediate)).track(projection(intermediate), projection(initial))
        val beforeDocument = before.documents.getValue(SyncDomain.SETTINGS)
        val afterDocument = after.documents.getValue(SyncDomain.SETTINGS)
        assertEquals(WebDavMerge.materialize(beforeDocument), WebDavMerge.materialize(afterDocument))
        assertNotEquals(beforeDocument.records.getValue("@global").fields.getValue("theme"), afterDocument.records.getValue("@global").fields.getValue("theme"))
        val result = rebaseDocumentChanges(projection(remote).getValue(SyncDomain.SETTINGS), beforeDocument, afterDocument)
        assertEquals(projection(remote.copy(theme = "light")).getValue(SyncDomain.SETTINGS), result)
    }

    @Test fun versionAwareRebaseRestoresTheWholeRecordAfterDeletionAndRecreation() {
        val key = "syosetu/restored-setting"
        val initial = LibraryState(bookSettings = mapOf(key to ReaderSettings(mode = "jp", fontSize = 21f)))
        val deleted = initial.copy(bookSettings = emptyMap())
        val remote = initial.copy(bookSettings = mapOf(key to ReaderSettings(mode = "zh", fontSize = 30f)), theme = "dark")
        fun projection(value: LibraryState) = WebDavProjection.library(value, setOf(SyncDomain.SETTINGS))
        val before = replica("device-a", initial, SyncDomain.SETTINGS)
        val after = before.track(projection(initial), projection(deleted)).track(projection(deleted), projection(initial))
        val beforeDocument = before.documents.getValue(SyncDomain.SETTINGS)
        val afterDocument = after.documents.getValue(SyncDomain.SETTINGS)
        assertEquals(WebDavMerge.materialize(beforeDocument), WebDavMerge.materialize(afterDocument))
        assertNotEquals(beforeDocument.records.getValue("@book/$key").existence, afterDocument.records.getValue("@book/$key").existence)
        val base = projection(remote).getValue(SyncDomain.SETTINGS)
        val result = rebaseDocumentChanges(base, beforeDocument, afterDocument)
        val expected = projection(remote.copy(bookSettings = initial.bookSettings)).getValue(SyncDomain.SETTINGS)
        assertEquals(expected, result)
        assertEquals(expected, rebaseDocumentChanges(base - "@book/$key", beforeDocument, afterDocument))
        val document = SyncReplica(deviceId = "restored-device").track(emptyMap(), mapOf(SyncDomain.SETTINGS to result)).bind(dataset)
            .documents.getValue(SyncDomain.SETTINGS)
        validate(document)
    }

    @Test fun versionAwareRebaseDoesNotTreatClockOrContextReservationsAsUserEdits() {
        val original = LibraryState(theme = "light")
        val remote = original.copy(theme = "dark", reducedMotion = true)
        val before = replica("device-a", original, SyncDomain.SETTINGS)
        val beforeDocument = before.documents.getValue(SyncDomain.SETTINGS)
        val reserved = reserveWebDavClock(before, beforeDocument.copy(context = beforeDocument.context + (before.deviceId to before.clock + 50)))
        val afterDocument = reserved.documents.getValue(SyncDomain.SETTINGS).copy(context = beforeDocument.context + (before.deviceId to reserved.clock))
        val base = WebDavProjection.library(remote, setOf(SyncDomain.SETTINGS)).getValue(SyncDomain.SETTINGS)
        assertEquals(beforeDocument.records, afterDocument.records)
        assertEquals(base, rebaseDocumentChanges(base, beforeDocument, afterDocument))
    }
}

package cc.novelia.app.data.appupdate

import cc.novelia.app.data.storage.appJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class AppReleaseDownloadCoordinatorTest {
    private fun candidate(channel: AppReleaseChannel = AppReleaseChannel.Stable, version: String = "0.3.0", id: Long = 1) =
        AppDownloadCandidate(channel, version, AppReleaseAsset("Novelia-universal.apk", "$APP_RELEASES_URL/download/preview/Novelia-universal.apk",
            size = 1000, state = "uploaded", id = id), versionCode = 2, run = id, commit = id.toString().padStart(40, '0'))

    private class Store : AppDownloadRecordStore {
        var json = "{}"
        override suspend fun load(): Map<AppReleaseChannel, AppDownloadRecord> = appJson.decodeFromString(json)
        override suspend fun save(records: Map<AppReleaseChannel, AppDownloadRecord>) { json = appJson.encodeToString(records) }
    }
    private class Backend : AppDownloadBackend {
        var starts = 0L
        var broken = false
        val transfers = mutableMapOf<Long, AppTransfer>()
        val removed = mutableListOf<Long>()
        override suspend fun enqueue(candidate: AppDownloadCandidate): AppDownloadRecord {
            val id = ++starts
            transfers[id] = AppTransfer(AppTransferStatus.Pending)
            return AppDownloadRecord(candidate, id, "$id.apk")
        }
        override suspend fun query(record: AppDownloadRecord) = transfers[record.downloadId] ?: AppTransfer(AppTransferStatus.Missing)
        override suspend fun verify(record: AppDownloadRecord) { if(broken) throw AppReleaseException("校验失败") }
        override suspend fun remove(record: AppDownloadRecord) { removed += record.downloadId; transfers.remove(record.downloadId) }
    }

    @Test fun stableButtonDoesNotDownloadSameOrOlderVersion() = runBlocking {
        val backend = Backend()
        for(version in listOf("0.2.0", "0.1.9")) {
            val service = AppReleaseDownloadCoordinator({ candidate(version = version) }, { InstalledAppRelease("0.2.0", 1) }, backend, Store())
            assertNull(service.check(AppReleaseChannel.Stable))
            assertEquals(AppDownloadPhase.Current, service.states.value.getValue(AppReleaseChannel.Stable).phase)
        }
        assertEquals(0L, backend.starts)
    }

    @Test fun previewRequiresConsentAndDoesNotRedownloadInstalledOrOlderBuilds() = runBlocking {
        val backend = Backend()
        val preview = candidate(AppReleaseChannel.Preview, "0.2.0", 11)
        val installed = InstalledAppRelease("0.2.0", 2, run = 10)
        val service = AppReleaseDownloadCoordinator({ preview }, { installed }, backend, Store())
        service.check(AppReleaseChannel.Preview)
        assertEquals(AppDownloadPhase.PreviewConsent, service.states.value.getValue(AppReleaseChannel.Preview).phase)
        assertEquals(0L, backend.starts)
        service.dismiss()
        assertEquals(0L, backend.starts)
        service.check(AppReleaseChannel.Preview)
        service.confirmPreview()
        service.confirmPreview()
        assertEquals(1L, backend.starts)
        assertFalse(preview.copy(run = 10).isNewerThan(installed))
        assertFalse(preview.copy(run = 9).isNewerThan(installed))
        assertFalse(preview.copy(versionCode = 1).isNewerThan(installed))
        assertFalse(preview.isNewerThan(installed.copy(run = 0, commit = preview.commit)))
        assertTrue(preview.copy(run = 10, attempt = 2).isNewerThan(installed))
        assertFalse(preview.copy(run = 10, attempt = 1).isNewerThan(installed.copy(attempt = 2)))
        val hash = "a".repeat(64)
        assertFalse(preview.copy(apk = preview.apk.copy(digest = "sha256:$hash")).isNewerThan(installed.copy(run = 0, apkSha256 = hash)))
    }

    @Test fun progressCompletionLaterRestartAndClickInstallReuseTheSameFile() = runBlocking {
        val backend = Backend(); val store = Store(); var requests = 0
        val loader: suspend (AppReleaseChannel) -> AppDownloadCandidate = { requests++; candidate() }
        fun service() = AppReleaseDownloadCoordinator(loader, { InstalledAppRelease("0.2.0", 1) }, backend, store)
        var service = service()
        service.check(AppReleaseChannel.Stable)
        backend.transfers[1] = AppTransfer(AppTransferStatus.Running, 400, 1000)
        service.refresh()
        assertEquals(.4f, service.states.value.getValue(AppReleaseChannel.Stable).progress!!, .001f)
        service.dismiss()
        backend.transfers[1] = AppTransfer(AppTransferStatus.Complete, 1000, 1000)
        service.refresh()
        assertEquals(AppReleaseChannel.Stable, service.dialog.value)
        assertEquals(AppDownloadPhase.Ready, service.states.value.getValue(AppReleaseChannel.Stable).phase)
        service.dismiss()
        service = service()
        service.refresh()
        assertNull(service.dialog.value)
        assertEquals(AppDownloadPhase.Ready, service.states.value.getValue(AppReleaseChannel.Stable).phase)
        assertEquals(1L, service.check(AppReleaseChannel.Stable)!!.downloadId)
        assertEquals(2, requests)
        assertEquals(1L, backend.starts)
    }

    @Test fun rollingPreviewAtSameUrlDownloadsAgainWhenTheAssetChanges() = runBlocking {
        val backend = Backend(); val store = Store()
        var latest = candidate(AppReleaseChannel.Preview)
        val service = AppReleaseDownloadCoordinator({ latest }, { InstalledAppRelease("0.2.0", 1) }, backend, store)
        service.check(AppReleaseChannel.Preview); service.confirmPreview()
        backend.transfers[1] = AppTransfer(AppTransferStatus.Complete)
        service.refresh(); service.dismiss()
        latest = latest.copy(apk = latest.apk.copy(id = 2), run = 2)
        assertNull(service.check(AppReleaseChannel.Preview))
        assertEquals(AppDownloadPhase.PreviewConsent, service.states.value.getValue(AppReleaseChannel.Preview).phase)
        assertEquals(1L, backend.starts)
        service.confirmPreview()
        assertEquals(2L, backend.starts)
        assertEquals(listOf(1L), backend.removed)
    }

    @Test fun aNewStableReleaseReplacesTheDeferredInstaller() = runBlocking {
        val backend = Backend(); var latest = candidate()
        val service = AppReleaseDownloadCoordinator({ latest }, { InstalledAppRelease("0.2.0", 1) }, backend, Store())
        service.check(AppReleaseChannel.Stable)
        backend.transfers[1] = AppTransfer(AppTransferStatus.Complete)
        service.refresh(); service.dismiss()
        latest = candidate(version = "0.4.0", id = 2)
        assertNull(service.check(AppReleaseChannel.Stable))
        assertEquals(2L, backend.starts)
        assertEquals(AppDownloadPhase.Downloading, service.states.value.getValue(AppReleaseChannel.Stable).phase)
    }

    @Test fun failedMissingAndCorruptDownloadsCanBeRetriedWithoutInstallingThem() = runBlocking {
        val backend = Backend()
        val service = AppReleaseDownloadCoordinator({ candidate() }, { InstalledAppRelease("0.2.0", 1) }, backend, Store())
        service.check(AppReleaseChannel.Stable)
        backend.transfers[1] = AppTransfer(AppTransferStatus.Failed)
        service.refresh()
        assertEquals(AppDownloadPhase.Failed, service.states.value.getValue(AppReleaseChannel.Stable).phase)
        service.check(AppReleaseChannel.Stable)
        backend.transfers.remove(2)
        service.check(AppReleaseChannel.Stable)
        backend.transfers[3] = AppTransfer(AppTransferStatus.Complete); backend.broken = true
        service.refresh()
        assertEquals(AppDownloadPhase.Failed, service.states.value.getValue(AppReleaseChannel.Stable).phase)
        assertNull(service.check(AppReleaseChannel.Stable))
        assertEquals(4L, backend.starts)
    }

    @Test fun repeatedClickWhileDownloadingDoesNotEnqueueTwiceAndRestoresAfterDeath() = runBlocking {
        val backend = Backend(); val store = Store()
        fun service() = AppReleaseDownloadCoordinator({ candidate() }, { InstalledAppRelease("0.2.0", 1) }, backend, store)
        val first = service(); first.check(AppReleaseChannel.Stable)
        first.check(AppReleaseChannel.Stable)
        val second = service(); second.refresh(); second.check(AppReleaseChannel.Stable)
        assertEquals(1L, backend.starts)
        assertEquals(AppDownloadPhase.Downloading, second.states.value.getValue(AppReleaseChannel.Stable).phase)
    }

    @Test fun installationFailureIsVisibleAndUpgradeDoesNotOfferTheOldPackageAgain() = runBlocking {
        val backend = Backend(); val store = Store(); var installed = InstalledAppRelease("0.2.0", 1)
        fun service() = AppReleaseDownloadCoordinator({ candidate() }, { installed }, backend, store)
        val first = service(); first.check(AppReleaseChannel.Stable)
        backend.transfers[1] = AppTransfer(AppTransferStatus.Complete)
        first.refresh()
        first.reportError(AppReleaseChannel.Stable, AppReleaseException("需要安装权限"))
        first.refresh()
        assertEquals("需要安装权限", first.states.value.getValue(AppReleaseChannel.Stable).message)
        installed = InstalledAppRelease("0.3.0", 2)
        val second = service(); second.refresh()
        assertNull(second.dialog.value)
        assertEquals(AppDownloadPhase.Current, second.states.value.getValue(AppReleaseChannel.Stable).phase)
    }

    @Test fun missingPreviewMetadataFailsClosedAndCancellationPropagates() = runBlocking {
        try { parsePreviewCandidate(candidate().apk, "versionName=0.3.0"); fail("Incomplete build metadata") }
        catch(_: AppReleaseException) { }
        val backend = Backend()
        val cancelled = AppReleaseDownloadCoordinator({ throw CancellationException() }, { InstalledAppRelease("0.2.0", 1) }, backend, Store())
        try { cancelled.check(AppReleaseChannel.Preview); fail("Expected cancellation") }
        catch(_: CancellationException) { }
        assertEquals(0L, backend.starts)
    }
}

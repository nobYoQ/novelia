package cc.novelia.app.ui.navigation

import cc.novelia.app.data.updates.AppDownloadPhase
import cc.novelia.app.data.updates.AppReleaseChannel

internal fun AppController.downloadAppRelease(preview: Boolean = false, onQueued: () -> Unit = {}) {
    val downloads = app.appReleaseDownloads
    val channel = if(preview) AppReleaseChannel.Preview else AppReleaseChannel.Stable
    if(downloads.coordinator.states.value[channel]?.phase == AppDownloadPhase.Checking) return
    onQueued()
    action {
        if(downloads.coordinator.check(channel) != null) downloads.requestInstall(channel)
    }
}

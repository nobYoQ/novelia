package cc.novelia.app.data.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.updates.AppRelease
import cc.novelia.app.data.updates.AppUpdateChecker
import cc.novelia.app.data.updates.APP_RELEASES_URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppUpdateCheckerTest {
    @Test fun throttlesChecksAndPersistsLaterAndPerVersionIgnore() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$name-$suffix", mode)
        }
        var time = 1_000L
        var requests = 0
        var release = AppRelease("v0.3.0", "$APP_RELEASES_URL/tag/v0.3.0")
        val loader: suspend () -> AppRelease = { requests++; release }
        var checker = AppUpdateChecker(context, loader, "0.2.6") { time }
        checker.check()
        assertEquals(release, checker.available.value)
        checker.check()
        assertEquals(1, requests)
        checker.later()
        assertNull(checker.available.value)
        checker = AppUpdateChecker(context, loader, "0.2.6") { time }
        checker.check()
        assertEquals(1, requests)
        time += 24 * 60 * 60_000L
        checker.check()
        assertEquals(release, checker.available.value)
        checker.ignore(release)
        time += 24 * 60 * 60_000L
        checker.check()
        assertNull(checker.available.value)
        release = AppRelease("v0.4.0", "$APP_RELEASES_URL/tag/v0.4.0")
        time += 24 * 60 * 60_000L
        checker.check()
        assertEquals(release, checker.available.value)
    }
}

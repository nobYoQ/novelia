package cc.novelia.app.data.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.updates.AppRelease
import cc.novelia.app.data.updates.AppUpdateChecker
import cc.novelia.app.data.updates.APP_RELEASES_URL
import java.util.UUID
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppUpdateCheckerTest {
    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        return object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$name-$suffix", mode)
        }
    }

    @Test fun everyStartupChecksEvenWithLegacyThrottleAndPersistsReminderChoices() = runBlocking {
        val context = isolatedContext()
        context.getSharedPreferences("app-release-updates", Context.MODE_PRIVATE).edit()
            .putLong("nextCheckAt", Long.MAX_VALUE).apply()
        var time = 1_000L
        var requests = 0
        var release = AppRelease("v0.3.0", "$APP_RELEASES_URL/tag/v0.3.0")
        val loader: suspend () -> AppRelease = { requests++; release }
        var checker = AppUpdateChecker(context, loader, "0.2.6") { time }
        checker.check()
        assertEquals(release, checker.available.value)
        checker.check()
        assertEquals(2, requests)
        checker.later()
        assertNull(checker.available.value)
        checker = AppUpdateChecker(context, loader, "0.2.6") { time }
        checker.check()
        assertEquals(3, requests)
        assertNull(checker.available.value)
        time += 24 * 60 * 60_000L
        checker.check()
        assertEquals(release, checker.available.value)
        checker.ignore(release)
        checker = AppUpdateChecker(context, loader, "0.2.6") { time }
        checker.check()
        assertNull(checker.available.value)
        release = AppRelease("v0.4.0", "$APP_RELEASES_URL/tag/v0.4.0")
        checker.check()
        assertEquals(release, checker.available.value)
    }

    @Test fun disabledAutomaticChecksMakeNoRequestsButManualChecksStillWork() = runBlocking {
        var enabled = false
        var requests = 0
        val release = AppRelease("v0.3.0", "$APP_RELEASES_URL/tag/v0.3.0")
        val checker = AppUpdateChecker(isolatedContext(), { requests++; release }, "0.2.6",
            automaticChecksEnabled = { enabled })
        assertNull(checker.check())
        assertEquals(0, requests)
        assertEquals(release, checker.check(force = true))
        assertEquals(release, checker.available.value)
        assertEquals(1, requests)
        checker.ignore(release)
        assertEquals(release, checker.check(force = true))
        assertEquals(release, checker.available.value)
        checker.later()
        assertEquals(release, checker.check(force = true))
        assertEquals(release, checker.available.value)
        enabled = true
        checker.check()
        assertEquals(4, requests)
        assertNull(checker.available.value)
    }

    @Test fun disablingDuringRequestSuppressesAutomaticPrompt() = runBlocking {
        var enabled = true
        val release = AppRelease("v0.3.0", "$APP_RELEASES_URL/tag/v0.3.0")
        val checker = AppUpdateChecker(isolatedContext(), { enabled = false; release }, "0.2.6",
            automaticChecksEnabled = { enabled })
        checker.check()
        assertNull(checker.available.value)
    }

    @Test fun failedCheckIsSilentAndNextStartupRetriesImmediately() = runBlocking {
        var requests = 0
        val release = AppRelease("v0.3.0", "$APP_RELEASES_URL/tag/v0.3.0")
        val checker = AppUpdateChecker(isolatedContext(), {
            if(++requests == 1) throw IOException("offline")
            release
        }, "0.2.6")
        assertNull(checker.check())
        assertEquals(release, checker.check())
        assertEquals(2, requests)
        assertEquals(release, checker.available.value)
    }

    @Test fun manualFailuresAndCancellationArePropagated() = runBlocking {
        val failure = IOException("offline")
        val checker = AppUpdateChecker(isolatedContext(), { throw failure }, "0.2.6")
        try {
            checker.check(force = true)
            fail("Manual check must report a network error")
        } catch(error: IOException) { assertSame(failure, error) }
        val cancelled = CancellationException("backgrounded")
        val cancelling = AppUpdateChecker(isolatedContext(), { throw cancelled }, "0.2.6")
        try {
            cancelling.check()
            fail("Cancellation must propagate")
        } catch(error: CancellationException) { assertSame(cancelled, error) }
    }
}

package cc.novelia.app.data.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkChangesTest {
    @Test fun callbackBurstIsCoalescedAndLateLostCannotEraseNewDefault() = runTest {
        val snapshots = mutableListOf<NetworkSnapshot>()
        val pending = mutableListOf<Boolean>()
        val changes = NetworkChanges(backgroundScope, pending::add, snapshots::add)
        changes.available(1)
        changes.capabilities(1, setOf(1), true, false, false)
        changes.links(1, listOf("dns:one"))
        advanceTimeBy(200)
        changes.available(2)
        changes.capabilities(2, setOf(0), true, true, false)
        changes.links(2, listOf("dns:two"))
        changes.lost(1)
        changes.links(1, listOf("late"))
        advanceTimeBy(400); runCurrent()
        assertEquals(1, snapshots.size)
        assertEquals(2L, snapshots.single().route?.id)
        assertTrue(snapshots.single().ready)
        assertEquals(false, pending.last())
    }

    @Test fun validationDoesNotRotateButDnsVpnProxyAndLossDo() = runTest {
        var resets = 0
        val connections = NetworkConnections(resetNative = { resets++ })
        val changes = NetworkChanges(backgroundScope, connections::pending, connections::update)
        fun connect() {
            changes.available(1)
            changes.capabilities(1, setOf(1), true, false, false)
            changes.links(1, listOf("dns:one"))
        }
        connect(); advanceTimeBy(400); runCurrent()
        assertEquals(1, resets)
        connect()
        changes.capabilities(1, setOf(1), true, true, false)
        advanceTimeBy(400); runCurrent()
        assertEquals(1, resets)
        changes.links(1, listOf("dns:two"))
        advanceTimeBy(400); runCurrent()
        changes.capabilities(1, setOf(4), true, true, false)
        advanceTimeBy(400); runCurrent()
        changes.proxy("synthetic-proxy")
        advanceTimeBy(400); runCurrent()
        changes.proxy("synthetic-proxy")
        advanceTimeBy(400); runCurrent()
        assertEquals(4, resets)
        changes.proxy(null)
        advanceTimeBy(400); runCurrent()
        changes.lost(1)
        advanceTimeBy(400); runCurrent()
        assertEquals(6, resets)
    }

    @Test fun availabilityAloneAndCaptivePortalAreNotReady() = runTest {
        val snapshots = mutableListOf<NetworkSnapshot>()
        val changes = NetworkChanges(backgroundScope, {}, snapshots::add)
        changes.available(1)
        advanceTimeBy(400); runCurrent()
        assertFalse(snapshots.last().ready)
        changes.links(1, emptyList())
        changes.capabilities(1, setOf(1), true, false, true)
        advanceTimeBy(400); runCurrent()
        assertFalse(snapshots.last().ready)
        changes.capabilities(1, setOf(1), true, false, false)
        advanceTimeBy(400); runCurrent()
        // Some usable networks cannot reach Android's validation endpoint.
        assertTrue(snapshots.last().ready)
        changes.close()
    }
}

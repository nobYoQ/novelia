package cc.novelia.app.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Only routing properties belong here: signal strength and validation must not churn pools. */
internal data class NetworkRoute(val id: Long, val transports: Set<Int> = emptySet(),
    val links: List<String>? = null, val proxy: String? = null)

internal data class NetworkSnapshot(val route: NetworkRoute? = null, val internet: Boolean = false,
    val validated: Boolean = false, val captivePortal: Boolean = false) {
    val ready get() = route?.links != null && internet && !captivePortal
}

/** Serializes callbacks, ignores late events from the former default, and coalesces a callback burst. */
internal class NetworkChanges(private val scope: CoroutineScope,
    private val pending: (Boolean) -> Unit, private val apply: (NetworkSnapshot) -> Unit,
    private val debounceMillis: Long = 400) {
    private var snapshot = NetworkSnapshot()
    private var committed = snapshot
    private var proxy: String? = null
    private var job: Job? = null
    private var revision = 0L

    @Synchronized fun available(id: Long) {
        if (snapshot.route?.id == id) return
        update(NetworkSnapshot(NetworkRoute(id, proxy = proxy)))
    }

    @Synchronized fun lost(id: Long) {
        if (snapshot.route?.id == id) update(NetworkSnapshot())
    }

    @Synchronized fun capabilities(id: Long, transports: Set<Int>, internet: Boolean,
        validated: Boolean, captivePortal: Boolean) {
        val route = snapshot.route?.takeIf { it.id == id } ?: return
        update(snapshot.copy(route = route.copy(transports = transports), internet = internet,
            validated = validated, captivePortal = captivePortal))
    }

    @Synchronized fun links(id: Long, links: List<String>) {
        val route = snapshot.route?.takeIf { it.id == id } ?: return
        update(snapshot.copy(route = route.copy(links = links)))
    }

    @Synchronized fun proxy(value: String?) {
        if (proxy == value) return
        proxy = value
        snapshot.route?.let { update(snapshot.copy(route = it.copy(proxy = value))) }
    }

    private fun update(next: NetworkSnapshot) {
        if (snapshot == next) return
        snapshot = next
        val version = ++revision
        job?.cancel()
        pending(next.route != committed.route)
        job = scope.launch {
            delay(debounceMillis)
            synchronized(this@NetworkChanges) {
                if (version == revision) {
                    apply(snapshot)
                    committed = snapshot
                    pending(false)
                }
            }
        }
    }

    @Synchronized fun close() { revision++; job?.cancel(); pending(false) }
}

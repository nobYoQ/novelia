package cc.novelia.app.data.network

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestRecoveryTest {
    @Test fun transientReadHasOneReplacementAndOneBudget() = runTest {
        var attempts = 0
        val result = withRequestBudget(2_000) {
            recoverRead(null) { attempts++; if (attempts == 1) throw IOException("synthetic disconnect"); "content" }
        }
        assertEquals("content", result)
        assertEquals(2, attempts)
        attempts = 0
        val error = runCatching {
            withRequestBudget(200) { recoverRead(null) { attempts++; delay(150); throw IOException("synthetic disconnect") } }
        }.exceptionOrNull()
        assertTrue(error is SocketTimeoutException)
        assertEquals(1, attempts)
    }

    @Test fun repeatedFailuresAreBoundedAndHttpTlsErrorsAreNeverRetried() = runTest {
        for (error in listOf(IOException("temporary"), ApiException(403, "denied"), SSLHandshakeException("untrusted"),
            EchIOException("系统代理需要认证", false))) {
            var attempts = 0
            val actual = runCatching { recoverRead(null) { attempts++; throw error } }.exceptionOrNull()
            assertSame(error, actual)
            assertEquals(if (error.javaClass == IOException::class.java) 2 else 1, attempts)
        }
    }

    @Test fun cancellingConnectivityWaitClearsReconnectStateAndCannotRetry() = runTest {
        val connections = NetworkConnections(resetNative = {})
        connections.update(NetworkSnapshot(NetworkRoute(1, links = emptyList()), internet = true))
        var attempts = 0
        val task = async {
            recoverRead(connections) {
                attempts++
                connections.update(NetworkSnapshot())
                throw NetworkChangedException(connections, 1, IOException("lost"))
            }
        }
        runCurrent()
        assertEquals(1, connections.state.value.recovering)
        task.cancelAndJoin()
        connections.update(NetworkSnapshot(NetworkRoute(2, links = emptyList()), internet = true))
        runCurrent()
        assertEquals(0, connections.state.value.recovering)
        assertEquals(1, attempts)
        assertTrue(task.isCancelled)
    }

    @Test fun successfulNullableResultAndCallerCancellationKeepTheirMeaning() = runTest {
        assertNull(withRequestBudget<String?>(100) { null })
        val error = runCatching { withRequestBudget(100) { throw CancellationException("left page") } }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }
}

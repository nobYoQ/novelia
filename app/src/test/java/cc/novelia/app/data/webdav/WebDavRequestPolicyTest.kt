package cc.novelia.app.data.webdav

import org.junit.Assert.*
import org.junit.Test

class WebDavRequestPolicyTest {
    private val binding = WebDavConfig(endpoint = "https://example.com/dav/", username = "reader",
        generation = 1, enabled = true, datasetId = "dataset")

    @Test fun foregroundAndWorkerTriggersShareOneBudgetDuringContinuousReading() {
        var now = 0L
        val policy = WebDavAutomaticPolicy { now }
        val dirty = setOf(SyncDomain.PROGRESS, SyncDomain.HISTORY)
        val rounds = mutableListOf<Set<SyncDomain>>()
        repeat(180) {
            now = it * 10_000L
            repeat(2) {
                val domains = policy.domains(binding, dirty)
                if(domains.isNotEmpty()) rounds += domains
            }
        }
        assertEquals(15, rounds.size)
        assertEquals(binding.selected, rounds.first())
        assertTrue(rounds.any { it == dirty })
        // 每轮一个 manifest + 实际选中的类型；不含真实写入和服务特有的 ETag 往返。
        assertEquals(85, rounds.sumOf { 1 + it.size })
    }

    @Test fun cleanDataStillPollsRemoteAndQueuedAutomaticWorkAfterManualSyncIsSkipped() {
        var now = 0L
        val policy = WebDavAutomaticPolicy { now }
        assertEquals(binding.selected, policy.domains(binding, emptySet()))
        now = 120_000
        assertTrue(policy.domains(binding, emptySet()).isEmpty())
        now = 180_000
        assertEquals(binding.selected, policy.domains(binding, emptySet()))
        now = 200_000
        policy.completedFullSync(binding)
        assertTrue(policy.domains(binding, setOf(SyncDomain.PROGRESS)).isEmpty())
        now = 320_000
        assertEquals(setOf(SyncDomain.PROGRESS), policy.domains(binding, setOf(SyncDomain.PROGRESS)))
        assertEquals(binding.selected, policy.domains(binding.copy(generation = 2), emptySet()))
    }

    @Test fun cooldownSurvivesRecreationAndDirectoryOrPasswordConfigurationChanges() {
        var now = 1_000_000L
        val saved = mutableMapOf<String, Long>()
        fun cooldown() = WebDavRequestCooldown({ now }, { saved[it] ?: 0 }, { key, until -> saved[key] = until })
        val key = webDavAccountKey(binding)
        cooldown().record(key, WebDavException(WebDavFailure.RATE_LIMITED, "limited", 503, 1_800_000))
        val restarted = cooldown()
        val changed = binding.copy(folder = "another-folder", generation = 5, endpoint = "https://example.com/another/")
        assertEquals(key, webDavAccountKey(changed))
        val error = assertThrows(WebDavException::class.java) { restarted.check(webDavAccountKey(changed)) }
        assertEquals(WebDavFailure.RATE_LIMITED, error.failure)
        assertEquals(1_800_000L, error.retryAfterMillis)
        restarted.check(webDavAccountKey(binding.copy(username = "another-reader")))
        restarted.check(webDavAccountKey(binding.copy(endpoint = "https://another.example.com/dav/")))
        now += 1_800_000
        restarted.check(key)
        assertFalse(key.contains(binding.username))
    }

    @Test fun shorterServerAdviceCannotShortenAnExistingCooldown() {
        val saved = mutableMapOf<String, Long>()
        val cooldown = WebDavRequestCooldown({ 1000 }, { saved[it] ?: 0 }, { key, until -> saved[key] = until })
        cooldown.record("account", WebDavException(WebDavFailure.RATE_LIMITED, "limited", 429, 7_200_000))
        cooldown.record("account", WebDavException(WebDavFailure.RATE_LIMITED, "limited", 429, 60_000))
        assertEquals(7_201_000L, saved["account"])
    }
}

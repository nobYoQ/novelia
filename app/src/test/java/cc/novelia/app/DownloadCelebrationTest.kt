package cc.novelia.app

import cc.novelia.app.data.DownloadEntry
import cc.novelia.app.ui.newlyCompletedDownloads
import org.junit.Assert.*
import org.junit.Test

class DownloadCelebrationTest {
    private fun entry(id: String, status: String) = DownloadEntry(id, "测试小说", "$id.txt", "https://example.invalid/$id", status)

    @Test fun onlyAnObservedTransitionToCompletionCelebrates() {
        val previous = mapOf("active" to "下载中", "old" to "已完成", "failed" to "下载中", "progress" to "等待下载")
        val current = listOf(entry("active", "已完成"), entry("old", "已完成"), entry("restored", "已完成"), entry("failed", "失败"), entry("progress", "下载中"))
        assertEquals(listOf("active"), newlyCompletedDownloads(previous, current).map { it.id })
        assertTrue(newlyCompletedDownloads(current.associate { it.id to it.status }, current).isEmpty())
        assertTrue(newlyCompletedDownloads(emptyMap(), current).isEmpty())
    }
}

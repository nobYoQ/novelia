package cc.novelia.app

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.files.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DocumentImportBatchTest {
    @Test fun failureDoesNotAbortRemainingFilesAndReportsStages() = runTest {
        val snapshots = mutableListOf<List<ImportItem>>()
        val result = runDocumentImportBatch(listOf("bad", "good", "duplicate").map { ImportItem(it, "$it.txt") }, onChange = snapshots::add) { item, stage ->
            stage("正在解析文件")
            if (item.source == "bad") error("无效文件")
            DocumentImportResult(BookRef("local", item.source), item.source != "duplicate")
        }
        assertEquals(listOf(ImportStatus.Failed, ImportStatus.Success, ImportStatus.Duplicate), result.map { it.status })
        assertEquals("无效文件", result.first().detail)
        assertEquals("local/duplicate", result.last().bookKey)
        assertTrue(snapshots.any { it[1].status == ImportStatus.Running && it[1].detail == "正在解析文件" })
        assertTrue(snapshots.first().drop(1).all { it.status == ImportStatus.Pending })
    }

    @Test fun retryProcessesOnlyFailuresAndKeepsSuccessfulResults() = runTest {
        val initial = listOf(
            ImportItem("ok", "成功.txt", ImportStatus.Success, bookKey = "local/ok"),
            ImportItem("dup", "重复.txt", ImportStatus.Duplicate, bookKey = "local/dup"),
            ImportItem("bad", "失败.txt", ImportStatus.Failed),
            ImportItem("pending", "尚未处理.txt"),
        )
        val visited = mutableListOf<String>()
        val result = runDocumentImportBatch(initial, failedOnly = true, onChange = {}) { item, _ ->
            visited += item.source
            DocumentImportResult(BookRef("local", "retried"), true)
        }
        assertEquals(listOf("bad"), visited)
        assertEquals(initial.take(2), result.take(2))
        assertEquals(ImportStatus.Success, result[2].status)
        assertEquals(initial.last(), result.last())
    }

    @Test fun cancellationRetainsPendingFilesAndCanResume() = runTest {
        var snapshot = emptyList<ImportItem>()
        val initial = listOf("one", "two", "three").map { ImportItem(it, it) }
        try {
            runDocumentImportBatch(initial, onChange = { snapshot = it }) { item, _ ->
                if (item.source == "two") throw CancellationException("暂停")
                DocumentImportResult(BookRef("local", item.source), true)
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf(ImportStatus.Success, ImportStatus.Pending, ImportStatus.Pending), snapshot.map { it.status })
        val visited = mutableListOf<String>()
        val result = runDocumentImportBatch(snapshot, onChange = {}) { item, _ ->
            visited += item.source
            DocumentImportResult(BookRef("local", item.source), true)
        }
        assertEquals(listOf("two", "three"), visited)
        assertTrue(result.all { it.status == ImportStatus.Success })
    }
}

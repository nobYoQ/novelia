package cc.novelia.app.data.updates

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ShelfCheckReportTest {
    @Test fun completedReportsDistinguishNewContentNoChangeAndIncompleteCoverage() {
        val cases = listOf(
            ShelfCheckReport(101, 2, 0, 3) to "检查完成：发现 2 本有更新",
            ShelfCheckReport(102, 0, 0, 3) to "检查完成：未发现更新",
            ShelfCheckReport(103, 1, 2, 3) to "发现 1 本有更新，另有 2 本检查失败",
            ShelfCheckReport(104, 0, 1, 3) to "已成功检查的书籍没有更新，另有 1 本检查失败",
            ShelfCheckReport(105, 0, 3, 3) to "检查失败：3 本书均未能检查，请重试",
            ShelfCheckReport(106, 0, 0, 0) to "没有可检查的在线书籍"
        )
        for((report, expected) in cases) {
            assertEquals(report, ShelfCheckReport.decode(report.encode()))
            assertEquals(expected, report.message)
        }
    }

    @Test fun oldNotificationCountsAndMalformedRecordsAreNotTreatedAsCleanScans() {
        assertEquals("上次有 2 本触发更新提醒，1 本检查失败", ShelfCheckReport.decode("42|2|1")?.message)
        assertEquals("上次检查已结束", ShelfCheckReport.decode("42|0|0")?.message)
        for(raw in listOf(null, "", "42", "42|-1|0", "bad|0|0", "0|0|0", "42|0|2|1", "42|2|1|2", "42|0|0|bad"))
            assertNull(ShelfCheckReport.decode(raw))
    }

    @Test fun workStatusUsesActualProgressAndTheTerminalOutputOfThatRun() {
        fun status(state: WorkInfo.State, output: Data = Data.EMPTY, progress: Data = Data.EMPTY) =
            ManualShelfCheck.status(WorkInfo(UUID.randomUUID(), state, emptySet(), outputData = output, progress = progress))
        assertEquals(true, status(WorkInfo.State.ENQUEUED)?.active)
        assertEquals(ShelfCheckStatus("正在检查书架：1 / 4 本", true, .25f),
            status(WorkInfo.State.RUNNING, progress = ManualShelfCheck.progress(1, 4)))
        for((state, report) in listOf(WorkInfo.State.SUCCEEDED to ShelfCheckReport(100, 1, 1, 3),
            WorkInfo.State.FAILED to ShelfCheckReport(200, 0, 3, 3))) {
            assertEquals(ShelfCheckStatus(report.message, finishedAt = report.finishedAt),
                status(state, workDataOf(ShelfCheckReport.KEY to report.encode())))
        }
        assertEquals("书库处于保护状态", status(WorkInfo.State.FAILED, ManualShelfCheck.error("书库处于保护状态"))?.message)
        assertEquals(ShelfCheckStatus("手动检查已取消，可重新检查"), status(WorkInfo.State.CANCELLED))
        assertNull(status(WorkInfo.State.SUCCEEDED))
    }
}

package cc.novelia.app

import cc.novelia.app.files.DownloadFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class DownloadFilesTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun cleanupRemovesAbandonedPartsButPreservesActiveWorkersAndDownloads() {
        val root = folder.newFolder()
        val abandoned = File(root, "task-old-worker.part").apply { writeText("partial") }
        val legacy = File(root, "task.part").apply { writeText("partial") }
        val finished = File(root, "task.epub").apply { writeText("book") }
        val active = DownloadFiles.acquire(root, "task", "new-worker").apply { writeText("active") }
        try {
            DownloadFiles.cleanup(root, "task")
            assertFalse(abandoned.exists())
            assertFalse(legacy.exists())
            assertTrue(finished.exists())
            assertTrue(active.exists())
        } finally { DownloadFiles.release(active) }
        assertFalse(active.exists())
    }

    @Test fun deletionBeforeLateCommitCannotResurrectTheCompletedFile() = runTest {
        val root = folder.newFolder()
        val part = DownloadFiles.acquire(root, "task", "old-worker").apply { writeText("old book") }
        val destination = File(root, "task.epub")
        val deleting = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        var exists = true
        var completed = false
        try {
            val removal = launch {
                DownloadFiles.withTaskLock(root, "task") {
                    exists = false
                    deleting.complete(Unit)
                    finishDelete.await() // The library flush can suspend here.
                    destination.delete()
                    DownloadFiles.cleanup(root, "task")
                }
            }
            deleting.await()
            val commit = async { DownloadFiles.commit(root, "task", part, destination, { exists }) { completed = true } }
            yield()
            assertFalse(commit.isCompleted)
            finishDelete.complete(Unit)
            removal.join()
            assertFalse(commit.await())
            assertFalse(completed)
            assertFalse(destination.exists())
        } finally { DownloadFiles.release(part) }
        assertFalse(part.exists())
    }

    @Test fun retryWaitsForOldRemovalAndRejectsTheOldWorkersCommit() = runTest {
        val root = folder.newFolder()
        val part = DownloadFiles.acquire(root, "task", "old-worker").apply { writeText("old book") }
        val destination = File(root, "task.epub").apply { writeText("previous book") }
        var currentWorker: String? = "old-worker"
        val deleting = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        try {
            val removal = launch {
                DownloadFiles.withTaskLock(root, "task") {
                    currentWorker = null
                    deleting.complete(Unit)
                    finishDelete.await()
                    destination.delete()
                }
            }
            deleting.await()
            val retry = launch {
                DownloadFiles.withTaskLock(root, "task") {
                    currentWorker = "new-worker"
                    destination.writeText("new book")
                }
            }
            yield()
            assertEquals("previous book", destination.readText())
            finishDelete.complete(Unit)
            removal.join(); retry.join()
            assertFalse(DownloadFiles.commit(root, "task", part, destination, { currentWorker == "old-worker" }) { fail("Stale worker completed") })
            assertEquals("new book", destination.readText())
        } finally { DownloadFiles.release(part) }
    }

    @Test fun deletionWaitsForAnAlreadyValidatedCommitThenRemovesItsFile() = runBlocking {
        val root = folder.newFolder()
        val part = DownloadFiles.acquire(root, "task", "worker").apply { writeText("book") }
        val destination = File(root, "task.epub")
        val validated = CountDownLatch(1)
        val finishCommit = CountDownLatch(1)
        val removalStarted = CompletableDeferred<Unit>()
        try {
            val commit = async(Dispatchers.Default) {
                DownloadFiles.commit(root, "task", part, destination, {
                    validated.countDown()
                    check(finishCommit.await(5, TimeUnit.SECONDS))
                    true
                }) {}
            }
            assertTrue(validated.await(5, TimeUnit.SECONDS))
            val removal = launch(Dispatchers.Default) {
                removalStarted.complete(Unit)
                DownloadFiles.withTaskLock(root, "task") { destination.delete() }
            }
            removalStarted.await()
            assertFalse(removal.isCompleted)
            finishCommit.countDown()
            assertTrue(commit.await())
            removal.join()
            assertFalse(destination.exists())
        } finally { finishCommit.countDown(); DownloadFiles.release(part) }
    }

    @Test fun unrelatedDownloadCanCommitWhileAnotherTaskIsBeingRemoved() = runTest {
        val root = folder.newFolder()
        val busy = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val part = DownloadFiles.acquire(root, "other", "worker").apply { writeText("book") }
        try {
            val removal = launch { DownloadFiles.withTaskLock(root, "task") { busy.complete(Unit); release.await() } }
            busy.await()
            assertTrue(DownloadFiles.commit(root, "other", part, File(root, "other.epub"), { true }) {})
            release.complete(Unit)
            removal.join()
        } finally { DownloadFiles.release(part) }
    }
}

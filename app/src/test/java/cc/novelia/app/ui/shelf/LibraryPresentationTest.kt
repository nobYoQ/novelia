package cc.novelia.app.ui.shelf

import cc.novelia.app.data.library.withoutBook
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.ui.components.book.stableCoverVariant
import cc.novelia.app.ui.downloads.downloadRecoveryLabel
import cc.novelia.app.ui.notes.presentNotes
import org.junit.Assert.*
import org.junit.Test

class LibraryPresentationTest {
    @Test fun recoveryActionsDescribeWhatActuallyHappens() {
        assertEquals("登录后继续", downloadRecoveryLabel("需要登录"))
        assertEquals("重新开始", downloadRecoveryLabel("已暂停"))
        assertEquals("重试", downloadRecoveryLabel("失败"))
    }

    @Test fun pendingFavoriteUsesTheCurrentAccountAndExactBookAcrossFolders() {
        val ref = BookRef("syosetu", "n123")
        val pending = listOf(
            PendingAction("other-account", "bob", "DELETE", "user/favored-web/default/syosetu/n123"),
            PendingAction("other-book", "alice", "DELETE", "user/favored-web/default/syosetu/n1234"),
            PendingAction("old", "alice", "DELETE", "user/favored-web/default/syosetu/n123"),
            PendingAction("latest", "alice", "PUT", "user/favored-web/new-folder/syosetu/n123"),
        )
        assertEquals("latest", pendingFavoriteAction(pending, "alice", ref)?.id)
        assertNull(pendingFavoriteAction(pending, "guest", ref))
        assertNull(pendingFavoriteAction(pending, "alice", BookRef("wenku", "n123")))
    }

    @Test fun noteSearchIncludesMetadataAndRespectsTheSelectedBook() {
        val first = BookRef("local", "first")
        val second = BookRef("local", "second")
        val state = LibraryState(
            books = listOf(SavedBook(BookCard(first, "风之旅"))),
            positions = mapOf(first.key to Position("chapter", title = "森林来信")),
            notes = listOf(
                Note("1", first.key, "chapter", 0, "今天的摘录", "关于勇气", createdAt = 1),
                Note("2", second.key, "other", 2, "另一份摘录", "", createdAt = 2, bookTitle = "已移出书架的作品", chapterTitle = "森林之章"),
            ),
        )
        assertEquals(listOf("2", "1"), presentNotes(state, "森林").map { it.note.id })
        assertEquals(listOf("1"), presentNotes(state, "森林", first.key).map { it.note.id })
        assertEquals("风之旅", presentNotes(state, "勇气").single().bookTitle)
        assertEquals("已移出书架的作品", presentNotes(state, "另一份").single().bookTitle)
        assertTrue(presentNotes(state, "没有这个词").isEmpty())
    }

    @Test fun undoRemovalRestoresMountedVolumesWithoutOverwritingNewEdits() {
        val parent = SavedBook(BookCard(BookRef("wenku", "parent"), "文库"), folder = "我的", volumeOrder = listOf("local/one", "local/two"))
        val one = SavedBook(BookCard(BookRef("local", "one"), "一"), parentWenkuKey = parent.book.ref.key)
        val two = SavedBook(BookCard(BookRef("local", "two"), "二"), parentWenkuKey = parent.book.ref.key)
        val before = listOf(parent, one, two)
        val after = LibraryState(books = before).withoutBook(parent.book.ref)
        val concurrent = after.copy(books = after.books.map { if(it.book.ref == two.book.ref) it.copy(folder = "后来移动") else it })
        val restored = restoreRemovedShelfBook(concurrent, before, parent.book.ref)
        assertEquals(parent.copy(volumeOrder = listOf(one.book.ref.key)), restored.books.first { it.book.ref == parent.book.ref })
        assertEquals(parent.book.ref.key, restored.books.first { it.book.ref == one.book.ref }.parentWenkuKey)
        assertNull(restored.books.first { it.book.ref == two.book.ref }.parentWenkuKey)
        assertEquals("后来移动", restored.books.first { it.book.ref == two.book.ref }.folder)
        assertEquals(restored, restoreRemovedShelfBook(restored, before, parent.book.ref))
    }

    @Test fun coverVariantIsStableAndHandlesNegativeHashes() {
        val values = listOf("local/森林来信", "syosetu/n123", "wenku/a", "polygenelubricants")
        values.forEach { key -> assertTrue(stableCoverVariant(key, 6) in 0..5); assertEquals(stableCoverVariant(key, 6), stableCoverVariant(key, 6)) }
        assertTrue((1..100).map { stableCoverVariant("local/$it", 6) }.distinct().size > 2)
    }

    @Test fun undoVolumeRemovalDoesNotRestoreADanglingParent() {
        val parent = SavedBook(BookCard(BookRef("wenku", "series"), "系列"), volumeOrder = listOf("local/volume"))
        val volume = SavedBook(BookCard(BookRef("local", "volume"), "分卷"), parentWenkuKey = parent.book.ref.key)
        val before = listOf(parent, volume)
        val current = LibraryState(books = before).withoutBook(volume.book.ref).withoutBook(parent.book.ref)
        val restored = restoreRemovedShelfBook(current, before, volume.book.ref)
        assertEquals(volume.book.ref, restored.books.single().book.ref)
        assertNull(restored.books.single().parentWenkuKey)
    }

    @Test fun undoParentRemovalKeepsConcurrentUpdatesWhileRestoringItsMounts() {
        val parent = SavedBook(BookCard(BookRef("wenku", "series"), "系列"), volumeOrder = listOf("local/volume"))
        val volume = SavedBook(BookCard(BookRef("local", "volume"), "分卷"), parentWenkuKey = parent.book.ref.key)
        val before = listOf(parent, volume)
        val current = LibraryState(books = before).withoutBook(parent.book.ref).let { state ->
            state.copy(books = state.books.map { it.copy(hasUpdates = true, pinned = true) })
        }
        val restored = restoreRemovedShelfBook(current, before, parent.book.ref)
        val result = restored.books.single { it.book.ref == volume.book.ref }
        assertEquals(parent.book.ref.key, result.parentWenkuKey)
        assertTrue(result.hasUpdates)
        assertTrue(result.pinned)
    }
}

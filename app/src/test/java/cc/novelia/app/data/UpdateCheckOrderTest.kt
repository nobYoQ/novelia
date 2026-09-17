package cc.novelia.app.data

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckOrderTest {
    private fun shelf(count: Int) = (1..count).map { SavedBook(BookCard(BookRef("syosetu", "$it"), "Book $it")) }

    @Test fun everyOnlineBookIncludingTheSixtyFirstIsSelected() {
        val books = shelf(61)
        val local = SavedBook(BookCard(BookRef("local", "local-book"), "Local"))
        assertEquals(books, booksForUpdate(books + local, null))
    }

    @Test fun interruptedRunsResumeAfterLastBookAndStillCoverWholeShelf() {
        val books = shelf(130)
        val secondRun = booksForUpdate(books, books[59].book.ref.key)
        assertEquals(books[60], secondRun.first())
        assertEquals(books.toSet(), secondRun.toSet())
        assertEquals(130, secondRun.size)
        val thirdRun = booksForUpdate(books, secondRun[59].book.ref.key)
        assertEquals(books[120], thirdRun.first())
        assertEquals(books.toSet(), thirdRun.toSet())
    }

    @Test fun removedResumeBookRestartsFromCurrentShelf() {
        val books = shelf(61)
        assertEquals(books, booksForUpdate(books, "syosetu/removed"))
    }
}

package cc.novelia.app

import cc.novelia.app.ui.components.paginationItems
import org.junit.Assert.*
import org.junit.Test

class PageControlsTest {
    @Test fun smallAndEmptyResultsHaveNoGaps() {
        assertEquals(listOf(0), paginationItems(0, 0))
        assertEquals((0..6).toList(), paginationItems(3, 7))
    }

    @Test fun largeResultsExposeEndpointsAndCurrentNeighborhood() {
        assertEquals(listOf(0, 1, 2, 3, null, 34), paginationItems(0, 35))
        assertEquals(listOf(0, null, 16, 17, 18, null, 34), paginationItems(17, 35))
        assertEquals(listOf(0, null, 31, 32, 33, 34), paginationItems(34, 35))
    }

    @Test fun windowsRemainBoundedAndOrderedAtEveryPage() {
        for (count in listOf(1, 7, 8, 35, 1000, Int.MAX_VALUE)) {
            for (page in listOf(-1, 0, 1, count / 2, count - 1, Int.MAX_VALUE)) {
                val items = paginationItems(page, count)
                val numbers = items.filterNotNull()
                assertTrue(items.size <= 7)
                assertEquals(0, numbers.first())
                assertEquals(count - 1, numbers.last())
                assertTrue(page.coerceIn(0, count - 1) in numbers)
                assertEquals(numbers.distinct().sorted(), numbers)
            }
        }
    }
}

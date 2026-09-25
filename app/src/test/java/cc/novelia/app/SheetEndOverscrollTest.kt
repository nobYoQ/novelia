package cc.novelia.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import cc.novelia.app.ui.components.SheetEndOverscrollConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetEndOverscrollTest {
    @Test fun upwardOverflowStopsAtListEndButDownwardDragReachesSheet() = runBlocking {
        var canScrollForward = false
        val connection = SheetEndOverscrollConnection { canScrollForward }
        assertEquals(Offset(0f, -42f), connection.onPostScroll(Offset.Zero, Offset(0f, -42f), NestedScrollSource.UserInput))
        assertEquals(Velocity(0f, -1500f), connection.onPostFling(Velocity.Zero, Velocity(0f, -1500f)))
        assertEquals(Offset.Zero, connection.onPostScroll(Offset.Zero, Offset(0f, 42f), NestedScrollSource.UserInput))
        assertEquals(Velocity.Zero, connection.onPostFling(Velocity.Zero, Velocity(0f, 1500f)))

        canScrollForward = true
        assertEquals(Offset.Zero, connection.onPostScroll(Offset.Zero, Offset(0f, -42f), NestedScrollSource.UserInput))
        assertEquals(Velocity.Zero, connection.onPostFling(Velocity.Zero, Velocity(0f, -1500f)))
    }
}

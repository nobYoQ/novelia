@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.SquareCornerTarget
import cc.novelia.app.ui.components.book.BookCover
import cc.novelia.app.ui.components.book.BookRowStatus
import cc.novelia.app.ui.shelf.ShelfCoverCard
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.theme.appControlShape
import cc.novelia.app.ui.theme.appPanelShape
import cc.novelia.app.ui.theme.appShape
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppCheckbox
import cc.novelia.app.ui.components.base.AppFilledIconButton
import cc.novelia.app.ui.components.base.AppSelectionChip
import cc.novelia.app.ui.components.base.AppSlider
import cc.novelia.app.ui.components.base.AppSwitch

class SquareCornersComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun materialAndCustomShapesChangeTogetherAndRestoreWithoutLosingControlState() {
        var square by mutableStateOf(false)
        var checked by mutableStateOf(false)
        var slider by mutableFloatStateOf(.25f)
        val fill = Color(0xFF008040)
        compose.setContent {
            NoveliaTheme("light", squareCorners = square) {
                Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(Modifier.size(120.dp, 48.dp).testTag("card"), colors = CardDefaults.cardColors(containerColor = fill)) {}
                    AppButton({}, Modifier.size(120.dp, 48.dp).testTag("button"), colors = ButtonDefaults.buttonColors(containerColor = fill)) { Text("按钮") }
                    AppFilledIconButton({}, Modifier.size(48.dp).testTag("icon"), colors = IconButtonDefaults.filledIconButtonColors(containerColor = fill)) {}
                    AppSelectionChip(true, {}, { Text("标签") }, Modifier.testTag("chip"),
                        shape = CircleShape, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = fill))
                    Surface(Modifier.size(48.dp).testTag("avatar"), color = fill, shape = appShape(CircleShape)) {}
                    AppSwitch(checked, { checked = it }, Modifier.testTag("switch"))
                    AppCheckbox(checked, { checked = it }, Modifier.testTag("check"))
                    AppSlider(slider, { slider = it }, Modifier.testTag("slider"))
                }
            }
        }
        for(enabled in listOf(false, true, false)) {
            compose.runOnIdle { square = enabled }
            for(tag in listOf("card", "button", "icon", "avatar")) {
                val pixel = compose.onNodeWithTag(tag).captureToImage().toPixelMap()[2, 2]
                val filled = abs(pixel.red - fill.red) < .02f && abs(pixel.green - fill.green) < .02f && abs(pixel.blue - fill.blue) < .02f
                assertEquals("$tag 的边角应随开关改变", enabled, filled)
            }
            compose.onNodeWithTag("switch").performClick()
            compose.onNodeWithTag("switch").assertIsOn()
            compose.onNodeWithTag("check").assertIsOn().performClick().assertIsOff()
            compose.onNodeWithTag("slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.75f) }
            compose.runOnIdle { assertEquals(.75f, slider, .01f); assertFalse(checked) }
        }
    }

    @Test fun categoriesOnlyUpdateTheirOwnCornersIncludingTheOuterGridCover() {
        var enabled by mutableStateOf(true)
        var targets by mutableStateOf(SquareCornerTarget.defaults)
        val background = Color.Magenta
        val fill = Color(0xFF008040)
        val book = BookCard(BookRef("local", "shape-test"), "封面示例")
        compose.setContent {
            NoveliaTheme("light", squareCorners = enabled, squareCornerTargets = targets) {
                Column(Modifier.fillMaxSize().background(background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BookCover(book, Modifier.testTag("cover"))
                        ShelfCoverCard(book, BookRowStatus(), {}, Modifier.width(76.dp).testTag("grid-cover"))
                    }
                    Card(Modifier.size(100.dp, 40.dp).testTag("card"), colors = CardDefaults.cardColors(containerColor = fill)) {}
                    AppButton({}, Modifier.size(100.dp, 48.dp).testTag("button"), colors = ButtonDefaults.buttonColors(containerColor = fill)) { Text("按钮") }
                    OutlinedTextField("", {}, Modifier.width(160.dp).testTag("input"), shape = appControlShape(),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = fill, unfocusedBorderColor = fill))
                    Surface(Modifier.size(100.dp, 40.dp).testTag("panel"), shape = appPanelShape(), color = fill) {}
                    Surface(Modifier.size(100.dp, 40.dp).testTag("navigation"),
                        shape = appShape(CircleShape, SquareCornerTarget.Navigation), color = fill) {}
                }
            }
        }
        val elements = mapOf("cover" to SquareCornerTarget.Covers, "grid-cover" to SquareCornerTarget.Covers,
            "card" to SquareCornerTarget.Cards, "button" to SquareCornerTarget.Controls, "input" to SquareCornerTarget.Controls,
            "panel" to SquareCornerTarget.Panels, "navigation" to SquareCornerTarget.Navigation)
        fun assertCorners(expected: Set<SquareCornerTarget>) {
            elements.forEach { (tag, category) ->
                val pixel = compose.onNodeWithTag(tag).captureToImage().toPixelMap()[0, 0]
                val matchesBackground = abs(pixel.red - background.red) < .06f &&
                    abs(pixel.green - background.green) < .06f && abs(pixel.blue - background.blue) < .06f
                assertEquals(tag, category !in expected, matchesBackground)
            }
        }

        for(target in SquareCornerTarget.entries) {
            compose.runOnIdle { targets = setOf(target) }
            assertCorners(setOf(target))
        }
        compose.runOnIdle { targets = SquareCornerTarget.defaults - SquareCornerTarget.Covers }
        assertCorners(SquareCornerTarget.defaults - SquareCornerTarget.Covers)
        compose.runOnIdle { enabled = false }
        assertCorners(emptySet())
    }
}

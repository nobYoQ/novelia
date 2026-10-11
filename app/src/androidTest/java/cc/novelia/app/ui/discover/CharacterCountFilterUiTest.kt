package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.CharacterCountFilter
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.CharacterCountFilterFields
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CharacterCountFilterUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun invalidRangeCannotApplyAndAValidCustomRangeIncludesItsEndpoints() {
        var filter by mutableStateOf(CharacterCountFilter())
        compose.setContent {
            MaterialTheme { AppScrollColumn(Modifier.width(360.dp).fillMaxHeight()) {
                CharacterCountFilterFields(filter) { filter = it }
            } }
        }
        compose.onNodeWithTag("characters-minimum").performScrollTo().performTextInput("500000")
        compose.onNodeWithTag("characters-maximum").performScrollTo().performTextInput("100000")
        compose.onNodeWithTag("apply-character-filter").assertIsNotEnabled()
        compose.onNodeWithTag("characters-maximum").performTextReplacement("500000")
        compose.onNodeWithTag("apply-character-filter").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(filter.matches(500000))
            assertFalse(filter.matches(499999))
            assertFalse(filter.matches(null))
        }
        compose.onNodeWithText("不限", useUnmergedTree = true).performScrollTo().performClick()
        compose.runOnIdle { assertFalse(filter.active) }
    }

}

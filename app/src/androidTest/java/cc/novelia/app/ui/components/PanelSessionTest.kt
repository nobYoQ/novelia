@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.ui.theme.LocalReducedMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PanelSessionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scrollPanelStartsAtTopEvenWhenItsFormStateIsSaved() = reopen(lazy = false, reduced = false)
    @Test fun staticLazyPanelStartsAtTopEvenWhenItsFormStateIsSaved() = reopen(lazy = true, reduced = true)

    private fun reopen(lazy: Boolean, reduced: Boolean) {
        var open by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    val holder = rememberSaveableStateHolder()
                    if(open) AppSheet({ open = false }) {
                        holder.SaveableStateProvider("form") {
                            if(lazy) AppLazyColumn(Modifier.height(240.dp).testTag("panel-list")) {
                                items(20) { Text("选项 $it", Modifier.height(60.dp).testTag("item-$it")) }
                            }
                            else AppScrollColumn(Modifier.height(240.dp)) {
                                repeat(20) { Text("选项 $it", Modifier.height(60.dp).testTag("item-$it")) }
                            }
                        }
                    }
                }
            }
        }
        if(lazy) compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("panel-list"))).performScrollToIndex(19)
        else compose.onNodeWithTag("item-19").performScrollTo()
        compose.onNodeWithTag("item-19").assertIsDisplayed()
        compose.runOnIdle { open = false }
        compose.waitForIdle()
        compose.runOnIdle { open = true }
        compose.onNodeWithTag("item-0").assertIsDisplayed()
    }
}

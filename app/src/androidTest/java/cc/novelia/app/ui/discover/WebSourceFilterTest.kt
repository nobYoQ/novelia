package cc.novelia.app.ui.discover

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WebSourceFilterTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultsAndResetSelectEverySourceWhilePartialSelectionSurvivesRestore() {
        lateinit var source: MutableState<String>
        val restore = StateRestorationTester(compose)
        restore.setContent {
            source = rememberSaveable { mutableStateOf("") }
            NoveliaTheme("light") { WebSourceFilter(source.value) { source.value = it } }
        }
        providers.keys.forEach { compose.onNodeWithTag("web-source-$it").assertIsSelected() }
        val first = providers.keys.first()
        compose.onNodeWithTag("web-source-$first").performClick().assertIsNotSelected()
        providers.keys.drop(1).forEach { compose.onNodeWithTag("web-source-$it").assertIsSelected() }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("web-source-$first").assertIsNotSelected()
        compose.onNodeWithText("全选").performClick()
        providers.keys.forEach { compose.onNodeWithTag("web-source-$it").assertIsSelected() }
        compose.runOnIdle { assertEquals("", source.value); source.value = "syosetu" }
        compose.onNodeWithTag("web-source-syosetu").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("syosetu", source.value); source.value = "" }
        providers.keys.forEach { compose.onNodeWithTag("web-source-$it").assertIsSelected() }
    }
}

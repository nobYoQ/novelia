package cc.novelia.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.network.BookSource
import org.junit.Rule
import org.junit.Test

class BookSourcePickerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun selectMirrorWithoutManualCredentialFieldsAndReturnToOriginal() {
        var selected by mutableStateOf(BookSource.ORIGINAL)
        compose.setContent { MaterialTheme { BookSourcePicker(selected, true, false, {}, { selected = it }) } }
        compose.onNodeWithTag("book-source-original").assertIsSelected()
        compose.onNodeWithTag("book-source-xkvi").performClick().assertIsSelected()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("book-source-original").performClick().assertIsSelected()
    }
    @Test fun buildWithoutGatewayConfigurationDoesNotOfferBrokenMirrorSwitch() {
        compose.setContent { MaterialTheme { BookSourcePicker(BookSource.ORIGINAL, false, false, {}, {}) } }
        compose.onNodeWithTag("book-source-xkvi").assertIsNotEnabled()
    }
}

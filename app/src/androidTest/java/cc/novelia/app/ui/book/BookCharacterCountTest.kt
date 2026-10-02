package cc.novelia.app.ui.book

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class BookCharacterCountTest {
    @get:Rule val compose = createComposeRule()

    @Test fun approximateCountOpensExactCountAndCanBeDismissed() {
        compose.setContent { MaterialTheme { BookCharacterCount(123_456) } }
        compose.onNodeWithTag("book-character-count").assertTextEquals("12.3 万字").performClick()
        compose.onNodeWithTag("exact-character-count").assertTextEquals("123,456 字")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("exact-character-count").assertDoesNotExist()
        compose.onNodeWithTag("book-character-count").assertTextEquals("12.3 万字")
    }

    @Test fun unknownCountIsNotClickableAndUpdatedBookDoesNotKeepTheOldDialog() {
        var count by mutableStateOf<Long?>(null)
        compose.setContent { MaterialTheme { BookCharacterCount(count) } }
        compose.onNodeWithTag("book-character-count").assertTextEquals("字数未知").assertHasNoClickAction()
        compose.runOnIdle { count = 123_456 }
        compose.onNodeWithTag("book-character-count").performClick()
        compose.runOnIdle { count = 500_000 }
        compose.onNodeWithTag("exact-character-count").assertDoesNotExist()
        compose.onNodeWithTag("book-character-count").assertTextEquals("50 万字").performClick()
        compose.onNodeWithTag("exact-character-count").assertTextEquals("500,000 字")
    }
}

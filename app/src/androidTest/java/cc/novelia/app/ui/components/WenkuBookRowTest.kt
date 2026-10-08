package cc.novelia.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.ui.discover.DiscoverBookRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WenkuBookRowTest {
    @get:Rule val compose = createComposeRule()
    private val book = BookCard(BookRef("wenku", "three-volumes"), "三卷作品", total = 6)

    @Test fun rowShowsPublicationCountInsteadOfBilingualFileCount() {
        compose.setContent {
            MaterialTheme { BookRow(book.copy(publishedVolumeCount = 3), onClick = {}, compactMetadata = true) }
        }
        compose.onNodeWithText("3 卷").assertIsDisplayed()
        compose.onNodeWithText("6 卷").assertDoesNotExist()
    }

    @Test fun legacyFileCountIsNotPresentedAsPublicationCount() {
        compose.setContent { MaterialTheme { BookRow(book, onClick = {}, compactMetadata = true) } }
        compose.onNodeWithText("三卷作品").assertIsDisplayed()
        compose.onNodeWithText("6 卷").assertDoesNotExist()
    }

    @Test fun discoveryRowAlsoUsesPublicationCountInsteadOfBilingualFileCount() {
        compose.setContent { MaterialTheme { DiscoverBookRow(book.copy(publishedVolumeCount = 3), onClick = {}) } }
        compose.onNodeWithText("3 卷").assertIsDisplayed()
        compose.onNodeWithText("6 卷").assertDoesNotExist()
    }

    @Test fun outlineUsesTheKnownPublicationCountFromTheLocalFavorite() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalBookListPresentation provides BookListPresentation(
                    books = mapOf(book.ref.key to SavedBook(book.copy(publishedVolumeCount = 3))),
                )) { BookRow(book.copy(total = 0), onClick = {}, compactMetadata = true) }
            }
        }
        compose.onNodeWithText("3 卷").assertIsDisplayed()
    }
}

package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.discover.visibleBook
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class BookVisibilityTest {
    @Test fun blockedAuthorMatchesAnyAuthorWithoutCaseSensitivity() {
        val book = BookCard(BookRef("syosetu", "n1"), "作品", authors = listOf("作者甲", "Writer"))
        assertFalse(visibleBook(book, LibraryState(blockedAuthors = setOf("writer"))))
        assertFalse(visibleBook(book, LibraryState(blockedAuthors = setOf("作者甲"))))
        assertTrue(visibleBook(book, LibraryState(blockedAuthors = setOf("作者乙"))))
    }

    @Test fun authorBlockSurvivesSettingsBackupAndOldData() {
        val backup = SettingsBackup(blockedAuthors = setOf("作者甲"))
        assertEquals(backup, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)))
        assertTrue(appJson.decodeFromString<SettingsBackup>("{}").blockedAuthors.isEmpty())
        assertTrue(appJson.decodeFromString<LibraryState>("{}").blockedAuthors.isEmpty())
    }
}

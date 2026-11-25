package cc.novelia.app.ui.book

import cc.novelia.app.data.model.Profile
import cc.novelia.app.data.model.WenkuDetail
import org.junit.Assert.*
import org.junit.Test

class WenkuFormRulesTest {
    private val valid = WenkuDetail(title = "ソラ", titleZh = "天空", cover = "https://example.org/cover.jpg")

    @Test fun formRequiresAnImagePathAndChecksTheSubmittedText() {
        assertNull(valid.copy(title = "原".repeat(80), titleZh = "译".repeat(80),
            introduction = "文".repeat(1000)).formError(false))
        for (book in listOf(valid.copy(title = " "), valid.copy(title = "原".repeat(81)),
            valid.copy(titleZh = "译".repeat(81)), valid.copy(titleZh = "天空の城"),
            valid.copy(introduction = "文".repeat(1001)), valid.copy(cover = null),
            valid.copy(cover = "http://example.org/cover.jpg"),
            valid.copy(cover = "https://example.org/page?file=cover.jpg"))) {
            assertNotNull(book.formError(false))
        }
        for (suffix in listOf("JPEG", "png", "WebP", "gif", "avif")) {
            assertNull(valid.copy(cover = "https://example.org/cover.$suffix?w=80#preview").formError(false))
        }
    }

    @Test fun adultCategoriesAndBookEditingFollowAccountPermissions() {
        for (level in listOf("成人向", "成人向女")) {
            assertNotNull(valid.copy(level = level).formError(false))
            assertNull(valid.copy(level = level).formError(true))
        }
        val account = Profile("读者", "trusted", 0, Long.MAX_VALUE)
        assertTrue(account.canEdit)
        assertTrue(account.copy(role = "restricted").canReadAdult)
        assertFalse(account.copy(role = "restricted").canEdit)
        assertFalse(account.copy(role = "banned").canReadAdult)
        assertFalse(account.copy(role = "admin", createdAt = System.currentTimeMillis() / 1000).canReadAdult)
    }
}

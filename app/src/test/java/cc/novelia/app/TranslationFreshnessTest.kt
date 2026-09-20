package cc.novelia.app

import cc.novelia.app.data.updates.BookUpdateInfo
import cc.novelia.app.data.updates.accumulate
import org.junit.Assert.*
import org.junit.Test

class TranslationFreshnessTest {
    @Test fun laterNewChaptersDoNotMakeAlreadyRefreshedTranslationStaleAgain() {
        val translated = BookUpdateInfo(100, translations = mapOf("sakura" to 2), translationUpdatedAt = mapOf("sakura" to 100))
        val later = translated.accumulate(BookUpdateInfo(300, newChapters = 1))
        assertEquals(300L, later.checkedAt)
        assertEquals(100L, later.latestTranslationAt(listOf("sakura")))
        assertFalse(200L < later.latestTranslationAt(listOf("sakura")))
    }

    @Test fun eachEngineRetainsItsOwnUpdateTime() {
        val first = BookUpdateInfo(100, translations = mapOf("sakura" to 2), translationUpdatedAt = mapOf("sakura" to 100))
        val second = first.accumulate(BookUpdateInfo(300, translations = mapOf("gpt" to 1), translationUpdatedAt = mapOf("gpt" to 300)))
        assertEquals(100L, second.latestTranslationAt(listOf("sakura")))
        assertEquals(300L, second.latestTranslationAt(listOf("gpt", "youdao")))
        assertEquals(0L, second.latestTranslationAt(listOf("youdao")))
    }
}

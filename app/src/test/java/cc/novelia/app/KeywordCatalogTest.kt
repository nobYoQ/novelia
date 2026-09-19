package cc.novelia.app

import cc.novelia.app.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class KeywordCatalogTest {
    @Test fun aliasesFindOriginalsAndLabelsKeepOriginalFirst() {
        assertEquals("ハーレム", KeywordCatalog.exactMatch(KeywordCatalog.common, "后宫")?.original)
        assertEquals("ハーレム (后宫)", KeywordCatalog.exactMatch(KeywordCatalog.common, "后宫")?.label)
        assertEquals("ほのぼの", KeywordCatalog.exactMatch(KeywordCatalog.common, "温馨")?.original)
        assertEquals("自定义", KeywordEntry("自定义").label)
    }

    @Test fun browsingDeduplicatesWithoutResettingAnEditedOrClearedTranslation() {
        val edited = KeywordEntry("ヤンデレ", "", translationEdited = true)
        val observed = KeywordCatalog.observe(KeywordCatalog.withDefaults(listOf(edited)), listOf("新标签", "新标签", "ヤンデレ", " ", " 新标签 "))
        assertEquals(1, observed.count { it.original == "新标签" })
        assertEquals("", observed.single { it.original == "ヤンデレ" }.translation)
        assertTrue(observed.single { it.original == "ヤンデレ" }.translationEdited)
        val restored = appJson.decodeFromString<List<KeywordEntry>>(appJson.encodeToString(observed))
        assertEquals(observed, restored)
    }

    @Test fun rankingPrefersMatchThenRecentUseThenCommonTags() {
        val entries = listOf(
            KeywordEntry("a", "恋爱故事", lastUsedAt = 900),
            KeywordEntry("b", "恋爱", common = true),
            KeywordEntry("c", "恋爱", lastUsedAt = 10),
            KeywordEntry("d", "恋爱"),
            KeywordEntry("e", "其他", lastUsedAt = 1000),
        )
        assertEquals(listOf("c", "b", "d", "a"), KeywordCatalog.suggestions(entries, "恋爱").map { it.original })
        assertEquals("e", KeywordCatalog.suggestions(entries, "").first().original)
    }

    @Test fun restoringTranslationsReplacesSeedDefaultsButPreservesUserEditsIncludingEmpty() {
        val restored = KeywordEntry("ヤンデレ", "自定译名", lastUsedAt = 20, translationEdited = true)
        assertEquals("自定译名", KeywordCatalog.merge(KeywordCatalog.common, listOf(restored)).single { it.original == "ヤンデレ" }.translation)
        val current = listOf(restored.copy(translation = "", lastUsedAt = 1))
        val result = KeywordCatalog.merge(current, listOf(restored)).single { it.original == "ヤンデレ" }
        assertEquals("", result.translation)
        assertEquals(20L, result.lastUsedAt)
    }

    @Test fun inclusionAndExclusionUseExactOriginalTagSyntaxWithoutDuplicates() {
        assertEquals("ヤンデレ$ -ハーレム$", SearchExpression.build("", "", "", "", "ヤンデレ ヤンデレ ハーレム", "ハーレム ハーレム", "", ""))
        assertEquals(listOf("ヤンデレ", "ハーレム"), SearchExpression.tagsIn("ヤンデレ$ -ハーレム$ ヤンデレ$ >20"))
    }

    @Test fun assistingPreservesTheManualExpression() {
        val original = "(旅人 | 少女) -悲剧 >20"
        assertEquals("$original ヤンデレ$", SearchExpression.append(original, "ヤンデレ$"))
        assertEquals(original, SearchExpression.append(original, ""))
        assertTrue(KeywordCatalog.canSearch("初めての恋"))
        assertFalse(KeywordCatalog.canSearch("两 个标签"))
        assertFalse(KeywordCatalog.canSearch("-反向"))
        assertTrue(KeywordCatalog.canSearch("语法$"))
    }

    @Test fun addingTagsDetectsOppositeManualConditionsAndDeduplicatesExistingTags() {
        assertEquals("旅人 ヤンデレ$ -ハーレム$", SearchExpression.append("旅人 ヤンデレ$", "ヤンデレ$ -ハーレム$"))
        assertEquals(listOf("ハーレム"), SearchExpression.conflictingTags("旅人 ハーレム$", "-ハーレム$ ヤンデレ$"))
        assertEquals(listOf("ハーレム"), SearchExpression.conflictingTags("-ハーレム$", "ハーレム$"))
    }

    @Test fun fullCatalogKeepsReaderEditsAndRecentUseWhileLearningNewTags() {
        val full = (0 until KeywordCatalog.MAX_ENTRIES).map { KeywordEntry("tag-$it") }.toMutableList()
        full[0] = full[0].copy(translationEdited = true)
        full[1] = full[1].copy(lastUsedAt = 100)
        val observed = KeywordCatalog.observe(full, listOf("new-tag"))
        assertEquals(KeywordCatalog.MAX_ENTRIES, observed.size)
        assertTrue(observed.any { it.original == "tag-0" && it.translationEdited })
        assertTrue(observed.any { it.original == "tag-1" })
        assertTrue(observed.any { it.original == "new-tag" })
        assertFalse(observed.any { it.original == "tag-2" })
    }

    @Test fun usingOrEditingNewTagsCanEnterAFullCatalogAndDoesNotResetTranslations() {
        val full = (0 until KeywordCatalog.MAX_ENTRIES).map { KeywordEntry("tag-$it", lastUsedAt = 100) }
        val used = KeywordCatalog.markUsed(full, listOf("new-tag"), now = 200)
        assertEquals(KeywordCatalog.MAX_ENTRIES, used.size)
        assertEquals(200L, used.single { it.original == "new-tag" }.lastUsedAt)
        val edited = KeywordCatalog.translate(full, "new-tag", "新标签")
        assertEquals(KeywordCatalog.MAX_ENTRIES, edited.size)
        assertTrue(edited.single { it.original == "new-tag" }.translationEdited)
        val cleared = KeywordEntry("ハーレム", "", translationEdited = true)
        assertEquals("", KeywordCatalog.markUsed(listOf(cleared), listOf("ハーレム"), 200).single().translation)
    }

    @Test fun catalogLimitsMatchTheBackupContractWithoutSilentlyTruncatingUserTranslations() {
        val overlong = "字".repeat(KeywordCatalog.MAX_TEXT_LENGTH + 1)
        assertFalse(KeywordCatalog.canSearch(overlong))
        assertTrue(KeywordCatalog.observe(emptyList(), listOf(overlong)).isEmpty())
        assertTrue(runCatching { KeywordCatalog.translate(emptyList(), "标签", overlong) }.exceptionOrNull() is IllegalArgumentException)
        val maximum = "字".repeat(KeywordCatalog.MAX_TEXT_LENGTH)
        assertEquals(maximum, KeywordCatalog.translate(emptyList(), "标签", maximum).single().translation)
    }
}

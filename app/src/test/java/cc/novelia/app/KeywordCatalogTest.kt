package cc.novelia.app

import cc.novelia.app.data.catalog.KeywordCatalog
import cc.novelia.app.data.catalog.KeywordEntry
import cc.novelia.app.data.catalog.SearchExpression
import cc.novelia.app.data.storage.appJson
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

    @Test fun reachingOrLoweringUserCapacityRetainsEveryExistingTag() {
        val full = (0 until 5).map { KeywordEntry("tag-$it") }.toMutableList()
        full[0] = full[0].copy(translationEdited = true)
        full[1] = full[1].copy(lastUsedAt = 100)
        assertEquals(full, KeywordCatalog.observe(full, listOf("new-tag"), limit = 5))
        assertEquals(full, KeywordCatalog.observe(full, listOf("new-tag"), limit = 2))
        assertEquals(full + KeywordEntry("new-tag"), KeywordCatalog.observe(full, listOf("new-tag")))
        assertEquals(listOf("tag-0", "tag-1", "new-tag"), KeywordCatalog.observe(full.take(2), listOf("tag-0", "new-tag", "another"), limit = 3).map { it.original })
    }

    @Test fun userCapacityStillAllowsUsingAndEditingExistingTags() {
        val full = (0 until 2).map { KeywordEntry("tag-$it", lastUsedAt = 100) }
        val used = KeywordCatalog.markUsed(full, listOf("tag-0", "new-tag"), now = 200, limit = 1)
        assertEquals(2, used.size)
        assertEquals(200L, used.single { it.original == "tag-0" }.lastUsedAt)
        assertEquals(100L, used.single { it.original == "tag-1" }.lastUsedAt)
        assertTrue(runCatching { KeywordCatalog.translate(full, "new-tag", "新标签", limit = 2) }.isFailure)
        assertEquals("已有译名", KeywordCatalog.translate(full, "tag-0", "已有译名", limit = 1).single { it.original == "tag-0" }.translation)
        assertEquals(3, KeywordCatalog.translate(full, "new-tag", "新标签").size)
        assertEquals(3, KeywordCatalog.markUsed(full, listOf("new-tag"), now = 200).size)
        val cleared = KeywordEntry("ハーレム", "", translationEdited = true)
        assertEquals("", KeywordCatalog.markUsed(listOf(cleared), listOf("ハーレム"), 200).single().translation)
    }

    @Test fun unlimitedCatalogRetainsMoreThanTwentyThousandTagsAcrossUpdates() {
        val large = (0..20_000).map { KeywordEntry("tag-$it") }
        assertEquals(large.size + 1, KeywordCatalog.observe(large, listOf("new-tag")).size)
        assertEquals(large.size + 1, KeywordCatalog.markUsed(large, listOf("new-tag"), now = 200).size)
        assertEquals(large.size + KeywordCatalog.common.size, KeywordCatalog.withDefaults(large).size)
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

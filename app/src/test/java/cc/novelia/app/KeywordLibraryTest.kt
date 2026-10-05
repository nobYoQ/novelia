package cc.novelia.app

import cc.novelia.app.data.catalog.*
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SettingsBackup
import cc.novelia.app.data.storage.appJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class KeywordLibraryTest {
    @Test fun reorderingCategoriesSurvivesRoundTripAndDefaultUpgradeWithoutChangingTags() {
        val original = KeywordLibrary.defaults().createCategory("我的分类").editEntry("ヤンデレ", "自定译名", "我的分类")
        val order = original.categories.reversed()
        val reordered = original.reorderCategories(order)
        assertEquals(order, reordered.categories)
        assertEquals(original.entries, reordered.entries)
        val bytes = ByteArrayOutputStream().also { KeywordLibraryFormat.write(it, reordered) }.toByteArray()
        val restored = KeywordLibraryFormat.read(ByteArrayInputStream(bytes)).upgradeDefaults()
        assertEquals(reordered, restored)
        assertEquals(reordered, reordered.reorderCategories(order))
    }

    @Test fun reorderingRejectsMissingDuplicateAndUnknownCategories() {
        val original = KeywordLibrary.defaults()
        val invalid = listOf(original.categories.dropLast(1), original.categories + "新分类",
            original.categories.dropLast(1) + original.categories.first(), original.categories.dropLast(1) + "不存在")
        invalid.forEach { assertTrue(runCatching { original.reorderCategories(it) }.isFailure) }
    }

    @Test fun categoryUpgradeSeedsOnceAndPreservesCustomMembershipAndCapacity() {
        val old = KeywordLibrary(listOf(KeywordEntry("BL", "男性恋爱", "题材"),
            KeywordEntry("GL", "百合", "我的分类", categoryEdited = true), KeywordEntry("TS", category = "题材"),
            KeywordEntry("ボーイズラブ", category = "情节", categoryEdited = true)),
            categories = listOf("题材", "情节", "其他", "我的分类"))
        val upgraded = old.upgradeDefaults(limit = 1)
        assertEquals(old.entries.size, upgraded.entries.size)
        assertEquals("BL／男性恋爱", upgraded.entries[0].category)
        assertEquals("我的分类", upgraded.entries[1].category)
        assertEquals("TS／性转", upgraded.entries[2].category)
        assertEquals("情节", upgraded.entries[3].category)
        val arranged = upgraded.renameCategory("BL／男性恋爱", "我的 BL").deleteCategory("TS／性转")
        assertEquals(arranged, arranged.upgradeDefaults(limit = 1))
        val restored = KeywordLibraryFormat.decode(appJson.encodeToString(arranged))
        assertEquals(arranged, restored.upgradeDefaults(limit = 1))
    }

    @Test fun seedingRespectsCategoryLimitAndExplicitlyClearingAnAutomaticTranslationPersists() {
        val full = KeywordLibrary(listOf(KeywordEntry("BL")), categories = listOf("其他") + (1..99).map { "分类$it" })
        val upgraded = full.upgradeDefaults(limit = 1)
        assertEquals(100, upgraded.categories.size)
        assertEquals("其他", upgraded.entries.single().category)
        KeywordLibraryFormat.validate(upgraded)
        val imported = KeywordLibrary(listOf(KeywordEntry("ヤンデレヒロイン")))
        val cleared = imported.editEntry("ヤンデレヒロイン", "", "其他")
        assertTrue(cleared.entries.single().translationEdited)
        assertEquals("ヤンデレヒロイン", cleared.upgradeDefaults(limit = 1).entries.single().label)
    }

    @Test fun categoryExpansionIsAFlatEditableSelectionAndRepeatedActionsDoNotDuplicate() {
        val originals = (0..24).map { "标签$it" } + listOf("有 空格", "-非法")
        val initial = KeywordSelection(listOf("已有"), listOf("标签0", "保留排除"))
        val included = initial.add(originals, true)
        assertEquals(listOf("已有") + originals.take(25), included.included)
        assertEquals(listOf("保留排除"), included.excluded)
        assertEquals(included, included.add(originals, true))
        val adjusted = included.add(listOf("标签1"), false).copy(included = included.included - "标签1" - "标签2")
        assertFalse("标签2" in adjusted.included)
        assertEquals(listOf("保留排除", "标签1"), adjusted.excluded)
        val excluded = adjusted.add(originals, false)
        assertEquals(listOf("已有"), excluded.included)
        assertEquals(26, excluded.excluded.size)
    }

    @Test fun renamingAndDeletingCategoriesRetainsLabelsAndMovesMembers() {
        val base = KeywordLibrary.defaults().createCategory("我的分类")
            .editEntry("ヤンデレ", "自定病娇", "我的分类")
        val renamed = base.renameCategory("我的分类", "喜欢的人物")
        assertFalse("我的分类" in renamed.categories)
        assertEquals("喜欢的人物", renamed.entries.single { it.original == "ヤンデレ" }.category)
        val removed = renamed.deleteCategory("喜欢的人物")
        val tag = removed.entries.single { it.original == "ヤンデレ" }
        assertEquals("其他", tag.category)
        assertEquals("自定病娇", tag.translation)
        assertTrue(tag.categoryEdited)
        assertEquals(base.entries.size, removed.entries.size)
    }

    @Test fun exportRoundTripKeepsEmptyCategoriesAndDoesNotReviveRemovedDefaults() {
        val library = KeywordLibrary.defaults().deleteCategory("题材").createCategory("待整理")
        val bytes = ByteArrayOutputStream().also { KeywordLibraryFormat.write(it, library) }.toByteArray()
        val restored = KeywordLibraryFormat.read(ByteArrayInputStream(bytes))
        assertEquals(library, restored)
        assertTrue("待整理" in restored.categories)
        assertFalse("题材" in restored.categories)
    }

    @Test fun legacyArraysRemainReadableWithTheirTranslationsAndCategories() {
        val legacy = listOf(KeywordEntry("标签", "旧译名", "旧分类"))
        val restored = KeywordLibraryFormat.decode(appJson.encodeToString(legacy))
        assertEquals(legacy, restored.entries)
        assertTrue("旧分类" in restored.categories)
        assertEquals(KeywordCatalog.common, KeywordLibrary.fromLegacy(emptyList()).entries)
    }

    @Test fun mergeRestoresUntouchedDefaultsButPreservesEditedEmptyTranslationsAndCategories() {
        val incoming = KeywordLibrary.defaults().createCategory("导入分类").editEntry("ヤンデレ", "导入译名", "导入分类")
        val untouched = KeywordLibrary.defaults().merge(incoming)
        assertEquals("导入译名", untouched.entries.single { it.original == "ヤンデレ" }.translation)
        assertEquals("导入分类", untouched.entries.single { it.original == "ヤンデレ" }.category)
        val current = KeywordLibrary.defaults().createCategory("本机分类").editEntry("ヤンデレ", "", "本机分类")
        val merged = current.merge(incoming)
        assertEquals("", merged.entries.single { it.original == "ヤンデレ" }.translation)
        assertEquals("本机分类", merged.entries.single { it.original == "ヤンデレ" }.category)
        assertTrue("导入分类" in merged.categories)
        assertEquals(merged, merged.merge(incoming))
    }

    @Test fun reservedDuplicateAndOverlongCategoriesAreRejected() {
        val library = KeywordLibrary.defaults()
        listOf("全部", "", "题材", "字".repeat(41)).forEach { name -> assertTrue(runCatching { library.createCategory(name) }.isFailure) }
        assertTrue(runCatching { library.deleteCategory("其他") }.isFailure)
        assertTrue(runCatching { library.renameCategory("其他", "未分类") }.isFailure)
        assertTrue(runCatching { library.editEntry("tag", "", "不存在") }.isFailure)
    }

    @Test fun invalidImportIsRejectedBeforeItCanBeMerged() {
        val valid = KeywordLibrary.defaults()
        listOf(valid.copy(version = 2), valid.copy(format = "settings"), valid.copy(categories = emptyList()),
            valid.copy(entries = valid.entries + valid.entries.first()),
            valid.copy(entries = listOf(KeywordEntry("bad", category = "不存在"))),
            valid.copy(entries = listOf(KeywordEntry("bad", translation = "长".repeat(257))))).forEach { invalid ->
            assertTrue(runCatching { KeywordLibraryFormat.decode(appJson.encodeToString(invalid)) }.isFailure)
        }
        assertTrue(runCatching { KeywordLibraryFormat.decode("{broken") }.isFailure)
        assertTrue(runCatching { KeywordLibraryFormat.decode("{\"theme\":\"dark\"}") }.isFailure)
    }

    @Test fun mergingAtUserCapacityNeverEvictsExistingOrIncomingVocabulary() {
        val full = KeywordLibrary((0 until 2).map { KeywordEntry("tag-$it") })
        assertEquals(full, full.merge(KeywordLibrary(emptyList()), limit = 1))
        assertTrue(runCatching { full.merge(KeywordLibrary(listOf(KeywordEntry("new-tag"))), limit = 2) }.isFailure)
        assertEquals(3, full.merge(KeywordLibrary(listOf(KeywordEntry("new-tag")))).entries.size)
        val updated = full.merge(KeywordLibrary(listOf(KeywordEntry("tag-0", "导入译名"))), limit = 1)
        assertEquals(2, updated.entries.size)
        assertEquals("导入译名", updated.entries.first().translation)
        assertEquals("手动译名", full.editEntry("tag-0", "手动译名", "其他", limit = 1).entries.last().translation)
        assertTrue(runCatching { full.editEntry("new-tag", "新词", "其他", limit = 2) }.isFailure)
    }

    @Test fun fullLibrarySearchHasNoTwelveItemPreviewLimitAndMatchesLocalTranslations() {
        val entries = (0..50).map { KeywordEntry("tag-$it", "译名 $it", if(it % 2 == 0) "偶数" else "奇数") }
        assertEquals(51, KeywordCatalog.suggestions(entries, "", limit = entries.size).size)
        assertEquals("tag-49", KeywordCatalog.suggestions(entries, "译名 49", limit = entries.size).single().original)
        assertEquals(26, KeywordCatalog.suggestions(entries, "", "偶数", entries.size).size)
    }

    @Test fun externalDictionaryKeepsFirstDuplicateAndAlwaysExportsNativeFormat() {
        val text = """[
            {"src":" タグ ","dst":"首条译名","info":" 外部分类 "},
            {"src":"タグ","dst":"后续译名","info":"后续分类"},
            {"src":"A","dst":"大写"},
            {"src":"a","dst":"小写","info":" "},
            {"src":"保留","dst":"保留","info":"[跳过]"}
        ]"""
        val imported = KeywordLibraryFormat.readImport(ByteArrayInputStream(("\uFEFF" + text).toByteArray(Charsets.UTF_8)))
        assertEquals(1, imported.duplicateCount)
        val library = imported.library
        assertEquals(listOf("タグ", "A", "a", "保留"), library.entries.map { it.original })
        assertEquals(KeywordEntry("タグ", "首条译名", "外部分类"), library.entries.first())
        assertEquals(listOf("其他", "外部分类", "[跳过]"), library.categories)
        assertEquals("其他", library.entries.single { it.original == "A" }.category)
        assertEquals("其他", library.entries.single { it.original == "a" }.category)
        val bytes = ByteArrayOutputStream().also { KeywordLibraryFormat.write(it, library) }.toByteArray()
        val native = appJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals(setOf("entries", "categories", "format", "version", "categorySeedsVersion"), native.keys)
        assertEquals("novelia-keywords", native.getValue("format").jsonPrimitive.content)
        assertEquals("1", native.getValue("version").jsonPrimitive.content)
        assertEquals("首条译名", native.getValue("entries").jsonArray.first().jsonObject.getValue("translation").jsonPrimitive.content)
        assertFalse(native.getValue("entries").jsonArray.first().jsonObject.keys.any { it in setOf("src", "dst", "info") })
        assertEquals(library, KeywordLibraryFormat.read(ByteArrayInputStream(bytes)))
        assertEquals(0, KeywordLibraryFormat.readImport(ByteArrayInputStream(bytes)).duplicateCount)
    }

    @Test fun externalDictionaryMergesWithoutOverwritingLocalEdits() {
        val incoming = KeywordLibraryFormat.decode("""[{"src":"ヤンデレ","dst":"导入病娇","info":"导入分类"}]""")
        val current = KeywordLibrary.defaults().createCategory("本机分类").editEntry("ヤンデレ", "", "本机分类")
        val merged = current.merge(incoming)
        assertEquals("", merged.entries.single { it.original == "ヤンデレ" }.translation)
        assertEquals("本机分类", merged.entries.single { it.original == "ヤンデレ" }.category)
        assertEquals("导入病娇", KeywordLibrary.defaults().merge(incoming).entries.single { it.original == "ヤンデレ" }.translation)
        assertEquals(merged, merged.merge(incoming))
    }

    @Test fun malformedExternalRecordsAndNativeDuplicatesAreRejected() {
        val valid = """{"src":"タグ","dst":"译名","info":"分类"}"""
        listOf(
            """{"src":"","dst":"译名"}""", """{"src":null,"dst":"译名"}""",
            """{"src":"标签"}""", """{"src":"标签","dst":12}""",
            """{"src":"标签","dst":"译名","info":null}""", """{"src":"标签","dst":"译名","info":"全部"}""",
            """{"original":"原生标签"}""", "null",
            """{"src":"タグ","dst":"${"长".repeat(257)}","info":"分类"}""",
        ).forEach { invalid -> assertTrue(invalid, runCatching { KeywordLibraryFormat.decode("[$valid,$invalid]") }.isFailure) }
        val legacy = listOf(KeywordEntry("重复"), KeywordEntry("重复"))
        assertTrue(runCatching { KeywordLibraryFormat.decode(appJson.encodeToString(legacy)) }.isFailure)
    }

    @Test fun largeExternalImportAndNativeRoundTripHaveNoDefaultCountLimit() {
        val text = (0 until 20_681).joinToString(prefix = "[", postfix = "]") { """{"src":"tag-$it","dst":"译名 $it","info":"导入分类"}""" }
        val library = KeywordLibraryFormat.decode(text)
        val merged = KeywordLibrary.defaults().merge(library)
        assertEquals(20_681 + KeywordCatalog.common.size, merged.entries.size)
        assertEquals("译名 20680", merged.entries.last().translation)
        val bytes = ByteArrayOutputStream().also { KeywordLibraryFormat.write(it, merged) }.toByteArray()
        assertEquals(merged, KeywordLibraryFormat.read(ByteArrayInputStream(bytes)))
        assertTrue(runCatching { KeywordLibrary.defaults().merge(library, limit = 20_000) }.isFailure)
    }

    @Test fun capacityPreferenceDefaultsToUnlimitedAndSurvivesSettingsRoundTrips() {
        assertNull(appJson.decodeFromString<LibraryState>("{}").keywordLimit)
        assertNull(appJson.decodeFromString<SettingsBackup>("{}").keywordLimit)
        for(limit in listOf(null, 30_000)) {
            val state = LibraryState(keywordLimit = limit)
            val settings = SettingsBackup(keywordLimit = limit)
            assertEquals(state, appJson.decodeFromString<LibraryState>(appJson.encodeToString(state)))
            assertEquals(settings, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(settings)))
        }
    }
}

package cc.novelia.app

import cc.novelia.app.data.catalog.*
import cc.novelia.app.data.storage.appJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class KeywordLibraryTest {
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
        assertEquals(20, KeywordLibrary.fromLegacy(emptyList()).entries.size)
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

    @Test fun mergingAtCapacityNeverEvictsExistingOrIncomingVocabulary() {
        val full = KeywordLibrary((0 until KeywordCatalog.MAX_ENTRIES).map { KeywordEntry("tag-$it") })
        assertEquals(full, full.merge(KeywordLibrary(emptyList())))
        assertTrue(runCatching { full.merge(KeywordLibrary(listOf(KeywordEntry("new-tag")))) }.isFailure)
    }

    @Test fun fullLibrarySearchHasNoTwelveItemPreviewLimitAndMatchesLocalTranslations() {
        val entries = (0..50).map { KeywordEntry("tag-$it", "译名 $it", if(it % 2 == 0) "偶数" else "奇数") }
        assertEquals(51, KeywordCatalog.suggestions(entries, "", limit = KeywordCatalog.MAX_ENTRIES).size)
        assertEquals("tag-49", KeywordCatalog.suggestions(entries, "译名 49", limit = KeywordCatalog.MAX_ENTRIES).single().original)
        assertEquals(26, KeywordCatalog.suggestions(entries, "", "偶数", KeywordCatalog.MAX_ENTRIES).size)
    }
}

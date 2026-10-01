package cc.novelia.app

import cc.novelia.app.data.backup.mergeLibraryBackup
import cc.novelia.app.data.catalog.SavedSearchPreset
import cc.novelia.app.data.catalog.withMigratedSearchPresets
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SavedSearchPresetTest {
    @Test fun fullSearchConditionsSurviveLibrarySerialization() {
        val preset = SavedSearchPreset(id = "weekly", name = "已完结奇幻", query = "魔法 -恋爱$", source = "syosetu,kakuyomu",
            type = 2, translate = 1, sort = 2, webLevel = 1, wenkuLevel = 3).normalized()
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(LibraryState(savedSearchPresets = listOf(preset))))
        assertEquals(preset, restored.savedSearchPresets.single())
        assertTrue(restored.savedSearchPresets.single().hasSameConditions(preset.copy(id = "other", name = "另一个名称")))
        assertFalse(restored.savedSearchPresets.single().hasSameConditions(preset.copy(translate = 2)))
        assertFalse(restored.savedSearchPresets.single().hasSameConditions(preset.copy(sort = 1)))
    }

    @Test fun legacyExpressionsMigrateWithoutLosingHandwrittenSyntaxOrDuplicating() {
        val legacy = appJson.decodeFromString<LibraryState>("""{"savedSearches":["魔法 学院$ -恋爱$","作者:名前"]}""")
        val migrated = legacy.withMigratedSearchPresets()
        assertEquals(legacy.savedSearches, migrated.savedSearchPresets.map { it.query })
        assertTrue(migrated.savedSearches.isEmpty())
        assertEquals(migrated, migrated.withMigratedSearchPresets())
        assertEquals(migrated.savedSearchPresets, migrated.copy(savedSearches = legacy.savedSearches).withMigratedSearchPresets().savedSearchPresets)
    }

    @Test fun filterOnlyAndWenkuSearchesAreValidAndInvalidImportedChoicesAreBounded() {
        val filterOnly = SavedSearchPreset(name = "GPT完结", type = 2, translate = 1)
        assertEquals("", filterOnly.normalized().query)
        assertTrue(filterOnly.summary().contains("已完结"))
        assertTrue(filterOnly.summary().contains("GPT"))
        val imported = SavedSearchPreset(name = " 文库分类 ", category = 99, source = "unknown,syosetu,syosetu", type = -1,
            translate = 100, sort = 100, webLevel = -1, wenkuLevel = 100).normalized()
        assertEquals(2, imported.category)
        assertEquals("syosetu", imported.source)
        assertEquals(0, imported.type)
        assertEquals(2, imported.translate)
        assertEquals(2, imported.sort)
        assertEquals(6, imported.wenkuLevel)
        assertTrue(imported.summary().startsWith("文库小说"))
    }

    @Test fun backupMergeKeepsLocalNamesAndAddsOtherSavedCombinations() {
        val current = SavedSearchPreset(id = "same", name = "本机名称", query = "本机条件")
        val incoming = current.copy(name = "备份名称", query = "旧条件")
        val other = SavedSearchPreset(id = "other", name = "另一组合", type = 2)
        val merged = mergeLibraryBackup(LibraryState(savedSearchPresets = listOf(current)), LibraryState(savedSearchPresets = listOf(incoming, other)))
        assertEquals(listOf(current, other), merged.savedSearchPresets)
    }
}

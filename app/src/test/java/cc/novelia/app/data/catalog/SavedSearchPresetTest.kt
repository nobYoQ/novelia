package cc.novelia.app.data.catalog

import cc.novelia.app.data.backup.mergeLibraryBackup
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SettingsBackup
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SavedSearchPresetTest {
    @Test fun fullSearchConditionsSurviveLibrarySerialization() {
        val preset = SavedSearchPreset(id = "weekly", name = "已完结奇幻", query = "魔法 -恋爱$", source = "syosetu,kakuyomu",
            type = 2, translate = 1, sort = 2, webLevel = 1, wenkuLevel = 3, lastPage = 7).normalized()
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(LibraryState(savedSearchPresets = listOf(preset))))
        assertEquals(preset, restored.savedSearchPresets.single())
        assertTrue(restored.savedSearchPresets.single().hasSameConditions(preset.copy(id = "other", name = "另一个名称", lastPage = 0)))
        assertEquals(preset.summary(), preset.copy(lastPage = 0).summary())
        assertFalse(restored.savedSearchPresets.single().hasSameConditions(preset.copy(translate = 2)))
        assertFalse(restored.savedSearchPresets.single().hasSameConditions(preset.copy(sort = 1)))
    }

    @Test fun legacyExpressionsMigrateWithoutLosingHandwrittenSyntaxOrDuplicating() {
        val legacy = appJson.decodeFromString<LibraryState>("""{"savedSearches":["魔法 学院$ -恋爱$","作者:名前"]}""")
        val migrated = legacy.withMigratedSearchPresets()
        assertEquals(legacy.savedSearches, migrated.savedSearchPresets.map { it.query })
        assertTrue(migrated.savedSearchPresets.all { it.lastPage == 0 })
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
            translate = 100, sort = 100, webLevel = -1, wenkuLevel = 100, lastPage = -1).normalized()
        assertEquals(2, imported.category)
        assertEquals("syosetu", imported.source)
        assertEquals(0, imported.type)
        assertEquals(2, imported.translate)
        assertEquals(2, imported.sort)
        assertEquals(6, imported.wenkuLevel)
        assertEquals(0, imported.lastPage)
        assertTrue(imported.summary().startsWith("文库小说"))
    }

    @Test fun backupMergeKeepsLocalNamesAndAddsOtherSavedCombinations() {
        val current = SavedSearchPreset(id = "same", name = "本机名称", query = "本机条件", lastPage = 8)
        val incoming = current.copy(name = "备份名称", query = "旧条件", lastPage = 2)
        val other = SavedSearchPreset(id = "other", name = "另一组合", type = 2, lastPage = 4)
        val merged = mergeLibraryBackup(LibraryState(savedSearchPresets = listOf(current)), LibraryState(savedSearchPresets = listOf(incoming, other)))
        assertEquals(listOf(current, other), merged.savedSearchPresets)
    }

    @Test fun oldDataDefaultsToFirstPageWithRestorationDisabledAndSettingSurvivesBackup() {
        val old = appJson.decodeFromString<LibraryState>("""{"savedSearchPresets":[{"id":"old","name":"旧搜索"}]}""")
        assertFalse(old.rememberSavedSearchPage)
        assertEquals(0, old.savedSearchPresets.single().lastPage)
        assertFalse(appJson.decodeFromString<SettingsBackup>("{}").rememberSavedSearchPage)
        for(enabled in listOf(true, false)) {
            val state = old.copy(rememberSavedSearchPage = enabled)
            assertEquals(state, appJson.decodeFromString<LibraryState>(appJson.encodeToString(state)))
            val backup = SettingsBackup(rememberSavedSearchPage = enabled)
            assertEquals(backup, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)))
        }
    }

    @Test fun eachAppliedPresetKeepsItsOwnPageEvenWithIdenticalConditions() {
        val first = SavedSearchPreset(id = "first", name = "第一套", query = "魔法", lastPage = 2)
        val second = first.copy(id = "second", name = "第二套", lastPage = 5)
        val wenku = SavedSearchPreset(id = "wenku", name = "文库", category = 2, lastPage = 3)
        val state = LibraryState(rememberSavedSearchPage = true, savedSearchPresets = listOf(first, second, wenku))
        val updated = state.withSavedSearchPage(first.id, first.copy(name = "新名称", lastPage = 0), 7)
            .withSavedSearchPage(wenku.id, wenku, 9)
        assertEquals(listOf(first.copy(lastPage = 7), second, wenku.copy(lastPage = 9)), updated.savedSearchPresets)
        assertEquals(updated, appJson.decodeFromString<LibraryState>(appJson.encodeToString(updated)))
        val continued = updated.withSavedSearchPage(first.id, first, 10).withSavedSearchPage(first.id, first, 11)
        assertEquals(listOf(first.copy(lastPage = 11), second, wenku.copy(lastPage = 9)), continued.savedSearchPresets)
    }

    @Test fun disabledOrUnrelatedSearchesDoNotOverwriteSavedPagesOrRecreateDeletedPresets() {
        val preset = SavedSearchPreset(id = "saved", name = "已保存", query = "魔法", lastPage = 6)
        val state = LibraryState(rememberSavedSearchPage = true, savedSearchPresets = listOf(preset))
        assertEquals(state, state.withSavedSearchPage(preset.id, preset.copy(query = "学院"), 0))
        assertEquals(state, state.withSavedSearchPage(preset.id, preset.copy(sort = 1), 0))
        assertEquals(state, state.withSavedSearchPage("", preset, 0))
        assertEquals(state, state.withSavedSearchPage("deleted", preset, 0))
        assertEquals(state, state.withSavedSearchPage(preset.id, preset, -1))
        val disabled = state.copy(rememberSavedSearchPage = false)
        assertSame(disabled, disabled.withSavedSearchPage(preset.id, preset, 0))
        assertEquals(6, disabled.copy(rememberSavedSearchPage = true).savedSearchPresets.single().lastPage)
        val deleted = state.copy(savedSearchPresets = emptyList())
        assertEquals(deleted, deleted.withSavedSearchPage(preset.id, preset, 8))
    }

    @Test fun savedPagesAndTheirSettingDoNotQueueWebDavChanges() {
        val preset = SavedSearchPreset(id = "saved", name = "搜索", lastPage = 2)
        val before = LibraryState(savedSearchPresets = listOf(preset))
        val after = before.copy(rememberSavedSearchPage = true).withSavedSearchPage(preset.id, preset, 5)
        assertTrue(WebDavProjection.affectedLibraryDomains(before, after).isEmpty())
        assertEquals(WebDavProjection.library(before), WebDavProjection.library(after))
    }
}

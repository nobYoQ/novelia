package cc.novelia.app.data.catalog

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.backup.LibraryBackupService
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.data.storage.appJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class KeywordStoreTest {
    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }

    @Test fun oldLibrarySeedsEditableCategoriesOnceAndPersistsSiteTranslations() = fixture { context, _ ->
        val old = KeywordLibrary(listOf(KeywordEntry("BL", category = "题材"), KeywordEntry("TS", category = "题材"),
            KeywordEntry("ヤンデレヒロイン")), categories = listOf("题材", "人物", "情节", "其他"))
        File(context.filesDir, KeywordStore.FILE_NAME).writeText(appJson.encodeToString(old), Charsets.UTF_8)
        val store = KeywordStore(context)
        assertEquals("BL／男性恋爱", store.state.value.entries.single { it.original == "BL" }.category)
        assertEquals("病娇女主角", store.state.value.entries.single { it.original == "ヤンデレヒロイン" }.translation)
        store.renameCategory("BL／男性恋爱", "我的 BL")
        store.deleteCategory("TS／性转")
        store.flush()
        val reopened = KeywordStore(context)
        assertEquals(store.state.value, reopened.state.value)
        assertFalse("BL／男性恋爱" in reopened.state.value.categories)
        assertFalse("TS／性转" in reopened.state.value.categories)
        assertEquals("我的 BL", reopened.state.value.entries.single { it.original == "BL" }.category)
    }
    private fun fixture(block: suspend (Context, Context) -> Unit) = runBlocking {
        withContext(Dispatchers.IO) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(base.cacheDir, "keyword-library-test-${UUID.randomUUID()}")
            try { block(IsolatedContext(base, File(root, "source")), IsolatedContext(base, File(root, "target"))) }
            finally { delay(400); root.deleteRecursively() }
        }
    }

    @Test fun legacyPersistenceMigratesAndDeletedCategoriesStayDeletedAfterRestart() = fixture { context, _ ->
        File(context.filesDir, KeywordStore.FILE_NAME).writeText(appJson.encodeToString(listOf(KeywordEntry("ヤンデレ", "原有译名"))), Charsets.UTF_8)
        val store = KeywordStore(context)
        assertEquals("原有译名", store.state.value.entries.single { it.original == "ヤンデレ" }.translation)
        store.createCategory("新分类")
        store.editEntry("ヤンデレ", "保留译名", "新分类")
        store.renameCategory("新分类", "人物收藏")
        store.deleteCategory("题材")
        store.flush()
        val reopened = KeywordStore(context)
        assertEquals(store.state.value, reopened.state.value)
        assertFalse("题材" in reopened.state.value.categories)
        assertEquals("人物收藏", reopened.state.value.entries.single { it.original == "ヤンデレ" }.category)
        reopened.observe(listOf("首次遇见的新标签"))
        reopened.deleteCategory("人物收藏")
        reopened.flush()
        assertEquals("其他", KeywordStore(context).state.value.entries.single { it.original == "ヤンデレ" }.category)
    }

    @Test fun fullReadingBackupRestoresCustomAndEmptyCategories() = fixture { sourceContext, targetContext ->
        val source = KeywordStore(sourceContext)
        source.createCategory("已分类")
        source.createCategory("空分类")
        source.editEntry("ハーレム", "后宫译名", "已分类")
        val archive = ByteArrayOutputStream().also { LibraryBackupService(LocalStore(sourceContext), source).export(it, false) }.toByteArray()
        val target = KeywordStore(targetContext)
        val service = LibraryBackupService(LocalStore(targetContext), target)
        assertNull(service.restore(service.prepare(ByteArrayInputStream(archive)).stagingId))
        val restored = KeywordStore(targetContext).state.value
        assertTrue("空分类" in restored.categories)
        assertEquals("已分类", restored.entries.single { it.original == "ハーレム" }.category)
        assertEquals("后宫译名", restored.entries.single { it.original == "ハーレム" }.translation)
    }

    @Test fun changingCapacityPreservesStoredTagsAndUnlimitedResumesCollection() = fixture { context, _ ->
        val settings = LocalStore(context)
        val store = KeywordStore(context) { settings.state.value.keywordLimit }
        store.observe(listOf("已收集"))
        val original = store.state.value
        settings.update { it.copy(keywordLimit = 1) }
        store.observe(listOf("暂停收集"))
        assertEquals(original, store.state.value)
        assertTrue(runCatching { store.mergeLibrary(KeywordLibrary(listOf(KeywordEntry("禁止新增")))) }.isFailure)
        store.setTranslation("已收集", "保留修改")
        settings.flush(); store.flush()
        val restoredSettings = LocalStore(context)
        assertEquals(1, restoredSettings.state.value.keywordLimit)
        val reopened = KeywordStore(context) { restoredSettings.state.value.keywordLimit }
        assertEquals(original.entries.size, reopened.state.value.entries.size)
        assertEquals("保留修改", reopened.state.value.entries.single { it.original == "已收集" }.translation)
        restoredSettings.update { it.copy(keywordLimit = null) }
        reopened.observe(listOf("恢复收集"))
        reopened.flush()
        assertEquals(original.entries.size + 1, KeywordStore(context).state.value.entries.size)
    }
}

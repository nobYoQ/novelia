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
}

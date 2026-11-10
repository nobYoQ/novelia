package cc.novelia.app.data.stickers

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.R
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StickerPackImagesTest {
    @Test fun realImagesRemainDecodableAfterExportImportAndBrokenFilesDoNotInstall() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "sticker-images-${UUID.randomUUID()}")
        try {
            val store = StickerPackStore(root)
            store.load()
            val draft = store.beginDraft()
            store.addImages(draft.id, listOf(StickerSlot.Neutral to { context.resources.openRawResource(R.drawable.midori_reading) }))
            val pack = store.saveDraft(draft.id, "图片测试", "搭子", "#E15C86")
            val output = ByteArrayOutputStream()
            store.exportArchive(pack.id, output)
            val imported = store.importArchive(ByteArrayInputStream(output.toByteArray()))
            val bitmap = BitmapFactory.decodeFile(imported.images().getValue(StickerSlot.Neutral).absolutePath)
            assertNotNull(bitmap)
            assertTrue(bitmap.hasAlpha())
            bitmap.recycle()
            val bad = store.beginDraft()
            try {
                store.addImages(bad.id, listOf(StickerSlot.Neutral to { ByteArrayInputStream("broken image".toByteArray()) }))
                fail("应拒绝损坏图片")
            } catch(_: IllegalArgumentException) { }
            assertEquals(2, store.state.value.packs.size)
            assertTrue(store.state.value.drafts.single().pack.manifest.artwork.isEmpty())
        } finally { root.deleteRecursively() }
    }
}

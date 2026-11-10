package cc.novelia.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.R
import cc.novelia.app.data.stickers.StickerPackStore
import cc.novelia.app.data.stickers.StickerSlot
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StickerPackEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editorRestoresInputRequiresNeutralAndSavesAUsablePack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "sticker-editor-${UUID.randomUUID()}")
        try {
            val store = StickerPackStore(root)
            val initial = runBlocking { store.load(); store.beginDraft() }
            val id = initial.id
            val restoration = StateRestorationTester(compose)
            var savedId: String? = null
            restoration.setContent {
                val state by store.state.collectAsState()
                MaterialTheme { StickerPackEditor(store, state.drafts.firstOrNull { it.id == id } ?: initial, {}, { savedId = it.id }) }
            }
            compose.onNodeWithTag("sticker-pack-save").assertIsNotEnabled()
            compose.onNodeWithTag("sticker-pack-title").performTextReplacement("我的自定义贴纸")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("sticker-pack-title").assertTextContains("我的自定义贴纸")
            runBlocking {
                store.addImages(id, listOf(StickerSlot.Neutral to { context.resources.openRawResource(R.drawable.midori_wave) }))
            }
            compose.onNodeWithTag("sticker-pack-save").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { savedId != null }
            val restarted = StickerPackStore(root)
            runBlocking { restarted.load() }
            assertEquals("我的自定义贴纸", restarted.state.value.packs.single().manifest.title)
            assertTrue(restarted.state.value.packs.single().images().getValue(StickerSlot.Neutral).isFile)
        } finally { root.deleteRecursively() }
    }
}

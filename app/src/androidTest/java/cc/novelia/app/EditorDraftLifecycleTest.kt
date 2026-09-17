package cc.novelia.app

import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.LocalStore
import cc.novelia.app.ui.DraftPersistence
import cc.novelia.app.ui.rememberDraftPersistence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class EditorDraftLifecycleTest {
    @get:Rule val compose = createComposeRule()

    private fun isolatedStore(): Pair<File, LocalStore> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "editor-draft-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolatedContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = directory
        }
        return directory to LocalStore(isolatedContext)
    }

    @Test fun disposalSavesInputChangedInTheSameFrameAsLeaving() {
        val (directory, store) = isolatedStore()
        var visible by mutableStateOf(true)
        var text by mutableStateOf("已有正文")
        try {
            compose.setContent {
                if(visible) rememberDraftPersistence(store, "article:test") { text }
            }
            compose.runOnIdle { text += "及最后的输入"; visible = false }
            compose.waitForIdle()
            assertEquals(text, store.state.value.drafts["article:test"])
            runBlocking { store.flush() }
            assertEquals(text, LocalStore(store.context).state.value.drafts["article:test"])
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            runBlocking { store.flush() }
            directory.deleteRecursively()
        }
    }

    @Test fun successfulPublishIsNotRestoredAsDraftWhenDisposed() {
        val (directory, store) = isolatedStore()
        var visible by mutableStateOf(true)
        lateinit var persistence: DraftPersistence
        try {
            compose.setContent {
                if(visible) persistence = rememberDraftPersistence(store, "wenku:test") { "已提交内容" }
            }
            compose.runOnIdle {
                persistence.save()
                persistence.submittedSuccessfully("已提交内容")
                visible = false
            }
            compose.waitForIdle()
            assertFalse(store.state.value.drafts.containsKey("wenku:test"))
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            runBlocking { store.flush() }
            directory.deleteRecursively()
        }
    }

    @Test fun switchingEditorKeysDoesNotSaveNextDocumentIntoPreviousDraft() {
        val (directory, store) = isolatedStore()
        var draftKey by mutableStateOf("first")
        var visible by mutableStateOf(true)
        try {
            compose.setContent {
                if(visible) {
                    val key = draftKey
                    rememberDraftPersistence(store, key) { "正文：$key" }
                }
            }
            compose.runOnIdle { draftKey = "second" }
            compose.waitForIdle()
            assertEquals("正文：first", store.state.value.drafts["first"])
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            assertEquals("正文：second", store.state.value.drafts["second"])
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            runBlocking { store.flush() }
            directory.deleteRecursively()
        }
    }
}

package cc.novelia.app.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.ui.markdown.MarkdownCommentInput
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalReducedMotion
import org.junit.Rule
import org.junit.Test

class CommentComposerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun collapsedEditorReopensWithItsDraftInAllMotionModes() {
        var reduced by mutableStateOf(false)
        var eInk by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reduced, LocalEInkMode provides eInk) {
                MaterialTheme {
                    var expanded by remember { mutableStateOf(false) }
                    var text by remember { mutableStateOf("") }
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            Spacer(Modifier.weight(1f))
                            CommentEditor(expanded, { expanded = false }) {
                                MarkdownCommentInput(text, { text = it }, "写下评论") {}
                            }
                        }
                        CommentButton(!expanded, { expanded = true }, Modifier.align(Alignment.BottomEnd))
                    }
                }
            }
        }
        compose.onNodeWithTag("comment-body").assertDoesNotExist()
        compose.onNodeWithContentDescription("写评论").performClick()
        compose.onNodeWithTag("comment-body").performTextInput("保留这份草稿")
        compose.onNodeWithContentDescription("收起评论框").performClick()
        compose.onNodeWithTag("comment-body").assertDoesNotExist()
        for(mode in 0..2) {
            compose.runOnIdle { reduced = mode == 1; eInk = mode == 2 }
            compose.onNodeWithContentDescription("写评论").performClick()
            compose.onNodeWithTag("comment-body").assertTextContains("保留这份草稿")
            compose.onNodeWithTag("comment-compose-button").assertDoesNotExist()
            compose.onNodeWithContentDescription("收起评论框").performClick()
            compose.onNodeWithTag("comment-body").assertDoesNotExist()
        }
    }

    @Test fun enablingStaticModeDuringOpeningImmediatelyFinishesTheTransition() {
        var expanded by mutableStateOf(false)
        var reduced by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                MaterialTheme { CommentEditor(expanded, { expanded = false }) {
                    MarkdownCommentInput("草稿", {}, "写下评论") {}
                } }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { expanded = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("comment-body").assertIsDisplayed().assertTextContains("草稿")
        compose.onNodeWithContentDescription("收起评论框").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("comment-editor").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }
}

package cc.novelia.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.*
import org.junit.Rule
import org.junit.Test

class MarkdownToolbarInteractionTest {
    @get:Rule val compose = createComposeRule()

    private fun exercise(comment: Boolean) {
        compose.setContent {
            NoveliaTheme("light") {
                var text by remember { mutableStateOf("前文选中内容后文") }
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    val height = maxHeight.coerceAtMost(420.dp)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        if (comment) MarkdownCommentInput(text, { text = it }, "写下评论") {
                            TextButton(onClick = { text = "" }) { Text("清空测试草稿") }
                        } else MarkdownEditor(text, { text = it }, height)
                    }
                }
            }
        }
        val field = compose.onNodeWithTag(if (comment) "comment-body" else "article-body")
        field.performTextInputSelection(TextRange(2, 6))
        compose.onNodeWithContentDescription("插入粗体").performClick()
        field.assertTextContains("前文**选中内容**后文").assertIsFocused()
        compose.onNodeWithContentDescription("插入粗体").performClick()
        field.assertTextContains("前文选中内容后文")
        compose.onNodeWithContentDescription("插入折叠内容").performScrollTo().performClick()
        field.assertTextContains("前文\n::: details 点击展开\n选中内容\n:::\n后文").assertIsFocused()
        compose.onNodeWithContentDescription("Markdown 格式帮助").performScrollTo().performClick()
        compose.onNodeWithText("Markdown 格式帮助").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        field.assertTextContains("前文\n::: details 点击展开\n选中内容\n:::\n后文")
        if (comment) {
            compose.onNodeWithText("清空测试草稿").performClick()
            field.assertTextEquals("写下评论", "")
            compose.onNodeWithContentDescription("插入评分").performScrollTo().performClick()
            field.assertTextContains("::: star 5\n").assertIsFocused()
        }
    }

    @Test fun articleToolbarKeepsSelectionAndFocus() = exercise(false)
    @Test fun commentToolbarKeepsSelectionAndResetsAfterSending() = exercise(true)
}

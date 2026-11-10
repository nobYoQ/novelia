package cc.novelia.app.ui.feedback

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cc.novelia.app.R
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.ui.components.book.DefaultBookCover
import cc.novelia.app.ui.theme.LocalReducedMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StickerPackRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingPackUpdatesExistingArtworkCoverCompanionAndActiveSnackbar() {
        val preview = StickerPack("preview", "预览集", "预览搭子", mapOf(Sticker.Neutral to R.drawable.midori_wave))
        var pack by mutableStateOf(StickerPacks.default)
        val snackbar = SnackbarHostState()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalStickerPack provides pack, LocalReducedMotion provides true) {
                Surface { Column {
                    StickerIllustration(Sticker.Reading, Modifier.size(64.dp).testTag("artwork"))
                    DefaultBookCover(BookCard(BookRef("local", "pack-test"), "测试封面"), Modifier.size(80.dp, 100.dp).testTag("cover"))
                    StickerCompanion(Modifier.size(88.dp))
                    Box(Modifier.testTag("snackbar")) { StickerSnackbarHost(snackbar) }
                } }
                LaunchedEffect(Unit) {
                    snackbar.showSnackbar(StickerSnackbarVisuals("完成提示", Sticker.Celebrate, duration = SnackbarDuration.Indefinite))
                }
            }
        } }
        fun pixels(tag: String): IntArray = compose.onNodeWithTag(tag).captureToImage().let { image ->
            IntArray(image.width * image.height).also { image.readPixels(it) }
        }
        val dynamicIcon = compose.onNodeWithContentDescription("${cc.novelia.app.ui.feedback.StickerPacks.default.companionName}，动态icon")
        dynamicIcon.assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "等待互动"))
        assertEquals("互动，切换表情", dynamicIcon.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithText("完成提示").assertIsDisplayed()
        val tags = listOf("artwork", "cover", "snackbar")
        val before = tags.associateWith(::pixels)
        compose.runOnIdle { pack = preview }
        tags.forEach { tag -> assertFalse("$tag must use the new pack", before.getValue(tag).contentEquals(pixels(tag))) }
        compose.onNodeWithContentDescription("预览搭子，动态icon").assertIsDisplayed().performClick()
        compose.onNodeWithText("完成提示").assertIsDisplayed()
        compose.runOnIdle { pack = StickerPacks.default }
        tags.forEach { tag -> assertArrayEquals("$tag must restore the original pack", before.getValue(tag), pixels(tag)) }
    }
}

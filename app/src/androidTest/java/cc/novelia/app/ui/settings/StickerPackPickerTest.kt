package cc.novelia.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.R
import cc.novelia.app.ui.feedback.Sticker
import cc.novelia.app.ui.feedback.StickerPack
import cc.novelia.app.ui.feedback.StickerPacks
import org.junit.Rule
import org.junit.Test

class StickerPackPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pickerSwitchesBetweenPacksAndUnknownIdShowsTheDefaultSelection() {
        val preview = StickerPack("preview", "预览集", "预览搭子", mapOf(Sticker.Neutral to R.drawable.midori_wave))
        var selectedId by mutableStateOf("removed-pack")
        compose.setContent { MaterialTheme {
            StickerPackPicker(selectedId, { selectedId = it }, packs = listOf(StickerPacks.default, preview))
        } }
        compose.onNodeWithTag("sticker-pack-${StickerPacks.default.id}").assertIsSelected()
        compose.onNodeWithTag("sticker-pack-preview").performClick().assertIsSelected()
        compose.onNodeWithTag("sticker-pack-${StickerPacks.default.id}").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithTag("sticker-pack-preview").assertIsNotSelected()
    }
}

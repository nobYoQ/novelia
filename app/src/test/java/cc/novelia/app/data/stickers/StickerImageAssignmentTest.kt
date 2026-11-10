package cc.novelia.app.data.stickers

import org.junit.Assert.*
import org.junit.Test

class StickerImageAssignmentTest {
    @Test fun namesAreMatchedBeforeUnnamedPicturesFillTheRemainingSlots() {
        assertEquals(listOf(StickerSlot.Neutral, StickerSlot.Happy, StickerSlot.Wave),
            assignStickerImages(listOf("photo.png", "happy.webp", "招手.jpg"), emptySet()))
        assertEquals(listOf(StickerSlot.Happy), assignStickerImages(listOf("photo.png"), setOf(StickerSlot.Neutral)))
        assertEquals(listOf(StickerSlot.Neutral), assignStickerImages(listOf("NEUTRAL.PNG"), StickerSlot.entries.toSet()))
    }

    @Test fun duplicatesAndExcessImagesCannotBeSilentlyDropped() {
        assertThrows(IllegalArgumentException::class.java) { assignStickerImages(listOf("neutral.png", "常态.png"), emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { assignStickerImages(listOf("photo.png"), StickerSlot.entries.toSet()) }
        assertThrows(IllegalArgumentException::class.java) { assignStickerImages(List(14) { "$it.png" }, emptySet()) }
    }
}

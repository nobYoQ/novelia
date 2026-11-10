package cc.novelia.app.ui.feedback

import cc.novelia.app.R
import cc.novelia.app.data.model.DEFAULT_STICKER_PACK_ID
import org.junit.Assert.*
import org.junit.Test

class StickerPacksTest {
    @Test fun defaultPackPreservesEveryExistingExpression() {
        val expected = listOf(R.drawable.midori_neutral, R.drawable.midori_happy, R.drawable.midori_wink,
            R.drawable.midori_love, R.drawable.midori_welcome, R.drawable.midori_curious,
            R.drawable.midori_reading, R.drawable.midori_thinking, R.drawable.midori_celebrate,
            R.drawable.midori_approve, R.drawable.midori_sleep, R.drawable.midori_concerned, R.drawable.midori_wave)
        assertEquals(expected, Sticker.entries.map { StickerPacks.default.drawable(it) })
        assertEquals(DEFAULT_STICKER_PACK_ID, StickerPacks.default.id)
        assertEquals(StickerPacks.all.size, StickerPacks.all.map { it.id }.distinct().size)
        StickerPacks.all.forEach { assertSame(it, StickerPacks.resolve(it.id)) }
    }

    @Test fun unknownPackIdsResolveToTheDefault() {
        listOf("", "missing-pack", "future-pack").forEach { id ->
            assertSame(StickerPacks.default, StickerPacks.resolve(id))
        }
    }

    @Test fun incompletePackUsesItsOwnNeutralAndCopiesTheSuppliedArtwork() {
        val artwork = mutableMapOf(Sticker.Neutral to 1, Sticker.Happy to 2)
        val pack = StickerPack("sample", "示例", "搭子", artwork)
        artwork[Sticker.Neutral] = 3
        artwork[Sticker.Celebrate] = 4
        assertEquals(1, pack.drawable(Sticker.Neutral))
        assertEquals(2, pack.drawable(Sticker.Happy))
        assertEquals(1, pack.drawable(Sticker.Celebrate))
    }

    @Test fun packMustHaveUsableNeutralArtwork() {
        for(artwork in listOf(emptyMap(), mapOf(Sticker.Happy to 1), mapOf(Sticker.Neutral to 0))) {
            assertThrows(IllegalArgumentException::class.java) { StickerPack("sample", "示例", "搭子", artwork) }
        }
    }
}

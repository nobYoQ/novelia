package cc.novelia.app.data.model

import org.junit.Assert.*
import org.junit.Test

class ThemePresetsTest {
    private val image = "a".repeat(64)
    private val forest = ThemePreset("forest", "雨林", accent = 0x007766, image = image, blur = 65)

    @Test fun editCopyAndDeleteKeepIndependentSelectionsAndShareOnlyTheImage() {
        val copy = forest.copy(id = "evening", name = "傍晚", blur = 10)
        val both = ThemePresets().saveAndSelect(forest).saveAndSelect(copy)
        val edited = both.saveAndSelect(forest.copy(overlay = 45))
        assertEquals(listOf("forest", "evening"), edited.items.map { it.id })
        assertEquals(45, edited.active?.overlay)
        assertEquals(10, edited.items.last().blur)
        val remaining = edited.remove("forest")
        assertNull(remaining.activeId)
        assertEquals(setOf(image), remaining.images)
        assertTrue(remaining.remove("evening").images.isEmpty())
    }
}

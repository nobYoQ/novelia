package cc.novelia.app.reader

import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReaderIndentTest {
    @Test fun legacySettingsKeepTheirIndentDefaults() {
        assertEquals(2, appJson.decodeFromString<ReaderSettings>("{}").resolvedIndentSize)
        assertEquals(0, appJson.decodeFromString<ReaderSettings>("""{"indent":false}""").resolvedIndentSize)
    }

    @Test fun customIndentSurvivesBackupsAndIndependentModes() {
        val normal = ReaderSettings(indentSize = 1)
        val ink = normal.withEInkMode(true).copy(indentSize = 7)
        val restored = appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(SettingsBackup(reader = ink))).reader
        assertEquals(7, restored.resolvedIndentSize)
        assertEquals(1, restored.withEInkMode(false).resolvedIndentSize)
        assertEquals(7, restored.withEInkMode(false).withEInkMode(true).resolvedIndentSize)
        val state = LibraryState(reader = normal, bookSettings = mapOf("local/book" to ink))
        assertEquals(state, appJson.decodeFromString<LibraryState>(appJson.encodeToString(state)))
        val differences = readerSettingsDifferences(normal, ReaderSettings()).associateBy { it.label }
        assertEquals("1 格", differences.getValue("首行缩进").book)
    }

    @Test fun bilingualSearchAndProgressOffsetsUseTheCustomNumberOfSpaces() {
        val paragraph = ReadingParagraph(0, listOf(TextPart("abc", "jp"), TextPart("译文", "gpt")))
        for(size in listOf(0, 1, 2, 7, 20)) {
            val settings = ReaderSettings(indentSize = size, parallel = true)
            val rendered = settings.indentText + "abc\nGPT\n" + settings.indentText + "译文"
            assertEquals(listOf(0, size + 8), paragraphPartStarts(paragraph, settings))
            val match = findReadingTextMatches(listOf(paragraph), "文").single()
            assertEquals(rendered.indexOf('文'), match.textOffset(paragraph, settings))
            val seek = ChapterSeekIndex(listOf(paragraph), settings)
            assertEquals(ChapterSeekAnchor(0, rendered.lastIndex), seek.anchorAt(1f))
        }
    }

    @Test fun onlyCustomIndentChangesInvalidateLegacyAnchors() {
        val legacy = "zh:sakura,gpt,youdao:false:false:true"
        assertEquals(legacy, WebDavProjection.anchorSource(ReaderSettings()))
        assertEquals("zh:sakura,gpt,youdao:false:false:false", WebDavProjection.anchorSource(ReaderSettings(indent = false)))
        assertNotEquals(legacy, WebDavProjection.anchorSource(ReaderSettings(indentSize = 1)))
        assertNotEquals(WebDavProjection.anchorSource(ReaderSettings(indentSize = 1)),
            WebDavProjection.anchorSource(ReaderSettings(indentSize = 1, textReplacements = mapOf("旧" to "新"))))
        assertEquals(WebDavProjection.anchorSource(ReaderSettings(indent = false)),
            WebDavProjection.anchorSource(ReaderSettings(indent = false, indentSize = 7)))
        assertEquals(20, ReaderSettings(indentSize = Int.MAX_VALUE).resolvedIndentSize)
        assertFalse(ReaderSettings(indentSize = -1).validIndentSize)
    }
}

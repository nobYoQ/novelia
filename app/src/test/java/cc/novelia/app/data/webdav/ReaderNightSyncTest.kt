package cc.novelia.app.data.webdav

import cc.novelia.app.data.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReaderNightSyncTest {
    @Test fun stylesSyncButBrightnessAndLocalNightFontsStayOnReceivingDevice() {
        val source = LibraryState(reader = ReaderSettings(nightReading = true, fontSize = 64f,
            nightBrightness = ReaderBrightness.night().copy(maximum = .4f),
            nightPreset = ReaderNightPreset(fontSize = 64f, lineHeight = 2.2f, fontFamily = "custom", customFont = ReaderCustomFont("a".repeat(64), "本机字体"))))
        val projection = WebDavProjection.library(source, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true)
        val docs = SyncReplica(deviceId = "a").track(emptyMap(), projection).bind("dataset").documents
        WebDavProjection.validateRecords(docs.getValue(SyncDomain.SETTINGS))
        val fields = projection.getValue(SyncDomain.SETTINGS).getValue("@reader")
        assertFalse("customFont" in fields.getValue("nightPreset").jsonObject)
        assertFalse("fontFamily" in fields.getValue("nightPreset").jsonObject)
        val target = LibraryState(reader = ReaderSettings(nightPreset = ReaderNightPreset(fontFamily = "monospace")))
        val restored = WebDavProjection.applyLibrary(target, docs, setOf(SyncDomain.SETTINGS))
        assertEquals(64f, restored.reader.fontSize, 0f)
        assertEquals(64f, restored.reader.nightPreset.fontSize!!, 0f)
        assertFalse(restored.reader.nightReading)
        assertEquals(target.reader.nightBrightness, restored.reader.nightBrightness)
        assertEquals(2.2f, restored.reader.nightPreset.lineHeight!!, 0f)
        assertEquals("monospace", restored.reader.nightPreset.fontFamily)
        val withDevice = WebDavProjection.applyLibrary(target, docs, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true)
        assertTrue(withDevice.reader.nightReading)
        assertEquals(.4f, withDevice.reader.nightBrightness.maximum, 0f)
        assertEquals("monospace", withDevice.reader.nightPreset.fontFamily)
    }

    @Test fun legacyDeviceAndModeGroupsCanOmitNightFieldsAndInvalidPresetsAreRejected() {
        val source = ReaderSettings().withEInkMode(true)
        val projection = WebDavProjection.library(LibraryState(reader = source), setOf(SyncDomain.SETTINGS), includeDevicePreferences = true)
        val fields = projection.getValue(SyncDomain.SETTINGS).getValue("@reader")
        val added = setOf("nightReading", "nightBrightness", "brightnessPreferences", "nightPreset", "letterSpacing")
        val group = fields.getValue("devicePreferences").jsonObject
        val legacy = JsonObject((group - added).mapValues { (key, value) ->
            if(key in setOf("activeModeSettings", "beforeEInk") && value is JsonObject) JsonObject(value - added) else value
        })
        WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "devicePreferences", legacy)
        val bad = JsonObject(fields.getValue("nightPreset").jsonObject + ("letterSpacing" to JsonPrimitive(2)))
        assertThrows(IllegalArgumentException::class.java) {
            WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "nightPreset", bad)
        }
        val badBrightness = JsonObject(group.getValue("nightBrightness").jsonObject + ("maximum" to JsonPrimitive(.001)))
        assertThrows(IllegalArgumentException::class.java) {
            WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "devicePreferences", JsonObject(group + ("nightBrightness" to badBrightness)))
        }
    }
}

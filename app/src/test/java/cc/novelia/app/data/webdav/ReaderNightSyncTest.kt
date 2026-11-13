package cc.novelia.app.data.webdav

import cc.novelia.app.data.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReaderNightSyncTest {
    @Test fun dpTypographySyncsAsAnAtomicGroupAndPreservesModeAndNightOverrides() {
        val normal = ReaderSettings().withDpTypography { it }.copy(typographyDp = ReaderTypographyDp(32f, 12f, 1f))
        val ink = normal.withEInkMode(true).copy(typographyDp = ReaderTypographyDp(64f, 24f, 2f),
            nightPreset = normal.nightPreset.copy(typographyDp = ReaderNightTypographyDp(48f, 20f, 1.5f)))
        val projection = WebDavProjection.library(LibraryState(reader = ink), setOf(SyncDomain.SETTINGS), includeDevicePreferences = true)
        val docs = SyncReplica(deviceId = "a").track(emptyMap(), projection).bind("dataset").documents
        WebDavProjection.validateRecords(docs.getValue(SyncDomain.SETTINGS))
        val restored = WebDavProjection.applyLibrary(LibraryState(), docs, setOf(SyncDomain.SETTINGS), includeDevicePreferences = true).reader
        assertEquals(ink.typographyDp, restored.typographyDp)
        assertEquals(ink.nightPreset.typographyDp, restored.nightPreset.typographyDp)
        assertEquals(normal.typographyDp, restored.withEInkMode(false).typographyDp)
    }

    @Test fun legacySyncWithoutDpUnitsKeepsLocalDpTypography() {
        val local = LibraryState(reader = ReaderSettings().withDpTypography { it * 1.5f })
        val legacy = WebDavProjection.library(LibraryState(), setOf(SyncDomain.SETTINGS), includeDevicePreferences = true)
        val docs = SyncReplica(deviceId = "old").track(emptyMap(), legacy).bind("dataset").documents
        WebDavProjection.validateRecords(docs.getValue(SyncDomain.SETTINGS))
        for(devices in listOf(false, true)) {
            val restored = WebDavProjection.applyLibrary(local, docs, setOf(SyncDomain.SETTINGS), includeDevicePreferences = devices).reader
            assertEquals(local.reader.typographyDp, restored.typographyDp)
            assertEquals(local.reader.nightPreset.typographyDp, restored.nightPreset.typographyDp)
        }
    }

    @Test fun invalidOrIncompleteDpGroupsAreRejected() {
        for(raw in listOf("""{"fontSize":129,"lineSpacing":0,"letterSpacing":0}""",
            """{"fontSize":20,"lineSpacing":-1,"letterSpacing":0}""", """{"fontSize":20}""")) {
            assertThrows(IllegalArgumentException::class.java) {
                WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "typographyDp", Json.parseToJsonElement(raw))
            }
        }
    }

    @Test fun paragraphSpacingAcceptsExpandedRangeAndRejectsValuesAboveIt() {
        WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "paragraphSpacing", JsonPrimitive(128))
        assertThrows(IllegalArgumentException::class.java) {
            WebDavProjection.validateField(SyncDomain.SETTINGS, "@reader", "paragraphSpacing", JsonPrimitive(129))
        }
    }

    @Test fun stylesSyncButBrightnessAndLocalNightFontsStayOnReceivingDevice() {
        val source = LibraryState(reader = ReaderSettings(nightReading = true, fontSize = 64f, paragraphSpacing = 128f,
            nightBrightness = ReaderBrightness.night().copy(maximum = .4f),
            nightPreset = ReaderNightPreset(fontSize = 64f, paragraphSpacing = 128f, lineHeight = 2.2f, fontFamily = "custom", customFont = ReaderCustomFont("a".repeat(64), "本机字体"))))
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
        assertEquals(128f, restored.reader.paragraphSpacing, 0f)
        assertEquals(128f, restored.reader.nightPreset.paragraphSpacing!!, 0f)
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

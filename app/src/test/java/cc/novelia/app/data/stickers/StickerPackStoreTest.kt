package cc.novelia.app.data.stickers

import cc.novelia.app.data.storage.appJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class StickerPackStoreTest {
    private fun newStore(root: File) = StickerPackStore(root) { image ->
        require(image.readText(Charsets.UTF_8) == "image") { "无效图片" }
        "png"
    }
    private fun fixture(block: suspend (File, StickerPackStore) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("sticker-packs-").toFile()
        try { val store = newStore(root); store.load(); block(root, store) }
        finally { root.deleteRecursively() }
    }
    private fun image(slot: StickerSlot = StickerSlot.Neutral) = slot to { ByteArrayInputStream("image".toByteArray()) as InputStream }
    private suspend fun install(store: StickerPackStore): LocalStickerPack {
        val draft = store.beginDraft()
        store.addImages(draft.id, listOf(image()))
        return store.saveDraft(draft.id, "我的贴纸", "搭子", "#123ABC")
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("应拒绝无效贴纸包或修改") }
        catch(_: IllegalArgumentException) { }
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    private fun archive(manifest: StickerPackManifest, extra: Map<String, ByteArray> = emptyMap()) = zip(
        mapOf("pack.json" to appJson.encodeToString(manifest).toByteArray(Charsets.UTF_8), "neutral.png" to "image".toByteArray()) + extra)

    @Test fun createCopyExportImportRestartAndDeleteKeepIndependentPacks() = fixture { root, store ->
        val first = install(store)
        assertTrue(first.images().getValue(StickerSlot.Neutral).isFile)
        assertTrue(store.state.value.drafts.isEmpty())
        val output = ByteArrayOutputStream()
        store.exportArchive(first.id, output)
        val copy = store.importArchive(ByteArrayInputStream(output.toByteArray()))
        assertNotEquals(first.id, copy.id)
        assertEquals(first.manifest.title, copy.manifest.title)
        val restarted = newStore(root).also { it.load() }
        assertEquals(setOf(first.id, copy.id), restarted.state.value.packs.map { it.id }.toSet())
        assertEquals("#123ABC", restarted.state.value.packs.first().manifest.heartColor)
        store.remove(first.id)
        assertEquals(listOf(copy.id), store.state.value.packs.map { it.id })
        assertEquals("image", copy.images().getValue(StickerSlot.Neutral).readText())
        rejected { store.remove("midori") }
    }

    @Test fun importPreservesPortableIdOnAnotherDevice() = fixture { _, store ->
        val pack = install(store)
        val bytes = ByteArrayOutputStream().also { store.exportArchive(pack.id, it) }.toByteArray()
        val otherRoot = Files.createTempDirectory("sticker-other-").toFile()
        try {
            val other = newStore(otherRoot)
            other.load()
            val imported = other.importArchive(ByteArrayInputStream(bytes))
            assertEquals(pack.id, imported.id)
            assertEquals(pack.manifest.companionName, imported.manifest.companionName)
        } finally { otherRoot.deleteRecursively() }
    }

    @Test fun blankCompanionNameUsesATitleWithinTheCompanionLengthLimit() = fixture { _, store ->
        val draft = store.beginDraft()
        store.addImages(draft.id, listOf(image()))
        val title = "贴纸".repeat(20)
        val saved = store.saveDraft(draft.id, title, "", "#E15C86")
        assertEquals(title, saved.manifest.title)
        assertEquals(title.take(24), saved.manifest.companionName)
    }

    @Test fun editsRemainDraftsUntilCommitAndCancelLeavesOriginalUntouched() = fixture { root, store ->
        val original = install(store)
        val draft = store.beginDraft(original.id)
        store.addImages(draft.id, listOf(image(StickerSlot.Happy)))
        assertFalse(StickerSlot.Happy.key in store.state.value.packs.single().manifest.artwork)
        store.updateDraftDetails(draft.id, "", "草稿搭子", "#1")
        val restarted = newStore(root).also { it.load() }
        assertEquals("", restarted.state.value.drafts.single().pack.manifest.title)
        assertEquals("#1", restarted.state.value.drafts.single().pack.manifest.heartColor)
        assertEquals(original.manifest, restarted.state.value.packs.single().manifest)
        restarted.discardDraft(draft.id)
        assertTrue(restarted.state.value.drafts.isEmpty())
        assertEquals(original.manifest, restarted.state.value.packs.single().manifest)
        val editing = restarted.beginDraft(original.id)
        restarted.addImages(editing.id, listOf(image(StickerSlot.Wave)))
        val saved = restarted.saveDraft(editing.id, " 修改后 ", "", "#abcdef")
        assertEquals(original.id, saved.id)
        assertEquals("修改后", saved.manifest.companionName)
        assertEquals("#ABCDEF", saved.manifest.heartColor)
        assertTrue(StickerSlot.Wave.key in saved.manifest.artwork)
        assertFalse(original.images().getValue(StickerSlot.Neutral).exists())
    }

    @Test fun failedImageBatchDoesNotCommitAnyChangesOrDeletePreviousPictures() = fixture { _, store ->
        val draft = store.beginDraft()
        val before = store.addImages(draft.id, listOf(image()))
        val originalFile = before.pack.images().getValue(StickerSlot.Neutral)
        rejected {
            store.addImages(draft.id, listOf(image(StickerSlot.Happy), StickerSlot.Wave to { ByteArrayInputStream("bad".toByteArray()) }))
        }
        assertEquals(before, store.state.value.drafts.single())
        assertEquals("image", originalFile.readText())
        assertEquals(2, originalFile.parentFile!!.listFiles()!!.size) // 原图与草稿清单，失败文件已清理。
        rejected { store.saveDraft(draft.id, "", "搭子", "#123ABC") }
        assertTrue(store.state.value.packs.isEmpty())
        store.removeImage(draft.id, StickerSlot.Neutral)
        rejected { store.saveDraft(draft.id, "名称", "搭子", "#123ABC") }
    }

    @Test fun interruptedImageReadLeavesDraftUsable() = fixture { _, store ->
        val draft = store.beginDraft()
        val broken = object : InputStream() { override fun read(): Int = throw IOException("读取中断") }
        try { store.addImages(draft.id, listOf(StickerSlot.Neutral to { broken })); fail("应传播读取失败") }
        catch(_: IOException) { }
        assertTrue(store.state.value.drafts.single().pack.manifest.artwork.isEmpty())
        store.addImages(draft.id, listOf(image()))
        assertTrue(store.saveDraft(draft.id, "恢复", "搭子", "#123456").images().isNotEmpty())
    }

    @Test fun archiveRejectsTraversalUnknownSlotsMissingImagesAndUnsupportedVersions() = fixture { root, store ->
        val manifest = StickerPackManifest(title = "测试", artwork = mapOf("neutral" to "neutral.png"))
        val bad = listOf(
            archive(manifest, mapOf("../outside.png" to "image".toByteArray())),
            archive(manifest.copy(artwork = mapOf("neutral" to "../outside.png"))),
            archive(manifest.copy(artwork = mapOf("unknown" to "neutral.png"))),
            archive(manifest.copy(artwork = mapOf("neutral" to "missing.png"))),
            archive(manifest.copy(version = 99)),
            archive(manifest.copy(heartColor = "red")),
            archive(manifest.copy(artwork = emptyMap())),
        )
        bad.forEach { bytes -> rejected { store.importArchive(ByteArrayInputStream(bytes)) } }
        assertTrue(store.state.value.packs.isEmpty())
        assertFalse(File(root, "outside.png").exists())
        assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun oversizeImagesAndDirectoryPayloadsAreRejectedBeforeInstallation() = fixture { _, store ->
        val manifest = StickerPackManifest(title = "过大", artwork = mapOf("neutral" to "neutral.png"))
        val oversized = archive(manifest, mapOf("neutral.png" to ByteArray(STICKER_IMAGE_LIMIT.toInt() + 1)))
        rejected { store.importArchive(ByteArrayInputStream(oversized)) }
        rejected { store.importArchive(ByteArrayInputStream(zip(mapOf("images/" to byteArrayOf(1))))) }
        assertTrue(store.state.value.packs.isEmpty())
    }

    @Test fun corruptedPackDoesNotPreventOtherPacksAndDraftsFromLoading() = fixture { root, store ->
        val broken = install(store)
        val healthy = install(store)
        val draft = store.beginDraft()
        File(broken.directory, "pack.json").writeText("broken", Charsets.UTF_8)
        val restarted = newStore(root)
        restarted.load()
        assertEquals(listOf(healthy.id), restarted.state.value.packs.map { it.id })
        assertEquals(listOf(draft.id), restarted.state.value.drafts.map { it.id })
        assertTrue(restarted.state.value.ready)
        assertNotNull(restarted.state.value.error)
    }

    @Test fun reimportDoesNotOverwriteAnExistingCorruptDirectory() = fixture { root, store ->
        val original = install(store)
        val bytes = ByteArrayOutputStream().also { store.exportArchive(original.id, it) }.toByteArray()
        val manifest = File(original.directory, "pack.json")
        manifest.writeText("corrupt", Charsets.UTF_8)
        val restarted = newStore(root).also { it.load() }
        val imported = restarted.importArchive(ByteArrayInputStream(bytes))
        assertNotEquals(original.id, imported.id)
        assertEquals("corrupt", manifest.readText())
    }

    @Test fun sharedArtworkIsCopiedOnceWhenEditing() = fixture { _, store ->
        val manifest = StickerPackManifest(title = "共用图片", artwork = StickerSlot.entries.associate { it.key to "neutral.png" })
        val installed = store.importArchive(ByteArrayInputStream(archive(manifest)))
        val draft = store.beginDraft(installed.id)
        assertEquals(1, draft.pack.manifest.artwork.values.distinct().size)
        val saved = store.saveDraft(draft.id, "保存共用图片", "搭子", "#E15C86")
        assertEquals(1, saved.manifest.artwork.values.distinct().size)
    }
}

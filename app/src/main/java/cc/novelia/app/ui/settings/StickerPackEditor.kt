package cc.novelia.app.ui.settings

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.stickers.LocalStickerPack
import cc.novelia.app.data.stickers.StickerDraft
import cc.novelia.app.data.stickers.StickerPackStore
import cc.novelia.app.data.stickers.StickerSlot
import cc.novelia.app.data.stickers.assignStickerImages
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.account.ProfileSectionTitle
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.components.documents.*
import cc.novelia.app.ui.reader.ReaderColorPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun StickerPackEditor(store: StickerPackStore, draft: StickerDraft, onBack: () -> Unit, onSaved: (LocalStickerPack) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val images = remember(draft) { draft.pack.images() }
    var title by rememberSaveable(draft.id) { mutableStateOf(draft.pack.manifest.title) }
    var companionName by rememberSaveable(draft.id) { mutableStateOf(draft.pack.manifest.companionName) }
    var heartColor by rememberSaveable(draft.id) { mutableStateOf(draft.pack.manifest.heartColor) }
    var pickingSlot by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var editingSlot by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var tab by rememberSaveable(draft.id) { mutableIntStateOf(0) }
    var showHelp by rememberSaveable(draft.id) { mutableStateOf(false) }
    var editingColor by rememberSaveable(draft.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val validColor = Regex("#[0-9a-fA-F]{6}").matches(heartColor)
    fun operation(block: suspend () -> Unit) {
        if(busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { error = e.friendlyMessage() }
            finally { busy = false }
        }
    }
    fun keepDraftAndBack() { operation { store.updateDraftDetails(draft.id, title, companionName, heartColor); onBack() } }
    BackHandler { if(!busy) keepDraftAndBack() }
    LaunchedEffect(draft.id, title, companionName, heartColor) {
        delay(400)
        try { store.updateDraftDetails(draft.id, title, companionName, heartColor) }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { error = "草稿暂未保存：${e.friendlyMessage()}" }
    }
    val singlePicker = rememberOpenDocumentPicker(DocumentPickerTypes.StickerImages) { uri ->
        val slot = StickerSlot.entries.firstOrNull { it.key == pickingSlot }
        if(uri != null && slot != null) operation {
            store.addImages(draft.id, listOf(slot to {
                context.contentResolver.openInputStream(uri) ?: kotlin.error("无法读取图片")
            }))
            pickingSlot = null
        }
    }
    val multiplePicker = rememberOpenMultipleDocumentsPicker(DocumentPickerTypes.StickerImages) { uris ->
        if(uris.isNotEmpty()) operation {
            val names = withContext(Dispatchers.IO) {
                uris.map { uri -> context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if(column >= 0 && it.moveToFirst()) it.getString(column) else null
                } ?: "图片" }
            }
            val assigned = assignStickerImages(names, images.keys)
            store.addImages(draft.id, uris.mapIndexed { index, uri -> assigned[index] to {
                context.contentResolver.openInputStream(uri) ?: kotlin.error("无法读取图片")
            } })
        }
    }
    ProfileDetailScreen(if(draft.originalId == null) "制作贴纸集" else "编辑贴纸集", { if(!busy) keepDraftAndBack() }, actions = {
        AppIconButton(onClick = { showHelp = true }) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, "贴纸制作帮助") }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            ChoiceRow("编辑内容", listOf("表情图片", "名称与配色"), tab) { tab = it }
            BoxWithConstraints(Modifier.weight(1f)) {
                val columns = stickerGridColumns(maxWidth)
                key(tab) {
                    ProfileDetailList(Modifier.fillMaxSize()) {
                        if(tab == 0) {
                            item(key = "summary") { StickerEditorSummary(title, images[StickerSlot.Neutral], images.size) { tab = 1 } }
                            item(key = "import") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppOutlinedButton(multiplePicker.launch, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sticker-images-import")) { Text("批量添加图片") }
                                DocumentPickerFallback(multiplePicker, enabled = !busy)
                                DocumentPickerFallback(singlePicker, enabled = !busy)
                            } }
                            item(key = "pictures-title") { ProfileSectionTitle("点选表情，添加或替换图片") }
                            items(StickerSlot.entries.chunked(columns), key = { "slots-${it.first().key}" }) { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { slot -> StickerEditorTile(slot, images[slot], !busy, { editingSlot = slot.key }, Modifier.weight(1f)) }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        } else {
                            item(key = "names-title") { ProfileSectionTitle("基本信息") }
                            item(key = "names") { StickerPackNameFields(title, { if(it.length <= 40) title = it },
                                companionName, { if(it.length <= 24) companionName = it }, !busy) }
                            item(key = "color-title") { ProfileSectionTitle("互动配色") }
                            item(key = "color") { StickerHeartColorPreference(heartColor, !busy) { editingColor = true } }
                        }
                    }
                }
            }
            StickerPageFooter("保存并使用", { operation { onSaved(store.saveDraft(draft.id, title, companionName, heartColor)) } },
                enabled = title.isNotBlank() && StickerSlot.Neutral in images && validColor, busy = busy, error = error,
                note = if(StickerSlot.Neutral !in images) "先添加一张常态图，即可保存" else if(!validColor) "请在名称与配色中选择爱心颜色"
                    else "已添加 ${images.size} / 13 个表情 · 其余沿用常态图",
                primaryModifier = Modifier.testTag("sticker-pack-save"))
        }
    }
    StickerSlot.entries.firstOrNull { it.key == editingSlot }?.let { slot ->
        StickerImageSlotSheet(slot, images[slot], onDismiss = { editingSlot = null }, onChoose = {
            pickingSlot = slot.key; editingSlot = null; singlePicker.launch()
        }, onRemove = {
            editingSlot = null
            operation { store.removeImage(draft.id, slot) }
        })
    }
    if(editingColor) ReaderColorPicker("爱心颜色", if(validColor) heartColor.removePrefix("#").toLong(16) else 0xE15C86,
        onDismiss = { editingColor = false }, onApply = { heartColor = "#%06X".format(it); editingColor = false })
    if(showHelp) StickerImportHelp { showHelp = false }
}

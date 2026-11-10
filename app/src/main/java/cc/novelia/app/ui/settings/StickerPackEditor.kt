package cc.novelia.app.ui.settings

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.stickers.LocalStickerPack
import cc.novelia.app.data.stickers.StickerDraft
import cc.novelia.app.data.stickers.StickerPackStore
import cc.novelia.app.data.stickers.StickerSlot
import cc.novelia.app.data.stickers.assignStickerImages
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.components.documents.*
import cc.novelia.app.ui.feedback.StickerIllustration
import cc.novelia.app.ui.feedback.asStickerPack
import coil.compose.AsyncImage
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
    ProfileDetailScreen(if(draft.originalId == null) "制作贴纸集" else "编辑贴纸集", { if(!busy) keepDraftAndBack() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            AppLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "details") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(title, { if(it.length <= 40) title = it }, enabled = !busy,
                            label = { Text("贴纸集名称") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("sticker-pack-title"))
                        OutlinedTextField(companionName, { if(it.length <= 24) companionName = it }, enabled = !busy,
                            label = { Text("搭子名称") }, supportingText = { Text("用于阅读搭子的无障碍描述，留空时取贴纸集名称的前 24 字") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("sticker-pack-companion-name"))
                        OutlinedTextField(heartColor, { if(it.length <= 7) heartColor = it }, enabled = !busy,
                            label = { Text("爱心颜色") }, isError = !validColor, supportingText = { Text("使用 #RRGGBB 格式，例如 #E15C86") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("sticker-pack-heart-color"))
                        AppOutlinedButton(multiplePicker.launch, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("sticker-images-import")) { Text("批量添加图片") }
                        DocumentPickerFallback(multiplePicker, enabled = !busy)
                        DocumentPickerFallback(singlePicker, enabled = !busy)
                        Text("支持 PNG / WebP / JPG，推荐透明背景。单张不超过 8 MB、4096 × 4096 像素，一套不超过 64 MB。至少需要常态图。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("批量添加时先按 neutral、happy 等英文名或“常态”“开心”等名称匹配，其余图片依次填入空位，可在下方逐个调整。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if(StickerSlot.Neutral in images) {
                            val preview = draft.pack.copy(manifest = draft.pack.manifest.copy(title = title.ifBlank { "预览" },
                                companionName = companionName.ifBlank { "阅读搭子" }, heartColor = if(validColor) heartColor else "#E15C86")).asStickerPack()
                            Text("效果预览", style = MaterialTheme.typography.titleSmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                listOf(StickerSlot.Neutral, StickerSlot.Reading, StickerSlot.Celebrate).forEach {
                                    StickerIllustration(it, Modifier.size(72.dp), preview)
                                }
                            }
                        }
                    }
                }
                items(StickerSlot.entries, key = { it.key }) { slot ->
                    Row(Modifier.fillMaxWidth().testTag("sticker-slot-${slot.key}"), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val file = images[slot] ?: images[StickerSlot.Neutral]
                        if(file != null) AsyncImage(file, "${slot.title}预览", Modifier.size(64.dp), contentScale = ContentScale.Fit)
                        else Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { Text("未添加", style = MaterialTheme.typography.bodySmall) }
                        Column(Modifier.weight(1f)) {
                            Text(slot.title, style = MaterialTheme.typography.titleMedium)
                            Text(if(slot in images) "已添加" else if(slot == StickerSlot.Neutral) "必需" else "使用常态图",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column {
                            AppTextButton(onClick = { pickingSlot = slot.key; singlePicker.launch() }, enabled = !busy,
                                modifier = Modifier.testTag("sticker-choose-${slot.key}")) { Text(if(slot in images) "替换" else "选择") }
                            if(slot in images) AppTextButton(onClick = { operation { store.removeImage(draft.id, slot) } }, enabled = !busy,
                                modifier = Modifier.testTag("sticker-remove-${slot.key}")) { Text("移除") }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if(busy) AppLinearProgressIndicator(Modifier.fillMaxWidth())
                AppButton(onClick = { operation { onSaved(store.saveDraft(draft.id, title, companionName, heartColor)) } },
                    enabled = !busy && title.isNotBlank() && StickerSlot.Neutral in images && validColor,
                    modifier = Modifier.fillMaxWidth().testTag("sticker-pack-save")) { Text("保存并使用") }
                Text("返回会保留草稿；保存前，修改不会影响正在使用的贴纸集。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

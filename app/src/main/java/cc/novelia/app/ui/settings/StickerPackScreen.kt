package cc.novelia.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.DEFAULT_STICKER_PACK_ID
import cc.novelia.app.data.stickers.StickerDraft
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.account.ProfileDetailCard
import cc.novelia.app.ui.account.ProfileMenuRow
import cc.novelia.app.ui.account.ProfileSectionTitle
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.components.documents.*
import cc.novelia.app.ui.feedback.StickerPacks
import cc.novelia.app.ui.feedback.asStickerPack
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun StickerPackScreen(c: AppController) {
    val store = c.app.stickerPacks
    val library by store.state.collectAsStateWithLifecycle()
    val state by c.store.state.collectAsStateWithLifecycle()
    val packs = remember(library.packs) { StickerPacks.all + library.packs.map { it.asStickerPack() } }
    val scope = rememberCoroutineScope()
    var draftId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    var cachedDraft by remember { mutableStateOf<StickerDraft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var discarding by rememberSaveable { mutableStateOf<String?>(null) }
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
    val importer = rememberOpenDocumentPicker(DocumentPickerTypes.Backup) { uri ->
        if(uri != null) operation {
            val pack = withContext(Dispatchers.IO) {
                c.app.contentResolver.openInputStream(uri)?.use { store.importArchive(it) } ?: kotlin.error("无法读取贴纸包")
            }
            c.store.update { it.copy(stickerPackId = pack.id) }
            previewId = pack.id
            c.message("贴纸集已导入并启用")
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = exportId
        exportId = null
        if(uri != null && id != null) operation {
            withContext(Dispatchers.IO) {
                c.app.contentResolver.openOutputStream(uri)?.use { store.exportArchive(id, it) } ?: kotlin.error("无法写入贴纸包")
            }
            c.message("贴纸包已导出")
        }
    }
    val diskDraft = library.drafts.firstOrNull { it.id == draftId }
    val draft = diskDraft ?: cachedDraft?.takeIf { it.id == draftId }
    SideEffect { if(diskDraft != null) cachedDraft = diskDraft }
    LaunchedEffect(draftId, library.ready) {
        if(draftId != null && library.ready && draft == null && store.state.value.drafts.none { it.id == draftId }) {
            draftId = null; error = "草稿已不存在"
        }
    }
    if(draft != null) {
        StickerPackEditor(store, draft, onBack = { draftId = null; cachedDraft = null }, onSaved = { pack ->
            c.store.update { it.copy(stickerPackId = pack.id) }
            draftId = null
            cachedDraft = null
            previewId = pack.id
            error = null
            c.message("贴纸集已保存并启用")
        })
        return
    }
    val preview = packs.firstOrNull { it.id == previewId }
    // 确认弹窗保留在页面分支之外，预览、编辑和列表共用同一组操作。
    deleting?.let { id -> ConfirmDialog("删除贴纸集？", "将删除本机的这套图片。正在使用时会切回默认集，建议先导出留存。",
        { deleting = null }, "删除") { operation {
            store.remove(id)
            if(c.store.state.value.stickerPackId == id) c.store.update { it.copy(stickerPackId = DEFAULT_STICKER_PACK_ID) }
            previewId = null
        } } }
    discarding?.let { id -> ConfirmDialog("放弃草稿？", "将删除这份草稿，已保存的贴纸集不受影响。",
        { discarding = null }, "放弃") { operation { store.discardDraft(id) } } }
    if(preview != null) {
        StickerPackDetailScreen(preview, preview.id == state.stickerPackId, busy, error, onBack = { previewId = null; error = null },
            onUse = { c.store.update { it.copy(stickerPackId = preview.id) } },
            onEdit = { operation { draftId = store.beginDraft(preview.id).id } },
            onExport = { exportId = preview.id; exporter.launch("novelia-stickers.zip") },
            onDelete = { deleting = preview.id })
        return
    }
    BackHandler(busy) { }
    ProfileDetailScreen("贴纸集", { if(!busy) c.back() }) { padding ->
        StickerPackPicker(state.stickerPackId, { id -> c.store.update { it.copy(stickerPackId = id) } },
            Modifier.padding(padding).fillMaxSize(), packs, enabled = !busy, showTitle = false,
            header = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileDetailCard {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("添加贴纸集", style = MaterialTheme.typography.titleMedium)
                            Text("从图片制作自己的贴纸，或导入已有贴纸包。", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            StickerActionButtons("制作贴纸集", { operation { draftId = store.beginDraft().id } }, enabled = !busy,
                                secondary = "导入贴纸包", onSecondary = importer.launch,
                                primaryModifier = Modifier.testTag("sticker-pack-create"), secondaryModifier = Modifier.testTag("sticker-pack-import"))
                        }
                    }
                    DocumentPickerFallback(importer, enabled = !busy)
                    if(busy) AppLinearProgressIndicator(Modifier.fillMaxWidth())
                    (error ?: library.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if(library.drafts.isNotEmpty()) ProfileSectionTitle("未完成的制作 · ${library.drafts.size}")
                    library.drafts.forEach { unfinished ->
                        ProfileMenuRow(unfinished.pack.manifest.title.ifBlank { "未命名贴纸集" },
                            "${unfinished.pack.manifest.artwork.size} 个表情已添加 · 继续制作", Icons.Outlined.EditNote,
                            { if(!busy) draftId = unfinished.id }, trailing = {
                                AppIconButton(onClick = { discarding = unfinished.id }, enabled = !busy) {
                                    Icon(Icons.Outlined.DeleteOutline, "放弃${unfinished.pack.manifest.title}草稿")
                                }
                            })
                    }
                }
            }, onPreview = { previewId = it.id })
    }
}

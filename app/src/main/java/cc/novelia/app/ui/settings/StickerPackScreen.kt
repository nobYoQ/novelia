package cc.novelia.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
            error = null
            c.message("贴纸集已保存并启用")
        })
        return
    }
    BackHandler(busy) { }
    ProfileDetailScreen("贴纸集", { if(!busy) c.back() }) { padding ->
        StickerPackPicker(state.stickerPackId, { id -> c.store.update { it.copy(stickerPackId = id) } },
            Modifier.padding(padding).fillMaxSize(), packs, enabled = !busy, showTitle = false,
            header = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(onClick = { operation { draftId = store.beginDraft().id } }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("sticker-pack-create")) { Text("制作贴纸集") }
                    AppOutlinedButton(onClick = importer.launch, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("sticker-pack-import")) { Text("导入贴纸包 ZIP") }
                    DocumentPickerFallback(importer, enabled = !busy)
                    Text("从 PNG、WebP、JPG 图片制作，或导入导出的完整贴纸包。自定义贴纸保存在本机，可单独导出迁移。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if(busy) AppLinearProgressIndicator(Modifier.fillMaxWidth())
                    (error ?: library.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    library.drafts.forEach { unfinished ->
                        Text("草稿：${unfinished.pack.manifest.title.ifBlank { "未命名贴纸集" }}", style = MaterialTheme.typography.titleSmall)
                        Row {
                            AppTextButton(onClick = { draftId = unfinished.id }, enabled = !busy) { Text("继续制作") }
                            AppTextButton(onClick = { discarding = unfinished.id }, enabled = !busy) { Text("放弃草稿") }
                        }
                    }
                }
            }, actions = { pack ->
                if(pack.custom) Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
                    AppTextButton(onClick = { operation { draftId = store.beginDraft(pack.id).id } }, enabled = !busy) { Text("编辑") }
                    AppTextButton(onClick = { exportId = pack.id; exporter.launch("novelia-stickers.zip") }, enabled = !busy) { Text("导出") }
                    AppTextButton(onClick = { deleting = pack.id }, enabled = !busy) { Text("删除") }
                }
            })
    }
    deleting?.let { id -> ConfirmDialog("删除贴纸集？", "将删除本机的这套图片。正在使用时会切回默认集，建议先导出留存。",
        { deleting = null }, "删除") { operation {
            store.remove(id)
            if(c.store.state.value.stickerPackId == id) c.store.update { it.copy(stickerPackId = DEFAULT_STICKER_PACK_ID) }
        } } }
    discarding?.let { id -> ConfirmDialog("放弃草稿？", "将删除这份草稿，已保存的贴纸集不受影响。",
        { discarding = null }, "放弃") { operation { store.discardDraft(id) } } }
}

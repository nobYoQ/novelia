@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cc.novelia.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ThemeBarGlass
import cc.novelia.app.data.model.ThemePreset
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.account.ProfileToggle
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.components.documents.*
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.reader.ReaderColorPicker
import cc.novelia.app.ui.theme.LocalAppDark
import cc.novelia.app.ui.theme.ThemeBar
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

private enum class EditorTab(val label: String) { Colors("配色"), Background("背景"), Bars("顶栏与底栏") }
private enum class ColorField(val label: String) { Accent("主题色"), Light("浅色模式底色"), Dark("深色模式底色") }
private val presetSaver = Saver<ThemePreset, String>(
    save = { appJson.encodeToString(it) }, restore = { appJson.decodeFromString(it) },
)

@Composable internal fun ThemeEditor(c: AppController, initial: ThemePreset, isNew: Boolean, close: () -> Unit) {
    val files = c.store.customThemes
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val session = rememberSaveable { UUID.randomUUID().toString() }
    var draft by rememberSaveable(stateSaver = presetSaver) { mutableStateOf(initial) }
    var useImage by rememberSaveable { mutableStateOf(initial.image != null) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val appDark = LocalAppDark.current
    var dark by rememberSaveable { mutableStateOf(appDark) }
    var tab by rememberSaveable { mutableStateOf(EditorTab.Colors) }
    var edge by rememberSaveable { mutableStateOf(ThemeBar.Top) }
    var colorField by rememberSaveable { mutableStateOf<ColorField?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun operation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.friendlyMessage() }
            finally { busy = false }
        }
    }
    val picker = rememberOpenDocumentPicker(DocumentPickerTypes.Images) { uri ->
        if (uri != null) operation {
            val hash = files.pickImage(session) { context.contentResolver.openInputStream(uri) ?: error("无法读取背景图片") }
            picked = hash
            draft = draft.copy(image = hash)
        }
    }
    val preview = if (useImage) draft else draft.copy(image = null)
    val imageFile = preview.image?.let { if (it == picked) files.draftFile(session, it) else files.imageFile(it) }
    fun discard() {
        c.action { files.discardDraft(session) }
        close()
    }
    fun back() {
        if (!busy) {
            if (preview != initial || useImage != (initial.image != null)) confirmDiscard = true else discard()
        }
    }
    fun changeTab(next: EditorTab) {
        focus.clearFocus()
        tab = next
    }
    fun save() {
        when {
            draft.name.isBlank() -> { changeTab(EditorTab.Colors); error = "请填写主题名称" }
            useImage && draft.image == null -> { changeTab(EditorTab.Background); error = "请选择一张背景图片" }
            else -> operation {
                files.save(preview.copy(name = preview.name.trim()), session)
                files.discardDraft(session)
                close()
            }
        }
    }
    BackHandler { back() }
    val backdropField = if (dark) ColorField.Dark else ColorField.Light
    val backdropColor = if (dark) draft.darkBackground else draft.lightBackground
    fun chooseBackgroundColor(value: Long) {
        draft = if (dark) draft.copy(darkBackground = value) else draft.copy(lightBackground = value)
    }
    fun updateBar(value: ThemeBarGlass) {
        draft = if (edge == ThemeBar.Top) draft.copy(topBar = value) else draft.copy(bottomBar = value)
    }

    val previewPanel: @Composable () -> Unit = {
        ThemeEditorPreview(preview, dark, imageFile, onDarkChange = { dark = it }, onBarClick = {
            edge = it
            changeTab(EditorTab.Bars)
        })
    }
    val fields: LazyListScope.() -> Unit = {
        when (tab) {
            EditorTab.Colors -> {
                item(key = "theme-name") { AppTextField(draft.name, { if (it.length <= ThemePreset.MAX_NAME) { draft = draft.copy(name = it); error = null } },
                    modifier = Modifier.fillMaxWidth(), label = { Text("主题名称") }, singleLine = true, enabled = !busy) }
                item { ThemeColorControl("主题色", draft.accent, accentSwatches, onPick = { colorField = ColorField.Accent },
                    onSwatch = { draft = draft.copy(accent = it) }) }
            }
            EditorTab.Background -> {
                item { ThemeChoice(listOf("纯色背景", "图片背景"), if (useImage) 1 else 0) {
                    useImage = it == 1
                    error = null
                    if (useImage && draft.image == null && !busy) picker.launch()
                } }
                if (useImage) {
                    item {
                        AppOutlinedButton(picker.launch, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Image, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (draft.image == null) "选择背景图片" else "更换背景图片")
                        }
                        if (draft.image == null) Text("JPG、PNG 或 WebP，最大 16 MB", style = MaterialTheme.typography.bodySmall)
                        DocumentPickerFallback(picker, enabled = !busy)
                    }
                    if (draft.image != null) {
                        item { ThemeSlider("图片模糊", draft.blur) { draft = draft.copy(blur = it) } }
                        item { ThemeSlider("底色遮罩", draft.overlay) { draft = draft.copy(overlay = it) } }
                        item { ThemeSlider("卡片不透明度", draft.cardOpacity, 50) { draft = draft.copy(cardOpacity = it) } }
                    }
                }
                item { ThemeColorControl(backdropField.label, backdropColor, if (dark) darkSwatches else lightSwatches,
                    onPick = { colorField = backdropField }, onSwatch = ::chooseBackgroundColor) }
            }
            EditorTab.Bars -> {
                val isTop = edge == ThemeBar.Top
                val label = if (isTop) "顶栏" else "底栏"
                val glass = if (isTop) draft.topBar else draft.bottomBar
                item { ThemeChoice(listOf("顶栏", "底栏"), if (isTop) 0 else 1) { edge = if (it == 0) ThemeBar.Top else ThemeBar.Bottom } }
                item { ProfileToggle("${label}毛玻璃", if (isTop) "标题栏和分页标签" else "底部导航和宽屏侧栏",
                    glass.enabled) { updateBar(glass.copy(enabled = it)) } }
                if (glass.enabled) {
                    if (preview.image != null) {
                        item { ThemeSlider("${label}模糊", glass.blur) { updateBar(glass.copy(blur = it)) } }
                    } else {
                        item { AppOutlinedButton(onClick = {
                            changeTab(EditorTab.Background)
                            useImage = true
                            if (draft.image == null) picker.launch()
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("使用图片背景预览模糊") } }
                    }
                    item { ThemeSlider("${label}不透明度", glass.opacity) { updateBar(glass.copy(opacity = it)) } }
                }
            }
        }
    }

    ProfileDetailScreen(if (isNew) "新建主题" else "编辑主题", ::back, actions = {
        AppButton(::save, enabled = !busy, modifier = Modifier.padding(end = 8.dp)) { Text(if (busy) "处理中…" else "保存并使用") }
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface).imePadding()) {
            val fontScale = LocalDensity.current.fontScale
            val wide = maxWidth / fontScale >= 620.dp && maxHeight / fontScale >= 260.dp
            val stackedPreview = !wide && !WindowInsets.isImeVisible
            val pin = maxHeight / fontScale >= 450.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    Box(Modifier.weight(.46f).padding(horizontal = 16.dp, vertical = 8.dp)) { previewPanel() }
                    VerticalDivider()
                }
                // 键盘改变可用高度时，表单和正在输入的文本框仍留在同一组合位置。
                Column(Modifier.weight(if (wide) .54f else 1f).fillMaxHeight()) {
                    if (stackedPreview && pin) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { previewPanel() }
                    }
                    PrimaryTabRow(tab.ordinal) {
                        EditorTab.entries.forEach { entry ->
                            Tab(entry == tab, onClick = { changeTab(entry) }, text = { Text(entry.label) })
                        }
                    }
                    error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    key(tab) {
                        ProfileDetailList(Modifier.weight(1f).testTag("theme-editor-controls"),
                            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (stackedPreview && !pin) item(key = "theme-preview") { previewPanel() }
                            fields()
                        }
                    }
                }
            }
        }
    }
    colorField?.let { field ->
        val color = when (field) { ColorField.Accent -> draft.accent; ColorField.Light -> draft.lightBackground; ColorField.Dark -> draft.darkBackground }
        ReaderColorPicker(field.label, color, onDismiss = { colorField = null }, onApply = {
            draft = when (field) {
                ColorField.Accent -> draft.copy(accent = it)
                ColorField.Light -> draft.copy(lightBackground = it)
                ColorField.Dark -> draft.copy(darkBackground = it)
            }
            colorField = null
        })
    }
    if (confirmDiscard) ConfirmDialog("放弃本次修改？", "当前主题还有未保存的修改。", { confirmDiscard = false }, "放弃修改", ::discard)
}

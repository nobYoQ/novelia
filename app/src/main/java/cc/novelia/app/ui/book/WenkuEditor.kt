@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.book

import cc.novelia.app.ui.components.base.AppTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.model.WenkuOutline
import cc.novelia.app.data.model.WenkuVolume
import cc.novelia.app.data.network.ApiException
import cc.novelia.app.data.network.similarWenku
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AsyncContent
import cc.novelia.app.ui.components.base.ChoiceRow
import cc.novelia.app.ui.components.base.EmptyState
import cc.novelia.app.ui.components.base.Screen
import cc.novelia.app.ui.markdown.rememberDraftPersistence
import cc.novelia.app.ui.navigation.AppController
import kotlinx.serialization.encodeToString

@Composable fun WenkuEditorScreen(c: AppController, id: String? = null) {
    val profile by c.session.profile.collectAsStateWithLifecycle()
    if(profile?.canEdit != true) { Screen("文库条目维护", c::back) { padding -> Box(Modifier.padding(padding)) { EmptyState("需要编辑权限", "登录满足原站角色与注册时间条件的账号后可维护文库条目。", Icons.Outlined.Lock, "查看账号", { c.go("profile") }) } }; return }
    if(id == null) WenkuForm(c, null, WenkuDetail())
    else AsyncContent(id, { c.api.get<WenkuDetail>("wenku/$id") }) { detail, _ -> WenkuForm(c, id, detail) }
}
@Composable internal fun WenkuForm(c: AppController, id: String?, original: WenkuDetail) {
    val draftKey = "wenku:${id ?: "new"}"
    val draft = remember(id) { c.store.state.value.drafts[draftKey]?.let { runCatching { appJson.decodeFromString<WenkuDetail>(it) }.getOrNull() } ?: original }
    var title by rememberSaveable(id) { mutableStateOf(draft.title) }
    var titleZh by rememberSaveable(id) { mutableStateOf(draft.titleZh) }
    var authors by rememberSaveable(id) { mutableStateOf(draft.authors.joinToString("\n")) }
    var artists by rememberSaveable(id) { mutableStateOf(draft.artists.joinToString("\n")) }
    var intro by rememberSaveable(id) { mutableStateOf(draft.introduction) }
    var cover by rememberSaveable(id) { mutableStateOf(draft.cover.orEmpty()) }
    var tags by rememberSaveable(id) { mutableStateOf(draft.keywords.joinToString("\n")) }
    var level by rememberSaveable(id) { mutableStateOf(draft.level) }
    var volumes by remember(id) { mutableStateOf(draft.volumes) }
    var volumeEditor by rememberSaveable(id) { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val binding = c.session.capture()
    var duplicate by remember(title, binding) { mutableStateOf<List<WenkuOutline>?>(null) }
    val focusManager = LocalFocusManager.current
    fun values() = WenkuDetail(title = title.trim(), titleZh = titleZh.trim(), cover = cover.trim().ifBlank { null }, authors = authors.lines().map(String::trim).filter(String::isNotBlank), artists = artists.lines().map(String::trim).filter(String::isNotBlank), keywords = tags.lines().map(String::trim).filter(String::isNotBlank), level = level, introduction = intro.trim(), volumes = volumes)
    val draftPersistence = rememberDraftPersistence(c.store, draftKey) { appJson.encodeToString(values()) }
    LaunchedEffect(title, titleZh, authors, artists, intro, cover, tags, level, volumes) { kotlinx.coroutines.delay(700); draftPersistence.save() }
    val canReadAdult = profile?.canReadAdult == true
    val saveError = values().formError(canReadAdult)
    fun submit(confirmedTitle: String? = null) {
        if (saving) return
        c.action {
            saving = true
            try {
                val submitted = values()
                c.session.ensureCurrent(binding)
                submitted.formError(canReadAdult)?.let { throw IllegalArgumentException(it) }
                if (id == null && confirmedTitle != submitted.title) {
                    val found = c.api.similarWenku(submitted.title, canReadAdult, binding)
                    if (title.trim() != submitted.title) return@action
                    if (found.isNotEmpty()) {
                        duplicate = found
                        return@action
                    }
                }
                val body = submitted.editablePayload().toString()
                val bookId = if (id == null) {
                    c.api.request("POST", "wenku", body, binding = binding).trim().trim('"')
                } else {
                    val latest = c.api.get<WenkuDetail>("wenku/$id", binding = binding)
                    if (latest.editablePayload() != original.editablePayload()) {
                        throw ApiException(409, "条目已被更新，请重新进入后核对再保存")
                    }
                    c.api.request("PUT", "wenku/$id", body, binding = binding)
                    id
                }
                draftPersistence.submittedSuccessfully(appJson.encodeToString(submitted))
                c.back()
                c.book(BookRef("wenku", bookId))
                c.message("文库条目已保存")
            } finally {
                saving = false
            }
        }
    }
    val coverError = wenkuCoverError(cover.trim())
    WenkuEditorPage(
        title = if(id == null) "新建文库条目" else "编辑文库条目",
        onBack = c::back,
        saving = saving,
        canSave = saveError == null,
        saveHint = saveError ?: "保存会更新原站资料，请确认信息准确",
        onSave = { focusManager.clearFocus(); submit() },
    ) {
        WenkuEditorSection("基本信息", Icons.AutoMirrored.Outlined.MenuBook) {
            WenkuEditorField(title, { title = it }, "原文标题", Modifier.testTag("wenku-title"))
            WenkuEditorField(titleZh, { titleZh = it }, "中文标题", Modifier.testTag("wenku-title-zh"))
            WenkuEditorFieldPair(
                first = { WenkuEditorField(authors, { authors = it }, "作者", hint = "每行一位") },
                second = { WenkuEditorField(artists, { artists = it }, "插画师", hint = "选填，每行一位") },
            )
            WenkuEditorField(cover, { cover = it }, "封面链接", Modifier.testTag("wenku-cover"),
                keyboardType = KeyboardType.Uri, isError = cover.isNotBlank() && coverError != null,
                hint = "必填，使用 HTTPS 图片链接")
        }
        WenkuEditorSection("分类与简介", Icons.AutoMirrored.Outlined.Subject) {
            val levels = listOf("一般向", "轻文学", "严肃向", "非小说", "成人向", "成人向女")
            ChoiceRow("作品分类", levels, levels.indexOf(level)) { level = levels[it] }
            WenkuEditorField(tags, { tags = it }, "标签", hint = "选填，每行一个")
            WenkuEditorField(intro, { intro = it }, "作品简介", minLines = 5)
        }
        WenkuEditorVolumes(volumes, onAdd = { volumeEditor = -1 }, onEdit = { volumeEditor = it },
            onRemove = { index -> volumes = volumes.filterIndexed { i, _ -> i != index } })
    }
    volumeEditor?.let { index ->
        key(index) {
            WenkuVolumeEditorDialog(volumes.getOrNull(index) ?: WenkuVolume(), onDismiss = { volumeEditor = null }) { volume ->
                volumes = if(index < 0) volumes + volume else volumes.toMutableList().apply { set(index, volume) }
                volumeEditor = null
            }
        }
    }
    duplicate?.let { matches ->
        AppAlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("发现可能重复的条目") },
            text = {
                AppScrollColumn(contentModifier = Modifier) {
                    Text("请先确认以下作品不是你要创建的文库。")
                    matches.forEach { book ->
                        AppTextButton(onClick = { duplicate = null; c.book(BookRef("wenku", book.id)) }) {
                            Text(book.titleZh.ifBlank { book.title })
                        }
                    }
                }
            },
            confirmButton = {
                AppTextButton(onClick = { duplicate = null; submit(confirmedTitle = title.trim()) }) {
                    Text("确认是新作品，继续创建")
                }
            },
            dismissButton = { AppTextButton(onClick = { duplicate = null }) { Text("返回检查") } }
        )
    }
}

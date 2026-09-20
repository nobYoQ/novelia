@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.Article
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.markdown.MarkdownEditor
import cc.novelia.app.ui.markdown.MarkdownText
import cc.novelia.app.ui.markdown.rememberDraftPersistence
import cc.novelia.app.ui.markdown.rememberMarkdownRenderer
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.MotionContent
import kotlinx.serialization.encodeToString

@Composable fun ComposeArticleScreen(c: AppController, articleId: String?) {
    if(articleId.isNullOrBlank()) ArticleEditor(c, null) else AsyncContent(articleId, { c.api.get<Article>("article/$articleId") }) { article, _ -> ArticleEditor(c, article.copy(id = articleId)) }
}
@Composable internal fun ArticleEditor(c: AppController, article: Article?) {
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val key = "article:${article?.id ?: "new"}"
    val draft = remember(key) { c.store.state.value.drafts[key] }
    val saved = remember(key) { draft?.let { runCatching { appJson.decodeFromString<Map<String, String>>(it) }.getOrNull() } }
    var title by rememberSaveable(key) { mutableStateOf(saved?.get("title") ?: article?.title.orEmpty()) }; var content by rememberSaveable(key) { mutableStateOf(saved?.get("content") ?: article?.content.orEmpty()) }; var category by rememberSaveable(key) { mutableStateOf(saved?.get("category") ?: article?.category ?: "General") }; var preview by rememberSaveable(key) { mutableStateOf(false) }; var sending by remember { mutableStateOf(false) }
    val editorState = rememberSaveableStateHolder()

    val renderer = rememberMarkdownRenderer(c)
    val focusManager = LocalFocusManager.current
    fun draftSnapshot() = appJson.encodeToString(mapOf("title" to title, "content" to content, "category" to category))
    val draftPersistence = rememberDraftPersistence(c.store, key, ::draftSnapshot)
    LaunchedEffect(title, content, category) { kotlinx.coroutines.delay(700); draftPersistence.save() }
    Screen(if(article == null) "写一篇帖子" else "编辑帖子", c::back, actions = { TextButton(onClick = { focusManager.clearFocus(); preview = !preview }) { Text(if(preview) "编辑" else "预览") } }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
        // Bound the inner text scroll area to the space left above the keyboard, including landscape.
        val editorHeight = maxHeight.coerceIn(1.dp, 420.dp)
        MotionContent(preview, Modifier.fillMaxSize(), animateInitial = false) {
            editorState.SaveableStateProvider(preview) {
                AppScrollColumn(contentModifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if(profile != null && profile?.canPost != true) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text("当前账号暂不具备社区发布权限。你仍可编辑和预览，草稿会保存在此设备，获得权限后可以继续发布。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    if(preview) { Text(title, style = MaterialTheme.typography.headlineMedium); MarkdownText(c, content, renderer = renderer, documentUrl = article?.id?.let { "https://n.novelia.cc/forum/$it" }) }
                    else {
                        OutlinedTextField(title, { if(it.length <= 80) title = it }, label = { Text("标题") }, supportingText = { Text("${title.length} / 80") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        ChoiceRow("分类", categories.values.toList(), categories.keys.indexOf(category)) { category = categories.keys.elementAt(it) }
                        MarkdownEditor(content, { content = it }, editorHeight)
                    }
                    Text("草稿自动保存在此设备。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { draftPersistence.save(); c.requireLogin { c.action {
                        check(c.session.profile.value?.canPost == true) { "当前账号暂不具备社区发布权限，草稿已保留" }
                        sending = true
                        try { val submittedDraft = draftSnapshot(); val body = mapOf("title" to title.trim(), "content" to content.trim(), "category" to category); val result = if(article == null) c.api.post("article", body) else c.api.put("article/${article.id}", body); draftPersistence.submittedSuccessfully(submittedDraft); c.back(); c.go("article/${article?.id ?: result.trim().trim('"')}", replaceTop = article != null && c.nav.currentDestination?.route == "article/{id}" && c.nav.currentBackStackEntry?.arguments?.getString("id") == article.id) } finally { sending = false }
                    } } }, enabled = !sending && (profile == null || profile?.canPost == true) && title.trim().length in 2..80 && content.trim().length in 2..20000, modifier = Modifier.fillMaxWidth()) { Text(if(sending) "正在提交…" else if(profile != null && profile?.canPost != true) "暂不可发布 · 草稿已保留" else if(article == null) "发布到社区" else "保存修改") }
                }
            }
        }
        }
    }
}

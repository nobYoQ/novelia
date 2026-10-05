@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.community

import cc.novelia.app.data.catalog.ForumLinks
import cc.novelia.app.data.model.ForumCategory
import cc.novelia.app.data.model.ForumPostInput
import cc.novelia.app.ui.components.EmptyState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
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

@Composable fun ComposeArticleScreen(c: AppController, articleId: String?, draftKey: String? = null) {
    val requestedKey = draftKey?.takeIf(ArticleDrafts::isNewPostKey)
    val forum = if(articleId.isNullOrBlank()) requestedKey == null || ArticleDrafts.isForumNewPostKey(requestedKey)
        else ForumLinks.postId(articleId) != null
    val newPostKey = rememberSaveable(articleId, requestedKey) { requestedKey ?: ArticleDrafts.newKey(forum) }
    if(forum) {
        AsyncContent(listOf("forum-editor", articleId), load = { c.forumApi.categories() to articleId?.let { c.article(it) } }) { (available, article), _ ->
            if(available.isEmpty()) EmptyState("论坛还没有分类", "暂时不能发布帖子。", Icons.Outlined.Forum)
            else ArticleEditor(c, article, available, forum = true, newPostKey = newPostKey)
        }
    } else if(articleId.isNullOrBlank()) ArticleEditor(c, null, newPostKey = newPostKey)
    else AsyncContent(articleId, { c.article(articleId) }) { article, _ -> ArticleEditor(c, article.copy(id = articleId)) }
}
@Composable internal fun ArticleEditor(c: AppController, article: Article?, forumCategories: List<ForumCategory> = emptyList(), forum: Boolean = false, newPostKey: String? = null) {
    val profile by (if(forum) c.forumSession else c.session).profile.collectAsStateWithLifecycle()
    val canPublish = forum || profile == null || profile?.canPost == true
    val generatedKey = rememberSaveable(forum) { ArticleDrafts.newKey(forum) }
    val key = article?.id?.let { "article:$it" } ?: newPostKey ?: generatedKey
    val draft = remember(key) { c.store.state.value.drafts[key] }
    val saved = remember(key) { draft?.let { ArticleDrafts.read(key, it) } }
    var title by rememberSaveable(key) { mutableStateOf(saved?.title ?: article?.title.orEmpty()) }; var content by rememberSaveable(key) { mutableStateOf(saved?.content ?: article?.content.orEmpty()) }; var category by rememberSaveable(key) { mutableStateOf(saved?.category ?: article?.category ?: "General") }; var preview by rememberSaveable(key) { mutableStateOf(false) }; var sending by remember { mutableStateOf(false) }
    val persistenceError by c.store.persistenceError.collectAsStateWithLifecycle()
    val editorState = rememberSaveableStateHolder()
    var categoryId by rememberSaveable(key) { mutableStateOf(saved?.categoryId ?: article?.forumCategoryId ?: forumCategories.firstOrNull()?.id) }
    var tagIds by rememberSaveable(key) { mutableStateOf(saved?.tagIds ?: article?.forumTags?.map { it.id }.orEmpty()) }
    val titleLimit = if(forum) 500 else 80
    val contentLimit = if(forum) 1000000 else 20000

    val renderer = rememberMarkdownRenderer(c, article?.id?.let(ForumLinks::articleUrl) ?: if(forum) ForumLinks.ORIGIN else null)
    val focusManager = LocalFocusManager.current
    fun draftSnapshot() = appJson.encodeToString(mapOf("title" to title, "content" to content, "category" to category, "categoryId" to categoryId.toString(), "tagIds" to tagIds.joinToString(",")))
    val draftPersistence = rememberDraftPersistence(c.store, key, ::draftSnapshot)
    LaunchedEffect(title, content, category, categoryId, tagIds) { kotlinx.coroutines.delay(700); draftPersistence.save() }
    Screen(if(article == null) "写一篇帖子" else "编辑帖子", c::back, actions = { TextButton(onClick = { focusManager.clearFocus(); preview = !preview }) { Text(if(preview) "编辑" else "预览") } }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
        // 把编辑区内部滚动高度限制在键盘上方的剩余空间，横屏也如此。
        val editorHeight = maxHeight.coerceIn(1.dp, 420.dp)
        MotionContent(preview, Modifier.fillMaxSize(), animateInitial = false) {
            editorState.SaveableStateProvider(preview) {
                AppScrollColumn(contentModifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if(!canPublish) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text("当前账号暂不具备社区发布权限。你仍可编辑和预览，草稿会保存在此设备，获得权限后可以继续发布。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    if(preview) { Text(title, style = MaterialTheme.typography.headlineMedium); MarkdownText(c, content, renderer = renderer, documentUrl = article?.id?.let(ForumLinks::articleUrl) ?: if(forum) ForumLinks.ORIGIN else null) }
                    else {
                        OutlinedTextField(title, { if(it.length <= titleLimit) title = it }, label = { Text("标题") }, supportingText = { Text("${title.length} / $titleLimit") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        if(forum) {
                            ChoiceRow("分类", forumCategories.map { it.title }, forumCategories.indexOfFirst { it.id == categoryId }) { categoryId = forumCategories[it].id; tagIds = emptyList() }
                            forumCategories.firstOrNull { it.id == categoryId }?.tags?.forEach { tag ->
                                FilterChip(selected = tag.id in tagIds, onClick = { tagIds = if(tag.id in tagIds) tagIds - tag.id else tagIds + tag.id }, label = { Text(tag.name) })
                            }
                        } else ChoiceRow("分类", categories.values.toList(), categories.keys.indexOf(category)) { category = categories.keys.elementAt(it) }
                        MarkdownEditor(content, { content = it }, editorHeight)
                    }
                    Text(persistenceError ?: if(article == null) "草稿自动保存在此设备，可从社区草稿箱继续写作。" else "草稿自动保存在此设备，重新编辑此帖时恢复。", style = MaterialTheme.typography.bodySmall, color = if(persistenceError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { c.action {
                        check(c.store.recoveryIssue.value == null) { "本地资料处于恢复保护状态，暂时无法保存草稿" }
                        draftPersistence.save(); c.store.flush(); c.back()
                    } }, modifier = Modifier.fillMaxWidth(), enabled = !sending) { Text("保存草稿并退出") }
                    Button(onClick = {
                        draftPersistence.save()
                        val submit = { c.action {
                            if(!forum) check(c.session.profile.value?.canPost == true) { "当前账号暂不具备社区发布权限，草稿已保留" }
                            sending = true; try {
                            val submittedDraft = draftSnapshot()
                            val resultId = if(forum) {
                                val input = ForumPostInput(requireNotNull(categoryId), title.trim(), content.trim(), tagIds)
                                val result = if(article == null) c.forumApi.createPost(input) else c.forumApi.updatePost(requireNotNull(ForumLinks.postId(article.id)), input)
                                ForumLinks.localId(result.id)
                            } else {
                                val body = mapOf("title" to title.trim(), "content" to content.trim(), "category" to category)
                                val result = if(article == null) c.api.post("article", body) else c.api.put("article/${article.id}", body)
                                article?.id ?: result.trim().trim('"')
                            }
                            draftPersistence.submittedSuccessfully(submittedDraft); c.back()
                            c.go("article/$resultId", replaceTop = article != null && c.nav.currentDestination?.route == "article/{id}" && c.nav.currentBackStackEntry?.arguments?.getString("id") == article.id)
                        } finally { sending = false } } }
                        if(forum) c.requireForumLogin(submit) else c.requireLogin(submit)
                    }, enabled = !sending && canPublish && title.trim().length in (if(forum) 1 else 2)..titleLimit && content.trim().length in (if(forum) 1 else 2)..contentLimit && (!forum || forumCategories.any { it.id == categoryId }), modifier = Modifier.fillMaxWidth()) { Text(if(sending) "正在提交…" else if(!canPublish) "暂不可发布 · 草稿已保留" else if(article == null) "发布到社区" else "保存修改") }
                }
            }
        }
        }
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.community

import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.data.community.ForumLinks
import cc.novelia.app.data.community.ForumCategory
import cc.novelia.app.data.community.ForumPostInput
import cc.novelia.app.data.community.ForumRules
import cc.novelia.app.ui.components.base.EmptyState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.community.Article
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AsyncContent
import cc.novelia.app.ui.components.base.ChoiceRow
import cc.novelia.app.ui.components.base.Screen
import cc.novelia.app.ui.markdown.MarkdownEditor
import cc.novelia.app.ui.markdown.MarkdownText
import cc.novelia.app.ui.markdown.rememberDraftPersistence
import cc.novelia.app.ui.markdown.rememberMarkdownRenderer
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.ObserveForumLogin
import cc.novelia.app.ui.theme.MotionContent

@Composable fun ComposeArticleScreen(c: AppController, articleId: String?, draftKey: String? = null) {
    val requestedKey = draftKey?.takeIf(ArticleDrafts::isNewPostKey)
    val forum = if(articleId.isNullOrBlank()) requestedKey == null || ArticleDrafts.isForumNewPostKey(requestedKey)
        else ForumLinks.postId(articleId) != null
    val newPostKey = rememberSaveable(articleId, requestedKey) { requestedKey ?: ArticleDrafts.newKey(forum) }
    if(forum) {
        ObserveForumLogin(c.session, c.forumSession)
        val profile by c.forumSession.profile.collectAsStateWithLifecycle()
        AsyncContent(listOf("forum-editor", articleId, c.forumSession.capture(), profile?.role), load = { c.forumApi.categories() to articleId?.let { c.article(it) } }) { (available, article), _ ->
            if(available.isEmpty()) EmptyState("论坛还没有分类", "暂时不能发布帖子。", Icons.Outlined.Forum)
            else ArticleEditor(c, article, available, forum = true, newPostKey = newPostKey)
        }
    } else if(articleId.isNullOrBlank()) ArticleEditor(c, null, newPostKey = newPostKey)
    else AsyncContent(articleId, { c.article(articleId) }) { article, _ -> ArticleEditor(c, article.copy(id = articleId)) }
}
@Composable internal fun ArticleEditor(c: AppController, article: Article?, forumCategories: List<ForumCategory> = emptyList(), forum: Boolean = false, newPostKey: String? = null) {
    val profile by (if(forum) c.forumSession else c.session).profile.collectAsStateWithLifecycle()
    val canPublish = profile == null || if(forum) ForumRules.canWrite(profile) else profile?.canPost == true
    val writableCategories = forumCategories.filter { ForumRules.canSelectCategory(it, profile) }
    val generatedKey = rememberSaveable(forum) { ArticleDrafts.newKey(forum) }
    val key = article?.id?.let { "article:$it" } ?: newPostKey ?: generatedKey
    val draft = remember(key) { c.store.state.value.drafts[key] }
    val originalCategoryId = article?.forumCategoryId ?: writableCategories.firstOrNull { it.slug == "novel" }?.id ?: writableCategories.firstOrNull()?.id
    val originalCategory = forumCategories.firstOrNull { it.id == originalCategoryId }
    val originalTags = article?.forumTags?.map { it.id }.orEmpty().let { ids ->
        if(forum) ids.filter { id -> originalCategory?.tags.orEmpty().any { it.id == id } }.distinct() else ids
    }
    val baseline = remember(article, originalCategoryId, originalCategory?.slug, originalTags) {
        article?.let { ArticleDrafts.snapshot(it.title, it.content, it.category, originalCategoryId, originalCategory?.slug, originalTags) }
    }
    var useSavedDraft by rememberSaveable(key) { mutableStateOf(article == null) }
    var chooseDraft by rememberSaveable(key) { mutableStateOf(article != null && draft != null && draft != baseline) }
    if(chooseDraft) {
        Screen("编辑帖子", c::back) { padding ->
            Text("已加载最新帖子，请选择要继续编辑的内容。", Modifier.padding(padding).padding(20.dp))
        }
        AppAlertDialog(onDismissRequest = c::back, title = { Text("发现本地修改草稿") },
            text = { Text("本地草稿可能早于已发布内容。选择使用最新帖子将删除这份本地草稿；也可以继续编辑草稿。") },
            confirmButton = { AppTextButton(onClick = {
                c.store.update { it.copy(drafts = it.drafts - key) }
                useSavedDraft = false; chooseDraft = false
            }) { Text("使用最新帖子") } },
            dismissButton = { AppTextButton(onClick = { useSavedDraft = true; chooseDraft = false }) { Text("继续本地草稿") } })
        return
    }
    val saved = remember(key, useSavedDraft) { if(useSavedDraft) draft?.let { ArticleDrafts.read(key, it) } else null }
    var title by rememberSaveable(key) { mutableStateOf(saved?.title ?: article?.title.orEmpty()) }; var content by rememberSaveable(key) { mutableStateOf(saved?.content ?: article?.content.orEmpty()) }; var category by rememberSaveable(key) { mutableStateOf(saved?.category ?: article?.category ?: "General") }; var preview by rememberSaveable(key) { mutableStateOf(false) }; var sending by remember { mutableStateOf(false) }
    val persistenceError by c.store.persistenceError.collectAsStateWithLifecycle()
    val editorState = rememberSaveableStateHolder()
    var categoryId by rememberSaveable(key) { mutableStateOf(if(forum && saved != null) saved.forumCategory(forumCategories)?.id
        else originalCategoryId) }
    var tagIds by rememberSaveable(key) {
        val restored = if(forum && saved != null) saved.forumTags(forumCategories) else saved?.tagIds ?: article?.forumTags?.map { it.id }.orEmpty()
        val validIds = forumCategories.firstOrNull { it.id == categoryId }?.tags?.map { it.id }.orEmpty().toSet()
        mutableStateOf(if(forum) restored.filter { it in validIds }.distinct() else restored)
    }
    val selectedForumCategory = forumCategories.firstOrNull { it.id == categoryId }
    val availableTags = selectedForumCategory?.tags.orEmpty()
    LaunchedEffect(forum, categoryId, availableTags) {
        if(forum) tagIds = tagIds.filter { id -> availableTags.any { it.id == id } }.distinct()
    }
    val titleLimit = if(forum) ForumRules.TITLE_LIMIT else 80
    val titleLength = if(forum) ForumRules.length(title.trim()) else title.length
    val forumError = if(forum) ForumRules.postError(ForumPostInput(categoryId ?: 0, title, content, tagIds)) else null
    val categoryAllowed = !forum || writableCategories.any { it.id == categoryId }

    val renderer = rememberMarkdownRenderer(c, article?.id?.let(ForumLinks::articleUrl) ?: if(forum) ForumLinks.ORIGIN else null)
    val focusManager = LocalFocusManager.current
    fun draftSnapshot() = ArticleDrafts.snapshot(title, content, category, categoryId, selectedForumCategory?.slug, tagIds)
    val draftPersistence = rememberDraftPersistence(c.store, key, baseline, ::draftSnapshot)
    LaunchedEffect(title, content, category, categoryId, tagIds) { kotlinx.coroutines.delay(700); draftPersistence.save() }
    Screen(if(article == null) "写一篇帖子" else "编辑帖子", c::back, actions = { AppTextButton(onClick = { focusManager.clearFocus(); preview = !preview }) { Text(if(preview) "编辑" else "预览") } }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
        // 把编辑区内部滚动高度限制在键盘上方的剩余空间，横屏也如此。
        val editorHeight = maxHeight.coerceIn(1.dp, 420.dp)
        MotionContent(preview, Modifier.fillMaxSize(), animateInitial = false) {
            editorState.SaveableStateProvider(preview) {
                AppScrollColumn(contentModifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if(forum) {
                        if(article == null) ForumPublishingNotice { c.openMarkdownLink(it, ForumLinks.ORIGIN) }
                        else ForumRulesReminder(c)
                    }
                    if(!canPublish) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text("当前账号暂不具备社区发布权限。你仍可编辑和预览，草稿会保存在此设备，获得权限后可以继续发布。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    if(preview) {
                        if(forum) selectedForumCategory?.let { Text(it.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                        Text(title, style = MaterialTheme.typography.headlineMedium)
                        if(forum) ForumTagBadges(availableTags.filter { it.id in tagIds })
                        MarkdownText(c, content, renderer = renderer, documentUrl = article?.id?.let(ForumLinks::articleUrl) ?: if(forum) ForumLinks.ORIGIN else null)
                    }
                    else {
                        OutlinedTextField(title, { if(forum || it.length <= titleLimit) title = it }, label = { Text("标题") }, supportingText = { Text("$titleLength / $titleLimit") }, isError = forum && title.isNotEmpty() && ForumRules.titleError(title) != null, modifier = Modifier.fillMaxWidth().testTag("article-title"), singleLine = true)
                        if(forum) {
                            ChoiceRow("分类", writableCategories.map { it.title }, writableCategories.indexOfFirst { it.id == categoryId }) {
                                val nextCategoryId = writableCategories[it].id
                                if(nextCategoryId != categoryId) { categoryId = nextCategoryId; tagIds = emptyList() }
                            }
                            if(!categoryAllowed) Text(if(selectedForumCategory?.slug == "announcements") "站务公告仅管理员可以发帖，请选择其他分类。"
                                else if(saved != null) "草稿分类需要重新确认，请选择可发布的分类。" else "请选择可发布的分类。", color = MaterialTheme.colorScheme.error)
                            ForumTagSelector(availableTags, tagIds, enabled = !sending) { tagIds = it }
                        } else ChoiceRow("分类", categories.values.toList(), categories.keys.indexOf(category)) { category = categories.keys.elementAt(it) }
                        MarkdownEditor(content, { content = it }, editorHeight, unicodeLimit = if(forum) ForumRules.POST_LIMIT else null)
                    }
                    if(forumError != null) Text(forumError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text(persistenceError ?: if(article == null) "草稿自动保存在此设备，可从社区草稿箱继续写作。" else "修改自动保存在此设备，可从帖子草稿箱管理；重新编辑时可选择最新帖子或本地草稿。", style = MaterialTheme.typography.bodySmall, color = if(persistenceError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    AppOutlinedButton(onClick = { c.action {
                        check(c.store.recoveryIssue.value == null) { "本地资料处于恢复保护状态，暂时无法保存草稿" }
                        draftPersistence.save(); c.store.flush(); c.back()
                    } }, modifier = Modifier.fillMaxWidth(), enabled = !sending) { Text("保存草稿并退出") }
                    AppButton(onClick = {
                        draftPersistence.save()
                        val submit = { c.action {
                            if(forum) require(ForumRules.canPublish(selectedForumCategory, c.forumSession.profile.value)) { "当前账号不能在此分类发帖，草稿已保留" }
                            else check(c.session.profile.value?.canPost == true) { "当前账号暂不具备社区发布权限，草稿已保留" }
                            sending = true; try {
                            val submittedDraft = draftSnapshot()
                            val resultId = if(forum) {
                                val input = ForumPostInput(requireNotNull(categoryId), title.trim(), content, tagIds)
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
                    }, enabled = !sending && canPublish && categoryAllowed && (if(forum) forumError == null else title.trim().length in 2..titleLimit && content.trim().length in 2..20000), modifier = Modifier.fillMaxWidth().testTag("article-submit")) { Text(if(sending) "正在提交…" else if(!canPublish) "暂不可发布 · 草稿已保留" else if(article == null) "发布到社区" else "保存修改") }
                }
            }
        }
        }
    }
}

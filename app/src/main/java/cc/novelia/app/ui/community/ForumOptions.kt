package cc.novelia.app.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.community.ForumCategory
import cc.novelia.app.data.community.ForumPost
import cc.novelia.app.data.community.ForumSort
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AppSwitch
import cc.novelia.app.ui.components.base.LocalInAppSheet
import cc.novelia.app.ui.components.base.PanelChoiceRow
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.appRoundedCornerShape
import cc.novelia.app.ui.theme.monochromeBorder

/** 分类 ID 可能变化；按 slug 保存默认页，不可用时沿用服务端分类顺序。 */
internal fun initialForumCategory(categories: List<ForumCategory>, preferred: String?): String =
    categories.firstOrNull { it.slug == preferred }?.slug ?: categories.first().slug

internal fun ForumCategory.availableTagId(preferred: Long?): Long? = preferred?.takeIf { id -> tags.any { it.id == id } }

internal fun initialForumTagIds(categories: List<ForumCategory>, defaults: Map<String, Long>): Map<String, Long> =
    categories.mapNotNull { category -> category.availableTagId(defaults[category.slug])?.let { category.slug to it } }.toMap()

internal fun visibleForumPosts(posts: List<ForumPost>, query: String, personalFeed: Boolean,
    blockedUsers: Set<String>): List<ForumPost> = posts.filter {
    (!personalFeed || it.title.contains(query, true)) && !it.authorBlocked && it.authorUsername !in blockedUsers
}

@Composable internal fun ForumOptions(
    categories: List<ForumCategory>, currentCategory: String, defaultCategory: String?, skipPinned: Boolean,
    defaultSort: ForumSort, defaultTagIds: Map<String, Long>,
    onDefaultCategory: (String?) -> Unit, onSkipPinned: (Boolean) -> Unit, onDefaultSort: (ForumSort) -> Unit,
    onDefaultTag: (String, Long?) -> Unit, onClose: () -> Unit,
) {
    val sorts = ForumSort.visibleEntries
    Column(Modifier.fillMaxWidth().testTag("forum-options")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("论坛选项", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if(!LocalInAppSheet.current || !appReducedMotion()) {
                AppIconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭论坛选项") }
            }
        }
        AppScrollColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
            contentModifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ForumOptionGroup {
                PanelChoiceRow("默认打开分类", listOf("默认顺序") + categories.map { it.title },
                    categories.indexOfFirst { it.slug == defaultCategory } + 1) { index ->
                    onDefaultCategory(categories.getOrNull(index - 1)?.slug)
                }
                PanelChoiceRow("默认帖子排序", sorts.map { it.label }, sorts.indexOf(defaultSort.visibleSort)) { index ->
                    onDefaultSort(sorts[index])
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().toggleable(skipPinned, role = Role.Switch, onValueChange = onSkipPinned)
                    .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("从首个非置顶帖开始", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    AppSwitch(skipPinned, onCheckedChange = null)
                }
            }
            if(categories.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("各分类的默认标签", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleSmall)
                ForumOptionGroup {
                    categories.sortedBy { it.slug != currentCategory }.forEach { category ->
                        key(category.slug) {
                            val tags = category.tags.sortedBy { it.sortOrder }.distinctBy { it.id }
                            val saved = defaultTagIds[category.slug]
                            // 保留清除已失效标签的入口，即使该分类已没有标签。
                            val canChoose = tags.isNotEmpty() || saved != null
                            PanelChoiceRow(category.title, if(canChoose) listOf("全部标签") + tags.map { it.name } else listOf("无标签"),
                                tags.indexOfFirst { it.id == saved } + 1, Modifier.testTag("forum-default-tag-${category.slug}"),
                                enabled = canChoose) { index -> onDefaultTag(category.slug, tags.getOrNull(index - 1)?.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ForumOptionGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = appRoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface, border = monochromeBorder()) {
        Column(content = content)
    }
}

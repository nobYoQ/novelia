package cc.novelia.app.ui.community

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.community.ForumCategory
import cc.novelia.app.data.community.ForumPost
import cc.novelia.app.data.community.ForumSort
import cc.novelia.app.ui.components.base.AppDropdownMenu
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AppSwitch
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.PanelRadioGrid
import cc.novelia.app.ui.theme.appRoundedCornerShape

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
    val colors = MaterialTheme.colorScheme
    val selectedCategory = defaultCategory?.takeIf { slug -> categories.any { it.slug == slug } }
    Column(Modifier.fillMaxWidth().testTag("forum-options")) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("论坛选项", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("设置自动保存", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            AppIconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭论坛选项") }
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
        AppScrollColumn(modifier = Modifier.fillMaxWidth(),
            contentModifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            ForumOptionSection("默认打开分类", "论坛页重新加载时生效；分类不可用时打开第一个分类。") {
                PanelRadioGrid(listOf(null to "默认顺序") + categories.map { it.slug to it.title }, selectedCategory, onDefaultCategory)
            }
            ForumOptionSection("默认帖子排序", "重新打开论坛时，以此方式排列全部帖子。") {
                PanelRadioGrid(ForumSort.visibleEntries.map { it to it.label }, defaultSort, onDefaultSort)
            }
            ForumOptionSection("默认标签筛选", "每个分类可分别设置；标签不可用时显示全部帖子。") {
                var editingSlug by rememberSaveable { mutableStateOf(currentCategory) }
                val editing = categories.firstOrNull { it.slug == editingSlug } ?: categories.first()
                var choosing by remember { mutableStateOf(false) }
                Box {
                    AppTextButton(onClick = { choosing = true }) {
                        Text("分类：${editing.title}")
                        Icon(Icons.Outlined.KeyboardArrowDown, "选择要设置标签的分类", Modifier.size(18.dp))
                    }
                    AppDropdownMenu(choosing, { choosing = false }) {
                        categories.forEach { category -> DropdownMenuItem(text = { Text(category.title) },
                            onClick = { editingSlug = category.slug; choosing = false }) }
                    }
                }
                val options = listOf(null to "全部标签") + editing.tags.sortedBy { it.sortOrder }.distinctBy { it.id }.map { it.id to it.name }
                PanelRadioGrid(options, editing.availableTagId(defaultTagIds[editing.slug])) { onDefaultTag(editing.slug, it) }
            }
            Surface(shape = appRoundedCornerShape(20.dp), color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .5f))) {
                Row(Modifier.fillMaxWidth().toggleable(skipPinned, role = Role.Switch, onValueChange = onSkipPinned)
                    .padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.VerticalAlignBottom, null, Modifier.size(20.dp), tint = colors.primary)
                            Text("跳过置顶", style = MaterialTheme.typography.titleSmall)
                        }
                        Text("自动定位首个非置顶帖，向上可查看置顶。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    AppSwitch(skipPinned, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable private fun ForumOptionSection(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

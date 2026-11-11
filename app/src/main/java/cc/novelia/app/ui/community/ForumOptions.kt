package cc.novelia.app.ui.community

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.community.ForumCategory
import cc.novelia.app.data.community.ForumPost
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppRadioButton
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AppSwitch
import cc.novelia.app.ui.theme.appRoundedCornerShape

/** 分类 ID 可能变化；按 slug 保存默认页，不可用时沿用服务端分类顺序。 */
internal fun initialForumCategory(categories: List<ForumCategory>, preferred: String?): String =
    categories.firstOrNull { it.slug == preferred }?.slug ?: categories.first().slug

internal fun visibleForumPosts(posts: List<ForumPost>, query: String, personalFeed: Boolean,
    blockedUsers: Set<String>): List<ForumPost> = posts.filter {
    (!personalFeed || it.title.contains(query, true)) && !it.authorBlocked && it.authorUsername !in blockedUsers
}

@Composable internal fun ForumOptions(
    categories: List<ForumCategory>, defaultCategory: String?, skipPinned: Boolean,
    onDefaultCategory: (String?) -> Unit, onSkipPinned: (Boolean) -> Unit, onClose: () -> Unit,
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("默认打开分类", style = MaterialTheme.typography.titleSmall)
                    Text("论坛页重新加载时生效", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Surface(shape = appRoundedCornerShape(20.dp), color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .5f))) {
                    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup().padding(6.dp)) {
                        val columns = if(maxWidth >= (300 * LocalDensity.current.fontScale).dp) 2 else 1
                        val options: List<Pair<String?, String>> = listOf(null to "默认顺序") + categories.map { it.slug to it.title }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            options.chunked(columns).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { (slug, title) ->
                                        ForumCategoryOption(title, slug == selectedCategory, Modifier.weight(1f)) { onDefaultCategory(slug) }
                                    }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                Text("默认顺序或分类不可用时，打开第一个分类。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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

@Composable private fun ForumCategoryOption(title: String, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clip(appRoundedCornerShape(14.dp))
        .background(if(selected) colors.secondaryContainer else Color.Transparent)
        .selectable(selected, role = Role.RadioButton, onClick = onSelect)
        .heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            fontWeight = if(selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if(selected) colors.onSecondaryContainer else colors.onSurface)
        AppRadioButton(selected, onClick = null)
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.settings

import cc.novelia.app.ui.components.base.AppIconButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.account.ProfileEmptyState
import cc.novelia.app.ui.account.ProfileMenuRow
import cc.novelia.app.ui.account.ProfileSummary
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.account.ProfileSectionTitle
import cc.novelia.app.ui.components.base.TextPrompt
import cc.novelia.app.ui.navigation.AppController

@Composable fun BlockedScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); var add by remember { mutableStateOf(false) }
    var addUser by remember { mutableStateOf(false) }
    var addAuthor by remember { mutableStateOf(false) }
    ProfileDetailScreen("屏蔽管理", c::back, actions = { AppIconButton(onClick = { add = true }) { Icon(Icons.Outlined.Add, "屏蔽标签") } }) { padding -> ProfileDetailList(Modifier.padding(padding)) {
        item { ProfileSummary("本地屏蔽", "只影响此设备的发现列表，不更改原站收藏。", Icons.Outlined.Block) }
        item { ProfileSectionTitle("用户", "添加用户") { addUser = true } }
        items(state.blockedUsers.toList()) { user -> ProfileMenuRow(user, "点击取消屏蔽", Icons.Outlined.PersonOff, { c.store.update { it.copy(blockedUsers = it.blockedUsers - user) } }) }
        item { ProfileSectionTitle("标签", "添加标签") { add = true } }
        items(state.blockedTags.toList()) { tag -> ProfileMenuRow(tag, "点击取消屏蔽", Icons.Outlined.Label, { c.store.update { it.copy(blockedTags = it.blockedTags - tag) } }) }
        item { ProfileSectionTitle("作者", "添加作者") { addAuthor = true } }
        items(state.blockedAuthors.toList()) { author -> ProfileMenuRow(author, "点击取消屏蔽", Icons.Outlined.EditOff, { c.store.update { it.copy(blockedAuthors = it.blockedAuthors - author) } }) }
        item { ProfileSectionTitle("作品") }
        items(state.blockedBooks.toList()) { key -> ProfileMenuRow(state.books.find { it.book.ref.key == key }?.book?.title ?: key, "点击取消屏蔽", Icons.Outlined.Book, { c.store.update { it.copy(blockedBooks = it.blockedBooks - key) } }) }
        if(state.blockedTags.isEmpty() && state.blockedBooks.isEmpty() && state.blockedAuthors.isEmpty() && state.blockedUsers.isEmpty()) item { ProfileEmptyState("没有屏蔽内容", "可以在作品详情屏蔽小说或作者，也可以添加标签。", Icons.Outlined.FilterAlt) }
    } }
    if(add) TextPrompt("屏蔽标签", "与原站标签完全一致", onDismiss = { add = false }) { tag -> c.store.update { it.copy(blockedTags = it.blockedTags + tag) } }
    if(addUser) TextPrompt("屏蔽用户", "输入原站用户名", onDismiss = { addUser = false }) { user -> c.store.update { it.copy(blockedUsers = it.blockedUsers + user) } }
    if(addAuthor) TextPrompt("屏蔽作者", "输入与原站一致的作者名", onDismiss = { addAuthor = false }) { author -> c.store.update { it.copy(blockedAuthors = it.blockedAuthors + author) } }
}

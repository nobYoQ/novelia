@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.stickers.StickerPackStore
import cc.novelia.app.ui.account.ProfileMenuRow
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.account.ProfileSectionTitle
import cc.novelia.app.ui.feedback.StickerPack
import cc.novelia.app.ui.feedback.StickerPacks
import cc.novelia.app.ui.feedback.asStickerPack

@Composable
internal fun StickerPackPreference(store: StickerPackStore, selectedId: String, onOpen: () -> Unit) {
    val library by store.state.collectAsStateWithLifecycle()
    val packs = remember(library.packs) { StickerPacks.all + library.packs.map { it.asStickerPack() } }
    ProfileMenuRow("贴纸集", "正在使用 · ${StickerPacks.resolve(selectedId, packs).title}", Icons.Outlined.EmojiEmotions, onOpen)
}

@Composable
internal fun StickerPackPicker(
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    packs: List<StickerPack> = StickerPacks.all,
    enabled: Boolean = true,
    showTitle: Boolean = true,
    header: @Composable () -> Unit = {},
    onPreview: ((StickerPack) -> Unit)? = null,
) {
    val selected = packs.firstOrNull { it.id == selectedId } ?: StickerPacks.default
    Column(modifier) {
        if(showTitle) Text("贴纸集", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        ProfileDetailList(Modifier.weight(1f).selectableGroup()) {
            item(key = "management") { header() }
            listOf(false, true).forEach { custom ->
                val group = packs.filter { it.custom == custom }
                if(group.isNotEmpty()) {
                    item(key = "heading-$custom") { ProfileSectionTitle(if(custom) "我的贴纸 · ${group.size}" else "内置贴纸") }
                    items(group, key = { it.id }) { pack ->
                        StickerPackCard(pack, pack.id == selected.id, enabled, { onSelect(pack.id) }, onPreview?.let { { it(pack) } })
                    }
                }
            }
        }
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.account.ProfileMenuRow
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.AppRadioButton
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.feedback.Sticker
import cc.novelia.app.ui.feedback.StickerIllustration
import cc.novelia.app.ui.feedback.StickerPack
import cc.novelia.app.ui.feedback.StickerPacks

@Composable
internal fun StickerPackPreference(selectedId: String, onSelect: (String) -> Unit) {
    var show by rememberSaveable { mutableStateOf(false) }
    ProfileMenuRow("贴纸集", StickerPacks.resolve(selectedId).title, Icons.Outlined.EmojiEmotions, { show = true })
    if(show) AppSheet(onDismissRequest = { show = false }) {
        StickerPackPicker(selectedId, onSelect, Modifier.fillMaxWidth().fillMaxHeight(.7f))
    }
}

@Composable
internal fun StickerPackPicker(
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    packs: List<StickerPack> = StickerPacks.all,
) {
    val selected = packs.firstOrNull { it.id == selectedId } ?: StickerPacks.default
    Column(modifier) {
        Text("贴纸集", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        Text("切换阅读搭子、默认封面与操作提示中的贴纸，立即生效。",
            Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
        AppLazyColumn(Modifier.weight(1f).selectableGroup(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(packs, key = { it.id }) { pack ->
                val active = pack.id == selected.id
                Row(Modifier.fillMaxWidth().testTag("sticker-pack-${pack.id}")
                    .selectable(active, role = Role.RadioButton, onClick = { onSelect(pack.id) })
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StickerIllustration(Sticker.Neutral, Modifier.size(64.dp), pack)
                    Column(Modifier.weight(1f)) {
                        Text(pack.title, style = MaterialTheme.typography.titleMedium)
                        if(active) Text("当前使用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    AppRadioButton(active, onClick = null)
                }
            }
        }
    }
}

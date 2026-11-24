package cc.novelia.app.ui.book

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cc.novelia.app.data.images.CustomBookCover
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.ui.components.book.rememberCustomBookCover
import cc.novelia.app.ui.components.documents.DocumentPickerTypes
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class BookCoverActions(val cover: CustomBookCover?, val busy: Boolean, val choose: () -> Unit, val restore: () -> Unit)

@Composable internal fun rememberBookCoverActions(c: AppController, ref: BookRef, linkedWenkuId: String? = null): BookCoverActions = key(ref) {
    val cover = rememberCustomBookCover(ref)
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null && !busy) c.action("已更换本机封面") {
            busy = true
            try {
                c.store.bookCovers.replace(ref) {
                    requireNotNull(c.app.contentResolver.openInputStream(uri)) { "无法打开所选图片" }
                }
            } finally { busy = false }
        }
    }
    var choosingSource by rememberSaveable { mutableStateOf(false) }
    val chooseLocal = {
        choosingSource = false
        picker.launch(DocumentPickerTypes.Images.toTypedArray())
    }
    if(choosingSource && linkedWenkuId != null) key(linkedWenkuId) {
        PublicationCoverSheet(c, ref, linkedWenkuId, onLocal = chooseLocal, onDismiss = { choosingSource = false })
    }
    BookCoverActions(cover, busy, choose = {
        if(linkedWenkuId == null) chooseLocal() else choosingSource = true
    }, restore = {
        c.action("已恢复默认封面") {
            busy = true
            try { withContext(Dispatchers.IO) { c.store.bookCovers.restoreDefault(ref) } }
            finally { busy = false }
        }
    })
}

@Composable internal fun BookCoverMenuItems(actions: BookCoverActions, onDismiss: () -> Unit) {
    DropdownMenuItem({ Text("更换封面") }, { onDismiss(); actions.choose() },
        enabled = !actions.busy, leadingIcon = { Icon(Icons.Outlined.Image, null) })
    if(actions.cover != null) DropdownMenuItem({ Text("恢复默认封面") }, { onDismiss(); actions.restore() },
        enabled = !actions.busy, leadingIcon = { Icon(Icons.Outlined.Restore, null) })
}

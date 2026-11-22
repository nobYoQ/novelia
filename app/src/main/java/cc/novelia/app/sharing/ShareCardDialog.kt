@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cc.novelia.app.sharing

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.ui.components.base.AppTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun ShareCardDialog(card: ShareCard, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var qr by rememberSaveable(card) { mutableStateOf(card.url != null) }
    var page by rememberSaveable(card) { mutableIntStateOf(0) }
    var artwork by remember(card) { mutableStateOf<CardArtwork?>(null) }
    var bitmap by remember(card, qr, page) { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(card) { mutableStateOf("") }
    LaunchedEffect(card) {
        try { artwork = withContext(Dispatchers.IO) { loadArtwork(context, card) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "无法生成分享卡片" }
    }
    val art = artwork
    LaunchedEffect(art, page, qr) {
        if (art != null) bitmap = withContext(Dispatchers.Default) { art.render(page, qr) }
    }
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "图片操作失败" }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null && art != null) runAction {
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).buffered().use { art.writePng(page, qr, it) }
            }
            message = "图片已保存"
        }
    }
    val canExport = art != null && bitmap != null && !busy
    AppAlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("分享预览", Modifier.weight(1f))
            AppIconButton(onDismiss, enabled = !busy) { Icon(Icons.Outlined.Close, "关闭") }
        }
    }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val preview = bitmap
            if (preview != null) Image(preview.asImageBitmap(), "分享图片预览",
                Modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(.8f))
            else if (message.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (art != null && art.pageCount > 1) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                AppTextButton({ page-- }, enabled = page > 0 && !busy) { Text("上一张") }
                Text("${page + 1} / ${art.pageCount}")
                AppTextButton({ page++ }, enabled = page + 1 < art.pageCount && !busy) { Text("下一张") }
            }
            card.url?.let { url ->
                Row(Modifier.fillMaxWidth().toggleable(qr, enabled = !busy, role = Role.Switch, onValueChange = { qr = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("在图片中显示二维码", Modifier.weight(1f))
                    Switch(qr, null, enabled = !busy)
                }
                AppTextButton({
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Novelia", url))
                    message = "链接已复制"
                }, enabled = !busy) { Text("复制链接") }
            }
        }
    }, confirmButton = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (busy || message.isNotEmpty()) Text(if (busy) "正在处理…" else message,
                Modifier.fillMaxWidth().padding(bottom = 6.dp), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                AppOutlinedButton({
                    val artworkToSave = art ?: return@AppOutlinedButton
                    if (Build.VERSION.SDK_INT >= 29) runAction {
                        withContext(Dispatchers.IO) { saveCardsToAlbum(context, artworkToSave, qr) }
                        message = if (artworkToSave.pageCount == 1) "已保存到相册 Novelia"
                            else "已保存 ${artworkToSave.pageCount} 张图片到相册 Novelia"
                    } else picker.launch("Novelia-${page + 1}.png")
                }, enabled = canExport) {
                    Text(if (Build.VERSION.SDK_INT < 29 && (art?.pageCount ?: 0) > 1) "保存当前图片" else "保存图片")
                }
                AppButton({
                    if (art != null) runAction {
                        val intent = withContext(Dispatchers.IO) { shareImages(context, art, card, qr) }
                        context.startActivity(intent)
                    }
                }, enabled = canExport) { Text("分享图片") }
            }
        }
    })
}

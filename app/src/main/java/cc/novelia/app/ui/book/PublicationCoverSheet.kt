@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.images.PublicationCover
import cc.novelia.app.data.images.publicationCover
import cc.novelia.app.data.images.replaceFromPublication
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.components.media.RecoveringAsyncImage
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.motionClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun PublicationCoverSheet(
    c: AppController, ref: BookRef, wenkuId: String, onLocal: () -> Unit, onDismiss: () -> Unit,
) {
    var browsing by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf<PublicationCover?>(null) }
    var failure by remember { mutableStateOf<Pair<PublicationCover, String>?>(null) }
    val scope = rememberCoroutineScope()
    AppSheet(onDismiss) {
        if(!browsing) {
            SectionTitle("封面来源")
            MenuRow("本地图片", "", Icons.Outlined.Image, onLocal)
            MenuRow("文库版出版封面", "", Icons.AutoMirrored.Outlined.LibraryBooks, { browsing = true })
        } else {
            SectionTitle("选择出版卷封面", "本地图片", onLocal)
            val key = c.detailKey("wenku/$wenkuId")
            CachedAsyncContent(c.uiResults.wenkuDetails, key, load = { c.detail<WenkuDetail>(key.path) },
                validate = { c.session.ensureCurrent(key.binding) }) { detail, _ ->
                val covers = remember(detail.volumes) { detail.volumes.mapIndexedNotNull { index, volume -> volume.publicationCover(index) } }
                if(covers.isEmpty()) {
                    Text("文库版暂无可选的出版封面", Modifier.padding(20.dp))
                } else AppLazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(covers) { cover ->
                        val error = failure?.takeIf { it.first == cover }?.second
                        AppListItem(
                            headlineContent = { Text(cover.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { PublicationThumbnail(cover) },
                            supportingContent = when {
                                saving == cover -> ({ Text("正在保存封面…") })
                                error != null -> ({ Text("$error · 点按重试", color = MaterialTheme.colorScheme.error) })
                                else -> null
                            },
                            modifier = Modifier.motionClickable(enabled = saving == null) {
                                if(saving != null) return@motionClickable
                                saving = cover
                                failure = null
                                scope.launch {
                                    try {
                                        c.store.bookCovers.replaceFromPublication(c.app, ref, cover)
                                        onDismiss()
                                        c.message("已更换本机封面")
                                    } catch(cancelled: CancellationException) {
                                        throw cancelled
                                    } catch(error: Exception) {
                                        failure = cover to error.friendlyMessage()
                                    } finally { saving = null }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable private fun PublicationThumbnail(cover: PublicationCover) {
    var url by remember(cover) { mutableStateOf(cover.urls.last()) }
    var failed by remember(url) { mutableStateOf(false) }
    Box(Modifier.width(64.dp).height(92.dp), contentAlignment = Alignment.Center) {
        RecoveringAsyncImage(url, null, Modifier.fillMaxSize(),
            onSuccess = { failed = false },
            onError = { if(url != cover.urls.first()) url = cover.urls.first() else failed = true })
        if(failed) Icon(Icons.Outlined.Image, "封面预览不可用")
    }
}

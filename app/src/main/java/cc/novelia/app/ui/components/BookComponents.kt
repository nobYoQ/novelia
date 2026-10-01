@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.catalog.KeywordCatalog
import cc.novelia.app.data.catalog.KeywordEntry
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.motionClickable
import coil.compose.AsyncImage
import coil.decode.DataSource

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable fun TagList(tags: List<String>, c: AppController) {
    val entries by c.app.keywords.state.collectAsStateWithLifecycle()
    val persistenceError by c.app.keywords.persistenceError.collectAsStateWithLifecycle()
    val lookup = remember(entries) { entries.entries.associateBy { it.original } }
    var editing by remember { mutableStateOf<KeywordEntry?>(null) }
    AppChipFlowRow(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        tags.distinct().forEach { tag ->
            val entry = lookup[tag] ?: KeywordEntry(tag)
            AppSelectionChip(false, onClick = {
                c.app.keywords.markUsed(listOf(tag))
                val expression = if(KeywordCatalog.canSearch(tag)) "$tag$" else tag
                c.go("discover?query=${android.net.Uri.encode(expression)}")
            }, label = { Text(entry.label) }, trailingIcon = {
                IconButton(onClick = { editing = entry }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Edit, "编辑标签翻译 $tag", Modifier.size(16.dp)) }
            })
        }
    }
    persistenceError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
    editing?.let { KeywordEditorDialog(it, { editing = null }, c.app.keywords::setTranslation,
        categories = entries.categories, onSaveDetails = c.app.keywords::editEntry) }
}

@Composable fun BookCover(book: BookCard, modifier: Modifier = Modifier) {
    val source = book.cover?.takeIf { it.isNotBlank() }
    var loaded by remember(source) { mutableStateOf(false) }
    Box(modifier.width(76.dp).height(104.dp).clip(RoundedCornerShape(12.dp)).semantics {
        contentDescription = "${book.title} ${if(loaded) "封面" else "默认封面"}"
    }, contentAlignment = Alignment.Center) {
        DefaultBookCover(book, Modifier.matchParentSize().clearAndSetSemantics {})
        if(source != null) {
            val reducedMotion = appReducedMotion()
            var memoryCached by remember(source) { mutableStateOf(false) }
            val coverAlpha = remember(source) { Animatable(0f) }
            LaunchedEffect(source, loaded, memoryCached, reducedMotion) {
                if(!loaded) coverAlpha.snapTo(0f)
                else if(reducedMotion || memoryCached) coverAlpha.snapTo(1f)
                else coverAlpha.animateTo(1f, tween(AppMotion.Release))
            }
            AsyncImage(
                source, null,
                Modifier.fillMaxSize().graphicsLayer { alpha = if(reducedMotion || memoryCached) 1f else coverAlpha.value },
                contentScale = ContentScale.Crop,
                onLoading = { loaded = false; memoryCached = false },
                onSuccess = { memoryCached = it.result.dataSource == DataSource.MEMORY_CACHE; loaded = true },
                onError = { loaded = false; memoryCached = false },
            )
        }
    }
}
@Composable internal fun BookRow(book: BookCard, onClick: () -> Unit, modifier: Modifier = Modifier, status: BookRowStatus? = null, showReadingProgress: Boolean = true, showBookMetadata: Boolean = true, trailing: @Composable (() -> Unit)? = null) {
    val presentation = LocalBookListPresentation.current
    val saved = presentation.books[book.ref.key]
    val reading = status ?: bookRowStatus(book, saved, presentation.positions[book.ref.key], presentation.updates[book.ref.key], presentation.account)
    val showProgress = showReadingProgress && !book.ref.isWenku
    val updated = bookUpdateDate(book.updateAt ?: saved?.book?.updateAt?.takeIf { showBookMetadata })
    val subtitle = book.subtitle.ifBlank { providers[book.ref.provider] ?: "本地小说" }
    val fontScale = LocalDensity.current.fontScale
    Row(modifier.fillMaxWidth().motionClickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book)
        BoxWithConstraints(Modifier.weight(1f)) {
            val showDate = showBookMetadata && updated != null
            // 分屏列表远窄于整屏，日期应换行显示而非直接隐藏。
            val inlineDate = showDate && maxWidth >= (320 * fontScale).dp
            val updateLabel = reading.updateLabel.takeIf { showBookMetadata }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    trailing?.invoke()
                }
                if(showDate && !inlineDate) BookUpdateDateLabel(updated!!, book.ref.key)
                if(showProgress || inlineDate || updateLabel != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if(showProgress) Text(reading.progressLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Spacer(Modifier.weight(1f))
                    if(inlineDate) BookUpdateDateLabel(updated!!, book.ref.key)
                    updateLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                }
                if(showProgress) BookReadingProgressBar(reading, Modifier.testTag("book-reading-progress-${book.ref.key}"))
                if(showBookMetadata) BookSyncIndicator(book.ref)
            }
        }
    }
}

@Composable private fun BookUpdateDateLabel(date: String, key: String) {
    Text("更新于 $date", Modifier.testTag("book-update-date-$key"), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun BookReadingProgressBar(reading: BookRowStatus, modifier: Modifier = Modifier) {
    // 服务器仅提供历史标记时，不能推导数值进度或显示为 0%。
    reading.progress?.let { progress ->
        LinearProgressIndicator(progress = { progress }, modifier = modifier.fillMaxWidth().height(3.dp)
            .semantics { contentDescription = reading.progressLabel }, gapSize = 0.dp, drawStopIndicator = {})
    }
}

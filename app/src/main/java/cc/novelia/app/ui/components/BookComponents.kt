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
    val lookup = remember(entries) { entries.associateBy { it.original } }
    var editing by remember { mutableStateOf<KeywordEntry?>(null) }
    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.distinct().forEach { tag ->
            val entry = lookup[tag] ?: KeywordEntry(tag)
            InputChip(false, onClick = {
                c.app.keywords.markUsed(listOf(tag))
                val expression = if(KeywordCatalog.canSearch(tag)) "$tag$" else tag
                c.go("discover?query=${android.net.Uri.encode(expression)}")
            }, label = { Text(entry.label) }, trailingIcon = {
                IconButton(onClick = { editing = entry }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Edit, "编辑标签翻译 $tag", Modifier.size(16.dp)) }
            })
        }
    }
    persistenceError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
    editing?.let { KeywordEditorDialog(it, { editing = null }, c.app.keywords::setTranslation) }
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
@Composable fun BookRow(book: BookCard, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().motionClickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(book.subtitle.ifBlank { providers[book.ref.provider] ?: "本地小说" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if(book.total > 0) Text(if(book.ref.isWenku) "${book.total} 个分卷文件" else "${book.translated} / ${book.total} 章有译文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            else if(book.originalTitle != book.title) Text(book.originalTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BookSyncIndicator(book.ref)
        }
        trailing?.invoke()
    }
}

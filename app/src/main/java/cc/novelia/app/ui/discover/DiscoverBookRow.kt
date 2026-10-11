@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.formatApproximateCharacters
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.ui.components.book.BookCover
import cc.novelia.app.ui.components.book.BookSyncIndicator
import cc.novelia.app.ui.components.book.LocalBookListPresentation
import cc.novelia.app.ui.components.base.FilterPanelExpandIcon
import cc.novelia.app.ui.components.book.bookRowStatus
import cc.novelia.app.ui.components.book.bookUpdateDate
import cc.novelia.app.ui.theme.motionClickable

/** 搜索结果优先展示选书信息，只使用列表和本地已有的元数据。 */
@Composable internal fun DiscoverBookRow(
    book: BookCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    keywordLabels: Map<String, String> = emptyMap(),
    showCharacterCount: Boolean = false,
) {
    val presentation = LocalBookListPresentation.current
    val saved = presentation.books[book.ref.key]
    val authors = book.authors.ifEmpty { saved?.book?.authors.orEmpty() }
    val source = providers[book.ref.provider] ?: if(book.ref.isWenku) "文库小说" else if(book.ref.isLocal) "本地小说" else book.ref.provider
    val byline = (authors.filter(String::isNotBlank).distinct() + source).joinToString(" · ")
    val characters = book.totalCharacters ?: saved?.book?.totalCharacters
    val updated = bookUpdateDate(book.updateAt ?: saved?.book?.updateAt)
    val updateLabel = bookRowStatus(book, saved, presentation.positions[book.ref.key], presentation.updates[book.ref.key], presentation.account).updateLabel
    val tags = remember(book.tags, book.attentions, keywordLabels) {
        (book.attentions.orEmpty() + book.tags).filter(String::isNotBlank)
            .map { keywordLabels[it] ?: it }.distinct()
    }
    val publishedVolumes = book.publishedVolumeCount ?: saved?.book?.publishedVolumeCount
    val metadata = when {
        book.ref.isWenku -> publishedVolumes?.takeIf { it > 0 }?.let { "$it 卷" }.orEmpty()
        !book.novelType.isNullOrBlank() -> "${book.novelType} · ${book.total} 章"
        book.total > 0 -> "${book.total} 章"
        else -> book.subtitle.takeUnless { it == source || it == authors.joinToString() }.orEmpty()
    }
    Row(modifier.fillMaxWidth().motionClickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BookCover(book)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(byline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if(metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if(characters != null || showCharacterCount) Text(characters?.let(::formatApproximateCharacters) ?: "字数未知",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if(updated != null) Text("更新于 $updated", Modifier.testTag("book-update-date-${book.ref.key}"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if(updateLabel != null) Text(updateLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if(tags.isNotEmpty()) {
                var tagsExpanded by rememberSaveable(book.ref.key) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().testTag("discover-tags-${book.ref.key}")
                    .clickable(role = Role.Button, onClickLabel = if(tagsExpanded) "收起标签" else "展开全部 ${tags.size} 个标签") { tagsExpanded = !tagsExpanded }
                    .semantics { stateDescription = if(tagsExpanded) "标签已展开" else "标签已收起" }
                    .padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                    Text(tags.joinToString(" · "), Modifier.weight(1f).testTag("discover-tags-text-${book.ref.key}"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        maxLines = if(tagsExpanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                    FilterPanelExpandIcon(tagsExpanded)
                }
            }
            BookSyncIndicator(book.ref)
        }
    }
}

package cc.novelia.app.ui.discover

import cc.novelia.app.ui.components.base.AppLinearProgressIndicator
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.KeywordEntry
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.AppSelectionChip
import cc.novelia.app.ui.components.base.ChipSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private data class MeasuredKeyword(val entry: KeywordEntry, val width: Int)

/**
 * 按可用宽度、字体缩放与文字方向在后台测量标签，再由惰性列表仅组合可见行。
 * 排布任务不依赖包含/排除选择，点击只改变样式和语义，无需重新测量整份词表。
 * 词条或尺寸变化会取消旧任务，测量器不跨任务共享可变缓存。
 */
@Composable internal fun KeywordTagCloud(
    entries: List<KeywordEntry>, included: List<String>, excluded: List<String>,
    onClick: (KeywordEntry) -> Unit, modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val fonts = LocalFontFamilyResolver.current
    val style = MaterialTheme.typography.labelLarge
    val includedSet = remember(included) { included.toSet() }
    val excludedSet = remember(excluded) { excluded.toSet() }
    val listState = rememberLazyListState()
    BoxWithConstraints(modifier) {
        val available = with(density) { (maxWidth - 40.dp).roundToPx().coerceAtLeast(1) }
        val spacing = with(density) { ChipSpacing.roundToPx() }
        val horizontalPadding = with(density) { 32.dp.roundToPx() }
        val minimumWidth = with(density) { 48.dp.roundToPx() }
        val rows by produceState<List<List<MeasuredKeyword>>?>(null, entries, available, density, direction, fonts, style) {
            value = null
            value = withContext(Dispatchers.Default) {
                // 每轮任务独享测量器，取消或替换的搜索不共享可变测量缓存。
                val measurer = TextMeasurer(fonts, density, direction, cacheSize = 0)
                val packed = mutableListOf<List<MeasuredKeyword>>()
                var row = mutableListOf<MeasuredKeyword>()
                var used = 0
                entries.forEach { entry ->
                    ensureActive()
                    val width = (measurer.measure(entry.label, style, softWrap = false).size.width + horizontalPadding + 1)
                        .coerceAtLeast(minimumWidth).coerceAtMost(available)
                    if(row.isNotEmpty() && used + spacing + width > available) {
                        packed += row; row = mutableListOf(); used = 0
                    }
                    used += (if(row.isEmpty()) 0 else spacing) + width
                    row += MeasuredKeyword(entry, width)
                }
                if(row.isNotEmpty()) packed += row
                packed
            }
        }
        if(rows == null) AppLinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        AppLazyColumn(Modifier.fillMaxSize(), state = listState, listModifier = Modifier.testTag("keyword-library-list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(ChipSpacing)) {
            items(rows.orEmpty(), key = { it.first().entry.original }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChipSpacing), verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { (entry, width) ->
                        key(entry.original) {
                            val isExcluded = entry.original in excludedSet
                            val isIncluded = entry.original in includedSet
                            val scheme = MaterialTheme.colorScheme
                            AppSelectionChip(isIncluded || isExcluded, { onClick(entry) },
                                label = { Text(entry.label, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    textDecoration = if(isExcluded) TextDecoration.LineThrough else null) },
                                modifier = Modifier.width(with(density) { width.toDp() }).testTag("library-tag-${entry.original}").semantics {
                                    stateDescription = when { isExcluded -> "已排除"; isIncluded -> "已包含"; else -> "未选择" }
                                },
                                colors = if(isExcluded) FilterChipDefaults.filterChipColors(selectedContainerColor = scheme.errorContainer,
                                    selectedLabelColor = scheme.onErrorContainer) else null,
                                border = if(isExcluded) BorderStroke(1.5.dp, scheme.error) else null)
                        }
                    }
                }
            }
        }
    }
}

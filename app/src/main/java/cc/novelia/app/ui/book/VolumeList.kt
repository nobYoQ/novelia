package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.shelf.ContinueReadingOverlay

@Composable internal fun VolumeList(
    c: AppController, library: LibraryState, volumes: List<SavedBook>, tag: String,
    modifier: Modifier = Modifier, showContinue: Boolean = true, content: LazyListScope.() -> Unit,
) {
    val list = rememberLazyListState()
    val latest = remember(volumes, library.positions) {
        volumes.mapNotNull { volume -> library.positions[volume.book.ref.key]?.let { volume.book to it } }
            .maxByOrNull { (_, position) -> position.updatedAt }
    }
    val density = LocalDensity.current
    val threshold = with(density) { 48.dp.roundToPx() }
    val expanded by remember(list, threshold) {
        derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < threshold }
    }
    var buttonHeight by remember { mutableIntStateOf(0) }
    var pagingHeight by remember { mutableIntStateOf(0) }
    val floating = showContinue && latest != null
    Box(modifier.fillMaxSize()) {
        AppLazyColumn(Modifier.fillMaxSize(), state = list, listModifier = Modifier.testTag(tag),
            onPageButtonsHeightChanged = { pagingHeight = it },
            contentPadding = PaddingValues(bottom = if(floating) with(density) { buttonHeight.toDp() } + 32.dp else 24.dp),
            content = content)
        ContinueReadingOverlay(latest.takeIf { showContinue }, { book, position -> c.read(book.ref, position.chapterId) },
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = with(density) { pagingHeight.toDp() } + 16.dp)
                .onSizeChanged { buttonHeight = it.height }, expanded = expanded, tagPrefix = "volume-continue")
    }
}

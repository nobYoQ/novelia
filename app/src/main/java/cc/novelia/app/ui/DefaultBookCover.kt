package cc.novelia.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.novelia.app.data.BookCard

private val CoverStickers = listOf(MidoriSticker.Reading, MidoriSticker.Thinking, MidoriSticker.Neutral, MidoriSticker.Welcome)

@Composable
internal fun DefaultBookCover(book: BookCard, modifier: Modifier = Modifier) {
    // Stable per book, including negative hash codes; filtering and refresh never shuffle covers.
    val variant = remember(book.ref.key) { (book.ref.key.hashCode() and Int.MAX_VALUE) % CoverStickers.size }
    val colors = MaterialTheme.colorScheme
    val tint = if(variant % 2 == 0) colors.secondaryContainer else colors.tertiaryContainer
    Box(modifier.background(Brush.verticalGradient(listOf(tint, colors.primaryContainer)))) {
        Box(Modifier.fillMaxHeight().width(3.dp).background(colors.onPrimaryContainer.copy(alpha = .12f)))
        Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 6.dp, top = 7.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MidoriIllustration(CoverStickers[variant], Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(book.title, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                color = colors.onPrimaryContainer)
        }
    }
}

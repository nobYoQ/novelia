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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.novelia.app.data.BookCard
import cc.novelia.app.data.providers

private val CoverStickers = listOf(MidoriSticker.Reading, MidoriSticker.Thinking, MidoriSticker.Neutral, MidoriSticker.Welcome)
private val CoverAccents = listOf(Color(0xFF537B57), Color(0xFF566EA0), Color(0xFF947055), Color(0xFF846697), Color(0xFF4D8085), Color(0xFF9E6876))

@Composable
internal fun DefaultBookCover(book: BookCard, modifier: Modifier = Modifier) {
    // Stable per book, including negative hash codes; filtering and refresh never shuffle covers.
    val variant = remember(book.ref.key) { stableCoverVariant(book.ref.key, CoverAccents.size) }
    val colors = MaterialTheme.colorScheme
    val eInk = LocalEInkMode.current
    val dark = colors.surface.luminance() < .5f
    val accent = if(eInk) colors.onSurface else CoverAccents[variant]
    val tint = if(eInk) colors.surface else lerp(colors.surface, accent, if(dark) .38f else .18f)
    Box(modifier.background(Brush.verticalGradient(listOf(tint, if(eInk) tint else lerp(colors.surface, accent, .08f))))) {
        Box(Modifier.fillMaxHeight().width(3.dp).background(accent.copy(alpha = .65f)))
        Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 6.dp, top = 7.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if(book.ref.isLocal) "本地藏书" else if(book.ref.isWenku) "文库" else providers[book.ref.provider] ?: book.ref.provider,
                fontSize = 7.sp, lineHeight = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            MidoriIllustration(CoverStickers[variant % CoverStickers.size], Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(book.title, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = if(variant % 2 == 0) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                color = colors.onSurface)
        }
    }
}

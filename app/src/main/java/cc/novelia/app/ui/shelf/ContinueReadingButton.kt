package cc.novelia.app.ui.shelf

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.Position
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.DefaultShapes
import cc.novelia.app.ui.theme.MotionContent
import cc.novelia.app.ui.theme.appControlShape
import cc.novelia.app.ui.theme.appReducedMotion

/** 续读目标沿用书架最近的本机位置；完整书名和章节仍保留在无障碍文本中。 */
@Composable internal fun ContinueReadingButton(
    book: BookCard,
    position: Position,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
) {
    val reducedMotion = appReducedMotion()
    val chapter = position.title.ifBlank { position.chapterIndex?.let { "第 ${it + 1} 章" } ?: "已记录阅读位置" }
    MotionContent(book.ref.key, modifier, animateInitial = false) {
        Surface(onClick = onClick, shape = appControlShape(DefaultShapes.medium),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shadowElevation = if(reducedMotion) 0.dp else 1.dp,
            modifier = Modifier.testTag("shelf-continue-reading").semantics { role = Role.Button }) {
            if(reducedMotion) ContinueReadingContent(book, chapter, expanded)
            else AnimatedContent(targetState = expanded, contentAlignment = Alignment.BottomEnd,
                transitionSpec = {
                    (fadeIn(tween(AppMotion.Quick, delayMillis = AppMotion.Exit)) togetherWith
                        fadeOut(tween(AppMotion.Exit))).using(SizeTransform(clip = true) { _, _ -> tween(AppMotion.Panel) })
                }, label = "续读按钮展开收起") { showDetails ->
                // 动画放在背景内部，让背景、点击区域与内容容器的每一帧尺寸一致。
                ContinueReadingContent(book, chapter, showDetails)
            }
        }
    }
}

@Composable private fun ContinueReadingContent(book: BookCard, chapter: String, expanded: Boolean) {
    if(expanded) Column(Modifier.width(184.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("继续", Modifier.testTag("shelf-continue-label"), style = MaterialTheme.typography.labelSmall)
        Text(book.title, Modifier.testTag("shelf-continue-title"), style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(chapter, Modifier.testTag("shelf-continue-chapter"), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .8f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.PlayArrow, "继续 ${book.title}，$chapter", Modifier.size(22.dp))
    }
}

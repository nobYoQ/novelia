package cc.novelia.app.ui.components.media

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import cc.novelia.app.data.network.isRemoteImage
import cc.novelia.app.ui.components.base.NetworkRecoveryEffect
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

@Composable internal fun RecoveringAsyncImage(model: Any?, contentDescription: String?, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onLoading: ((AsyncImagePainter.State.Loading) -> Unit)? = null,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null) {
    var failure by remember(model) { mutableStateOf<Throwable?>(null) }
    var retry by remember(model) { mutableIntStateOf(0) }
    if (isRemoteImage((model as? ImageRequest)?.data ?: model)) {
        NetworkRecoveryEffect(failure) { failure = null; retry++ }
    }
    key(retry) {
        AsyncImage(model, contentDescription, modifier, contentScale = contentScale,
            onLoading = { failure = null; onLoading?.invoke(it) },
            onSuccess = { failure = null; onSuccess?.invoke(it) },
            onError = { failure = it.result.throwable; onError?.invoke(it) })
    }
}

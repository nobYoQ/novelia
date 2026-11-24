package cc.novelia.app.data.images

import android.content.Context
import android.net.Uri
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.WenkuVolume
import coil.fetch.SourceResult
import coil.imageLoader
import coil.network.HttpException
import coil.request.CachePolicy
import coil.request.Options
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class PublicationCover(val title: String, val urls: List<String>)

internal fun WenkuVolume.publicationCover(index: Int): PublicationCover? {
    val urls = listOfNotNull(coverHires, cover).mapNotNull { it.trim().toHttpUrlOrNull()?.toString() }.distinct()
    if(urls.isEmpty()) return null
    val label = titleZh?.takeIf(String::isNotBlank) ?: title.ifBlank { asin.ifBlank { "出版卷目 ${index + 1}" } }
    return PublicationCover(label, urls)
}

internal suspend fun BookCoverStore.replaceFromPublication(context: Context, ref: BookRef, cover: PublicationCover) =
    withContext(Dispatchers.IO) {
        val expectedRevision = revision(ref)
        val loader = context.imageLoader
        // 读取原图字节；已有缩略预览的磁盘缓存也可复用，保存时仍由封面仓库校验格式和大小。
        val options = Options(context, diskCachePolicy = CachePolicy.READ_ONLY)
        for((index, url) in cover.urls.withIndex()) {
            try {
                val fetcher = loader.components.newFetcher(Uri.parse(url), options, loader)?.first
                val image = fetcher?.fetch() as? SourceResult ?: throw IOException("无法读取出版封面")
                image.source.use { source -> replace(ref, expectedRevision) { source.source().inputStream() } }
                return@withContext
            } catch(error: Exception) {
                if(index == cover.urls.lastIndex || (error !is HttpException && error !is IOException && error !is IllegalArgumentException)) throw error
            }
        }
    }

package cc.novelia.app.data.images

import okhttp3.Request

internal fun Request.withPixivImageReferer(): Request {
    if (!url.isHttps || url.host != "i.pximg.net" || url.port != 443) return this
    return newBuilder().header("Referer", "https://www.pixiv.net/").build()
}

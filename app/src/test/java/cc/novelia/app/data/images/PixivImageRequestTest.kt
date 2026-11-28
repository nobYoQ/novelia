package cc.novelia.app.data.images

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PixivImageRequestTest {
    @Test fun pixivIllustrationGetsTheSourceReferer() {
        val request = Request.Builder().url("https://i.pximg.net/novel-cover-original/img/test.png").build()
        assertEquals("https://www.pixiv.net/", request.withPixivImageReferer().header("Referer"))
    }

    @Test fun otherOriginsKeepTheirOriginalRequest() {
        for (url in listOf(
            "https://49423.mitemin.net/image",
            "https://i.pximg.net.example.org/image",
            "http://i.pximg.net/image",
            "https://i.pximg.net:8443/image",
        )) {
            val request = Request.Builder().url(url).build()
            assertSame(url, request, request.withPixivImageReferer())
        }
    }
}

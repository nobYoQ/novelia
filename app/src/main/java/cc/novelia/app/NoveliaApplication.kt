package cc.novelia.app

import android.app.Application
import cc.novelia.app.data.LocalStore
import cc.novelia.app.data.NoveliaApi
import cc.novelia.app.data.Session

class NoveliaApplication : Application() {
    val store by lazy { LocalStore(this) }
    val session by lazy { Session(this) }
    val api by lazy { NoveliaApi(session) }
}

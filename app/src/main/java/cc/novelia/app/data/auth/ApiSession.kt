package cc.novelia.app.data.auth

enum class AuthTarget(val appId: String, val origin: String, val preferencesName: String) {
    NOVEL("n", "https://n.novelia.cc", "session"),
    FORUM("f", "https://forum.novelia.cc", "forum-session")
}

interface ApiSession : AuthenticationSession {
    val target: AuthTarget
    val token: String?
}

package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class User(val username: String = "")

@Serializable data class Profile(val username: String, val role: String, val createdAt: Long, val expiresAt: Long, val userId: Long? = null) {
    val canPost get() = role in listOf("admin", "member")
    val canEdit get() = canPost && (role == "admin" || System.currentTimeMillis() / 1000 - createdAt >= 30L * 86400)
}

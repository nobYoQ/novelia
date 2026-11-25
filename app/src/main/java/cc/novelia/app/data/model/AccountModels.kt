package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class User(val username: String = "")

@Serializable data class Profile(val username: String, val role: String, val createdAt: Long, val expiresAt: Long, val userId: Long? = null) {
    private val registeredThirtyDays get() = System.currentTimeMillis() / 1000 - createdAt >= 30L * 86400
    val canReadAdult get() = registeredThirtyDays && role in setOf("restricted", "member", "trusted", "admin")
    val canEdit get() = registeredThirtyDays && role in setOf("member", "trusted", "admin")
}

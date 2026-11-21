package com.example.pinpoint.data

import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AuthUser(val id: String, val email: String, val displayName: String)
private data class SavedSession(val accessToken: String, val refreshToken: String, val expiresAt: Long, val user: AuthUser)

class SupabaseAuth(private val backend: SupabaseBackend, private val store: SessionStore) {
    private val mutex = Mutex()
    private var session: SavedSession? = runCatching {
        store.read(backend.url)?.let { backend.gson.fromJson(it, SavedSession::class.java) }
            ?.takeIf { it.accessToken.isNotBlank() && it.refreshToken.isNotBlank() && it.user.id.isNotBlank() }
    }.getOrNull()
    private val mutableUsers = MutableStateFlow(session?.user)
    val users = mutableUsers.asStateFlow()
    val currentUser: AuthUser? get() = mutableUsers.value

    suspend fun signIn(email: String, password: String) = mutex.withLock {
        save(backend.json("auth/v1/token?grant_type=password", "POST", mapOf("email" to email.trim(), "password" to password), false).asJsonObject)
    }
    suspend fun register(name: String, email: String, password: String): Boolean = mutex.withLock {
        require(name.trim().length in 2..80) { "Name must contain 2 to 80 characters." }
        require(password.length >= 8) { "Use a password with at least 8 characters." }
        val result = backend.json("auth/v1/signup", "POST", mapOf("email" to email.trim(), "password" to password,
            "data" to mapOf("display_name" to name.trim())), false).asJsonObject
        if (result.has("access_token") && !result["access_token"].isJsonNull) { save(result); true } else false
    }
    suspend fun accessToken(expectedUserId: String? = null): String = mutex.withLock {
        val current = session ?: error("Sign in first.")
        check(expectedUserId == null || current.user.id == expectedUserId) { "The queued report belongs to another signed-in account." }
        if (current.expiresAt <= System.currentTimeMillis() / 1000 + 60) {
            try {
                save(backend.json("auth/v1/token?grant_type=refresh_token", "POST",
                    mapOf("refresh_token" to current.refreshToken), false).asJsonObject)
            } catch (error: BackendException) {
                if (error.status in listOf(400, 401, 403)) clear()
                throw error
            }
        }
        requireNotNull(session).accessToken
    }
    suspend fun signOut() {
        backend.json("auth/v1/logout?scope=local", "POST")
        mutex.withLock { clear() }
    }
    private fun save(result: JsonObject) {
        val user = result.getAsJsonObject("user") ?: error("Authentication returned no user.")
        val metadata = user.getAsJsonObject("user_metadata")
        val saved = SavedSession(result["access_token"].asString, result["refresh_token"].asString,
            result["expires_at"]?.asLong ?: (System.currentTimeMillis()/1000 + result["expires_in"].asLong),
            AuthUser(user["id"].asString, user["email"]?.asString.orEmpty(), metadata?.get("display_name")?.asString ?: "Citizen"))
        require(saved.accessToken.isNotBlank() && saved.refreshToken.isNotBlank()) { "Authentication returned an invalid session." }
        store.write(backend.url, backend.gson.toJson(saved))
        session = saved
        mutableUsers.value = saved.user
    }
    private fun clear() { store.clear(backend.url); session = null; mutableUsers.value = null }
}

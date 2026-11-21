package com.example.pinpoint.data

import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AuthUser(val id: String, val email: String, val displayName: String)
data class PendingEmailConfirmation(val email: String, val resendAvailableAt: Long = 0)
private data class SavedSession(val accessToken: String, val refreshToken: String, val expiresAt: Long, val user: AuthUser)

class SupabaseAuth(private val backend: SupabaseBackend, private val store: SessionStore,
    private val publicPost: suspend (String, Any) -> JsonObject = { path, body ->
        backend.json(path,"POST",body,false).asJsonObject
    }) {
    private val mutex = Mutex()
    private var session: SavedSession? = runCatching {
        store.read(backend.url)?.let { backend.gson.fromJson(it, SavedSession::class.java) }
            ?.takeIf { it.accessToken.isNotBlank() && it.refreshToken.isNotBlank() && it.user.id.isNotBlank() }
    }.getOrNull()
    private val mutableUsers = MutableStateFlow(session?.user)
    val users = mutableUsers.asStateFlow()
    val currentUser: AuthUser? get() = mutableUsers.value
    private val pendingKey = "${backend.url}#pending-confirmation"
    private val mutableConfirmation = MutableStateFlow(runCatching {
        if (session != null) null else store.read(pendingKey)?.let {
            backend.gson.fromJson(it, PendingEmailConfirmation::class.java)
        }?.takeIf { it.email.isNotBlank() }
    }.getOrNull())
    val confirmation = mutableConfirmation.asStateFlow()

    suspend fun signIn(email: String, password: String) = mutex.withLock {
        try {
            save(publicPost("auth/v1/token?grant_type=password", mapOf("email" to email.trim(), "password" to password)))
        } catch (error: BackendException) {
            if (error.code == "email_not_confirmed") setConfirmation(PendingEmailConfirmation(email.trim()))
            throw error
        }
    }
    suspend fun register(name: String, email: String, password: String): Boolean = mutex.withLock {
        require(name.trim().length in 2..80) { "Name must contain 2 to 80 characters." }
        require(password.length >= 8) { "Use a password with at least 8 characters." }
        val result = publicPost("auth/v1/signup", mapOf("email" to email.trim(), "password" to password,
            "data" to mapOf("display_name" to name.trim())))
        if (result.has("access_token") && !result["access_token"].isJsonNull) { save(result); true }
        else { setConfirmation(PendingEmailConfirmation(email.trim(), System.currentTimeMillis() + 60_000)); false }
    }
    suspend fun verifyEmail(code: String) = mutex.withLock {
        require(code.matches(Regex("[0-9]{6}"))) { "Enter the six-digit code from your confirmation email." }
        val pending = requireNotNull(confirmation.value) { "Enter your email first." }
        save(publicPost("auth/v1/verify", mapOf("email" to pending.email, "token" to code, "type" to "email")))
    }
    suspend fun resendConfirmation() = mutex.withLock {
        val pending = requireNotNull(confirmation.value) { "Enter your email first." }
        check(System.currentTimeMillis() >= pending.resendAvailableAt) { "Wait before requesting another confirmation email." }
        // Persist the cooldown before sending, including for interrupted requests.
        setConfirmation(pending.copy(resendAvailableAt = System.currentTimeMillis() + 60_000))
        try {
            publicPost("auth/v1/resend", mapOf("type" to "signup", "email" to pending.email))
        } catch (error: BackendException) {
            if (error.status == 429) setConfirmation(pending.copy(resendAvailableAt =
                System.currentTimeMillis() + (error.retryAfterSeconds ?: 60) * 1000))
            throw error
        }
    }
    fun enterConfirmation(email: String) {
        val trimmed = email.trim()
        require(trimmed.contains('@') && trimmed.length <= 254) { "Enter your account's email address." }
        val existing = confirmation.value
        setConfirmation(if (existing?.email == trimmed) existing else PendingEmailConfirmation(trimmed))
    }
    fun cancelConfirmation() = setConfirmation(null)
    private fun setConfirmation(pending: PendingEmailConfirmation?) {
        // Persist only the email and retry time, never a password or confirmation code.
        if (pending == null) store.clear(pendingKey) else store.write(pendingKey, backend.gson.toJson(pending))
        mutableConfirmation.value = pending
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
        setConfirmation(null)
        session = saved
        mutableUsers.value = saved.user
    }
    private fun clear() { store.clear(backend.url); session = null; mutableUsers.value = null }
}

package com.example.pinpoint.data

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.example.pinpoint.BuildConfig
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BackendException(val status: Int, val code: String, message: String, val retryAfterSeconds: Long? = null) : IOException("$code: $message")

class SupabaseBackend private constructor(context: Context, val url: String, internal val publishableKey: String,
    sessionPreferences: String = "pinpoint_secure_session") {
    val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    val auth = SupabaseAuth(this, SessionStore(context, sessionPreferences))

    suspend fun json(path: String, method: String = "GET", body: Any? = null, authenticated: Boolean = true, expectedUserId: String? = null): JsonElement {
        val bytes = body?.let { gson.toJson(it).toByteArray() }
        val response = request(path, method, bytes, "application/json", authenticated, expectedUserId)
        if (response.isEmpty()) return JsonNull.INSTANCE
        return try { JsonParser.parseString(String(response, Charsets.UTF_8)) }
        catch (error: JsonParseException) {
            // A captive portal or proxy can answer 200 with a web page. Treat it like a dropped connection so queued
            // work is retried instead of being marked permanently failed.
            throw IOException("The server returned something other than data. Check the connection and try again.", error)
        }
    }

    suspend fun request(path: String, method: String, bytes: ByteArray? = null,
        contentType: String = "application/json", authenticated: Boolean = true, expectedUserId: String? = null): ByteArray {
        require(!path.startsWith("/") && !path.contains("://")) { "Expected a relative backend path." }
        var rejected: String? = null
        for (attempt in 0..1) {
            val token = if (authenticated) auth.accessToken(expectedUserId, rejected) else null
            try { return send(path, method, bytes, contentType, token) }
            catch (error: BackendException) {
                // 401 = rejected before execution, so one retry with a freshly refreshed token is safe even for POST.
                // It covers a device clock that makes an expired token look valid.
                if (attempt == 0 && token != null && error.status == 401) rejected = token else throw error
            }
        }
        error("unreachable")
    }

    private suspend fun send(path: String, method: String, bytes: ByteArray?, contentType: String, token: String?): ByteArray {
        val payload = if (method in listOf("GET", "HEAD")) null else (bytes ?: ByteArray(0)).toRequestBody(contentType.toMediaType())
        val request = Request.Builder().url("${url.trimEnd('/')}/$path")
            .header("apikey", publishableKey).apply { if (token != null) header("Authorization", "Bearer $token") }
            .method(method, payload).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val data = it.body?.bytes() ?: ByteArray(0)
                            if (!it.isSuccessful) {
                                val error = runCatching { JsonParser.parseString(String(data, Charsets.UTF_8)).asJsonObject }.getOrNull()
                                // Auth returns a numeric HTTP `code` beside its symbolic `error_code`.
                                val code = error?.get("error_code")?.asString
                                    ?: error?.get("code")?.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString
                                    ?: "HTTP_${it.code}"
                                val message = error?.get("message")?.asString ?: error?.get("msg")?.asString
                                    ?: error?.get("error_description")?.asString
                                    ?: error?.get("error")?.takeIf { value -> value.isJsonPrimitive }?.asString ?: it.message
                                throw BackendException(it.code, code, message,
                                    it.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600))
                            }
                            if (continuation.isActive) continuation.resume(data)
                        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    }
                }
            })
        }
    }

    companion object {
        @Volatile private var instance: SupabaseBackend? = null
        @Volatile private var testInstance: SupabaseBackend? = null

        /** Debug builds only: route the whole app (workers, services) to a local fake server with isolated session storage. */
        fun installForTesting(context: Context, url: String, key: String, sessionPreferences: String = "pinpoint_test_session"): SupabaseBackend {
            check(BuildConfig.DEBUG) { "Test backend is available in debug builds only." }
            require(url.startsWith("http://127.0.0.1")) { "Test backend must be a loopback URL." }
            context.applicationContext.getSharedPreferences(sessionPreferences, Context.MODE_PRIVATE).edit(commit = true) { clear() }
            return SupabaseBackend(context.applicationContext, url, key, sessionPreferences).also { testInstance = it }
        }
        fun resetForTesting() { testInstance = null }

        fun create(context: Context): SupabaseBackend? {
            testInstance?.let { return it }
            if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) return null
            check(BuildConfig.SUPABASE_URL.startsWith("https://") || (BuildConfig.DEBUG &&
                BuildConfig.SUPABASE_URL.startsWith("http://127.0.0.1"))) { "Supabase must use HTTPS." }
            val configuredKey=BuildConfig.SUPABASE_PUBLISHABLE_KEY
            val legacyRole=runCatching {
                val payload=configuredKey.split('.')[1]
                JsonParser.parseString(String(Base64.decode(payload,Base64.URL_SAFE or Base64.NO_WRAP),Charsets.UTF_8))
                    .asJsonObject.get("role")?.asString
            }.getOrNull()
            check(configuredKey.startsWith("sb_publishable_") || legacyRole=="anon") { "Use a publishable or legacy anon key, never a server secret." }
            return instance ?: synchronized(this) {
                instance ?: SupabaseBackend(context.applicationContext, BuildConfig.SUPABASE_URL,
                    BuildConfig.SUPABASE_PUBLISHABLE_KEY).also { instance = it }
            }
        }
    }
}

package com.example.pinpoint

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A minimal loopback stand-in for the Supabase endpoints the app uses (password/refresh/logout Auth, photo Storage,
 * the submit_report RPC). It lets tests inject faults deterministically without touching the hosted project. It models
 * only what the failure tests need: tokens map to users, report creation is idempotent per id, photo upload refuses
 * duplicates like Storage does.
 */
class FakeSupabaseServer : Closeable {
    enum class Rpc { OK, DROP_BEFORE_COMMIT, DROP_AFTER_COMMIT, HTML_200, SERVER_500, VALIDATION_400, HOLD_THEN_OK }
    enum class Refresh { OK, REVOKED_400, SERVER_503, DROP }
    class Seen(val method: String, val path: String, val query: String, val bearer: String?, val body: ByteArray)

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    val requests = CopyOnWriteArrayList<Seen>()
    val reports = ConcurrentHashMap<String, String>()          // report id -> owner uid
    val creations = CopyOnWriteArrayList<Pair<String, String>>() // (owner uid, report id) per actual creation
    val photos = ConcurrentHashMap<String, ByteArray>()
    val rpcBehaviors = ConcurrentLinkedQueue<Rpc>()
    @Volatile var refreshBehavior = Refresh.OK
    @Volatile var nextExpiresIn = 3600
    /** Bearers that answer 401 "JWT expired" exactly once, to model a token the client still believes valid. */
    val rejectOnce: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Report rows served by GET /rest/v1/reports (newest first, same filters and keyset cursor as PostgREST). */
    class Row(val id: String, val owner: String, val category: String, val status: String, val createdAt: String,
              val description: String, val lat: Double? = null, val lon: Double? = null)
    val reportRows = CopyOnWriteArrayList<Row>()
    /** One entry is consumed per reports read: OK, DROP_BEFORE_COMMIT (connection dropped), HTML_200, SERVER_500, HOLD_THEN_OK. */
    val readBehaviors = ConcurrentLinkedQueue<Rpc>()
    val readEntered = CountDownLatch(1)
    val releaseRead = CountDownLatch(1)
    /** attach_original is idempotent per report id; attachCommits records each FIRST attach only. */
    val attachBehaviors = ConcurrentLinkedQueue<Rpc>()
    val attached: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val attachCommits = CopyOnWriteArrayList<String>()
    val rpcEntered = CountDownLatch(1)
    val releaseHold = CountDownLatch(1)

    private val accessTokens = ConcurrentHashMap<String, String>()
    private val refreshTokens = ConcurrentHashMap<String, String>()
    val passwords = ConcurrentHashMap<String, String>()
    val recoveryRequests = CopyOnWriteArrayList<String>()
    @Volatile var validRecoveryCode = "654321"
    private var sequence = 0

    fun userId(email: String): String = UUID.nameUUIDFromBytes(email.toByteArray()).toString()
    fun count(path: String) = requests.count { it.path == path }
    fun latestAccessToken(uid: String): String? = accessTokens.entries.lastOrNull { it.value == uid }?.key

    init { pool.execute { while (!socket.isClosed) runCatching { val client = socket.accept(); pool.execute { serve(client) } } } }
    override fun close() { runCatching { socket.close() }; pool.shutdownNow() }

    private fun serve(client: Socket) = client.use { c ->
        c.soTimeout = 20_000
        val input = c.getInputStream()
        val requestLine = readLine(input) ?: return
        val (method, target) = requestLine.split(' ').let { it[0] to it[1] }
        val headers = HashMap<String, String>()
        while (true) { val line = readLine(input) ?: return; if (line.isEmpty()) break; line.split(": ", limit = 2).let { headers[it[0].lowercase()] = it.getOrElse(1) { "" } } }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { var read = 0; while (read < it.size) { val n = input.read(it, read, it.size - read); if (n < 0) break; read += n } }
        val path = target.substringBefore('?'); val query = target.substringAfter('?', "")
        val bearer = headers["authorization"]?.removePrefix("Bearer ")
        val seen = Seen(method, path, query, bearer, body); requests += seen
        val reply = try { handle(seen) } catch (_: Exception) { null }
        if (reply == null) return                                    // connection dropped without a response
        val (status, type, payload) = reply
        // A client that cancelled or timed out has closed its socket; that must not crash the test process.
        runCatching {
            val out = c.getOutputStream()
            out.write("HTTP/1.1 $status X\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(payload); out.flush()
        }.let { }
    }

    private fun json(status: Int, value: JSONObject) = Triple(status, "application/json", value.toString().toByteArray())

    private fun json2(status: Int, value: org.json.JSONArray) = Triple(status, "application/json", value.toString().toByteArray())

    private fun reportsJson(query: String, uid: String): org.json.JSONArray {
        val params = query.split('&').filter { it.contains('=') }.associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        var rows = reportRows.toList()
        params["status"]?.removePrefix("eq.")?.let { v -> rows = rows.filter { it.status == v } }
        params["category"]?.removePrefix("eq.")?.let { v -> rows = rows.filter { it.category == v } }
        params["owner_id"]?.removePrefix("eq.")?.let { v -> rows = rows.filter { it.owner == v } }
        params["or"]?.let { cursor ->
            val m = Regex("""created_at\.lt\.([^,]+),and\(created_at\.eq\.([^,]+),id\.lt\.([^)]+)\)""").find(cursor)
            if (m != null) { val (t, _, id) = m.destructured; rows = rows.filter { it.createdAt < t || (it.createdAt == t && it.id < id) } }
        }
        rows = rows.sortedWith(compareByDescending<Row> { it.createdAt }.thenByDescending { it.id })
        val limit = params["limit"]?.toInt() ?: 1000
        return org.json.JSONArray().also { out -> rows.take(limit).forEach { out.put(JSONObject().put("id", it.id).put("owner_id", it.owner)
            .put("category", it.category).put("description", it.description).put("location", "Test").put("barangay", "Alangilan")
            .put("status", it.status).put("priority", "Low").put("created_at", it.createdAt).put("vote_count", 0)
            .put("latitude", it.lat ?: JSONObject.NULL).put("longitude", it.lon ?: JSONObject.NULL).put("photo_path", JSONObject.NULL)) } }
    }

    private fun session(uid: String, email: String): JSONObject {
        sequence++
        val access = "at-$uid-$sequence"; val refresh = "rt-$uid-$sequence"
        accessTokens[access] = uid; refreshTokens[refresh] = uid
        return JSONObject().put("access_token", access).put("refresh_token", refresh).put("expires_in", nextExpiresIn)
            .put("user", JSONObject().put("id", uid).put("email", email).put("user_metadata", JSONObject().put("display_name", "Test User")))
    }

    private fun handle(r: Seen): Triple<Int, String, ByteArray>? {
        when {
            r.path == "/auth/v1/token" && r.query.contains("grant_type=password") -> {
                val payload = JSONObject(String(r.body))
                val email = payload.getString("email")
                val expectedPw = passwords[email]
                if (expectedPw != null && payload.optString("password") != expectedPw) {
                    return json(400, JSONObject().put("error_code", "invalid_credentials").put("msg", "Invalid login credentials"))
                }
                return json(200, session(userId(email), email))
            }
            r.path == "/auth/v1/recover" && r.method == "POST" -> {
                val email = JSONObject(String(r.body)).getString("email")
                recoveryRequests += email
                return json(200, JSONObject())
            }
            r.path == "/auth/v1/verify" && r.method == "POST" -> {
                val payload = JSONObject(String(r.body))
                val email = payload.getString("email")
                val token = payload.getString("token")
                if (token != validRecoveryCode) {
                    return json(403, JSONObject().put("error_code", "otp_expired").put("msg", "Token has expired or is invalid"))
                }
                return json(200, session(userId(email), email))
            }
            r.path == "/auth/v1/token" && r.query.contains("grant_type=refresh_token") -> return when (refreshBehavior) {
                Refresh.OK -> {
                    val uid = refreshTokens[JSONObject(String(r.body)).getString("refresh_token")] ?: return json(400, JSONObject().put("error_code", "refresh_token_not_found").put("msg", "Invalid Refresh Token"))
                    json(200, session(uid, "refreshed@test"))
                }
                Refresh.REVOKED_400 -> json(400, JSONObject().put("error_code", "refresh_token_not_found").put("msg", "Invalid Refresh Token: Refresh Token Not Found"))
                Refresh.SERVER_503 -> json(503, JSONObject().put("msg", "unavailable"))
                Refresh.DROP -> null
            }
            r.path == "/auth/v1/logout" -> return Triple(204, "application/json", ByteArray(0))
        }
        // Everything below needs a valid bearer token.
        val uid = r.bearer?.let { accessTokens[it] } ?: return json(401, JSONObject().put("code", "PGRST301").put("message", "JWT invalid"))
        if (rejectOnce.remove(r.bearer)) return json(401, JSONObject().put("code", "PGRST303").put("message", "JWT expired"))
        if (r.method == "PUT" && r.path == "/auth/v1/user") {
            val payload = JSONObject(String(r.body))
            payload.optString("password").takeIf { it.isNotBlank() }?.let { newPw ->
                recoveryRequests.lastOrNull()?.let { passwords[it] = newPw }
                passwords["user-$uid"] = newPw
            }
            return json(200, JSONObject().put("id", uid))
        }
        if (r.method == "POST" && r.path.startsWith("/storage/v1/object/report-images/")) {
            val name = r.path.removePrefix("/storage/v1/object/report-images/")
            if (photos.containsKey(name)) return json(400, JSONObject().put("statusCode", "409").put("error", "Duplicate").put("message", "The resource already exists"))
            photos[name] = r.body
            return json(200, JSONObject().put("Key", "report-images/$name"))
        }
        if (r.method == "GET" && r.path.startsWith("/storage/v1/object/authenticated/report-images/")) {
            val bytes = photos[r.path.removePrefix("/storage/v1/object/authenticated/report-images/")] ?: return json(404, JSONObject().put("statusCode", "404").put("message", "Object not found"))
            return Triple(200, "image/jpeg", bytes)
        }
        if (r.method == "GET" && r.path == "/rest/v1/reports") {
            return when (readBehaviors.poll() ?: Rpc.OK) {
                Rpc.OK -> json2(200, reportsJson(r.query, uid))
                Rpc.DROP_BEFORE_COMMIT, Rpc.DROP_AFTER_COMMIT -> null
                Rpc.HTML_200 -> Triple(200, "text/html", "<html><body>Sign in to the Wi-Fi network</body></html>".toByteArray())
                Rpc.SERVER_500 -> json(500, JSONObject().put("message", "internal"))
                Rpc.VALIDATION_400 -> json(400, JSONObject().put("code", "22023").put("message", "bad"))
                Rpc.HOLD_THEN_OK -> { readEntered.countDown(); releaseRead.await(30, TimeUnit.SECONDS); json2(200, reportsJson(r.query, uid)) }
            }
        }
        if (r.method == "POST" && r.path == "/rest/v1/rpc/attach_original") {
            val id = JSONObject(String(r.body)).getString("p_report_id")
            fun commit() { if (attached.add(id)) attachCommits += id }
            return when (attachBehaviors.poll() ?: Rpc.OK) {
                Rpc.OK -> { commit(); json(200, JSONObject()) }
                Rpc.DROP_AFTER_COMMIT -> { commit(); null }
                Rpc.DROP_BEFORE_COMMIT -> null
                Rpc.SERVER_500 -> json(500, JSONObject().put("message", "internal"))
                else -> json(400, JSONObject().put("message", "unsupported test behavior"))
            }
        }
        if (r.method == "POST" && r.path == "/rest/v1/rpc/my_profile") return json(200, JSONObject().put("id", uid).put("display_name", "Test User").put("official", false))
        if (r.method == "POST" && r.path == "/rest/v1/rpc/pending_push_reports") return json2(200, org.json.JSONArray())
        if (r.method == "GET" && r.path == "/rest/v1/notifications") return json2(200, org.json.JSONArray())
        if (r.method == "POST" && r.path == "/rest/v1/rpc/submit_report") {
            val id = JSONObject(String(r.body)).getString("p_id")
            val behavior = rpcBehaviors.poll() ?: Rpc.OK
            fun commit(): JSONObject {
                val owner = reports.putIfAbsent(id, uid)
                if (owner == null) creations += uid to id else check(owner == uid) { "another account's report" }
                return JSONObject().put("id", id).put("owner_id", uid)
            }
            return when (behavior) {
                Rpc.OK -> json(200, commit())
                Rpc.DROP_BEFORE_COMMIT -> null
                Rpc.DROP_AFTER_COMMIT -> { commit(); null }
                Rpc.HTML_200 -> Triple(200, "text/html", "<html><body>Sign in to the Wi-Fi network</body></html>".toByteArray())
                Rpc.SERVER_500 -> json(500, JSONObject().put("message", "internal"))
                Rpc.VALIDATION_400 -> json(400, JSONObject().put("code", "22023").put("message", "Invalid report"))
                Rpc.HOLD_THEN_OK -> { rpcEntered.countDown(); releaseHold.await(15, TimeUnit.SECONDS); json(200, commit()) }
            }
        }
        return json(404, JSONObject().put("message", "unhandled ${r.method} ${r.path}"))
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString()
            if (b == '\n'.code) return out.toString().trimEnd('\r')
            out.write(b)
        }
    }
}

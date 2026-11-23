package com.example.pinpoint.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** User-facing wording. Technical detail (exception class, HTTP code) stays in logs and stored diagnostics, never on screen. */
object UiText {
    private val classPrefix = Regex("""^([A-Za-z_.]+(?:Exception|Error)):\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val networkClasses = setOf("IOException", "SocketTimeoutException", "UnknownHostException", "ConnectException",
        "SSLException", "SSLHandshakeException", "EOFException", "SocketException", "InterruptedIOException")
    private const val OFFLINE = "Can't reach the server. Check your connection and try again."
    private const val GENERIC = "Something went wrong. Please try again."

    /** Accepts either an exception or the stored "ClassName: message" diagnostic string. */
    fun friendlyError(error: Throwable): String =
        if (error is BackendException) backend(error.status, error.code, (error.message ?: "").substringAfter(": ", ""))
        else humanize("${error.javaClass.simpleName}: ${error.message ?: ""}")

    fun humanize(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return GENERIC
        val match = classPrefix.matchEntire(text)
        val cls = match?.groupValues?.get(1)?.substringAfterLast('.')
        val rest = (match?.groupValues?.get(2) ?: text).trim()
        if (cls == "BackendException") return backend(rest)
        if (cls != null && cls in networkClasses) {
            // Sentences we wrote ourselves for connection problems are already user-friendly; raw platform text is not.
            return if (rest.contains("Wi-Fi", true) || rest.contains("something other than data", true) || rest.contains("unmetered", true)) sentence(rest) else OFFLINE
        }
        if (rest.isBlank() || looksTechnical(rest)) return GENERIC
        return sentence(rest)
    }

    /** From a stored "BackendException: CODE: message" diagnostic; the status is only recoverable for HTTP_nnn / UPLOAD_nnn codes. */
    private fun backend(rest: String): String {
        val code = rest.substringBefore(':', "").trim()
        val message = rest.substringAfter(':', "").trim()
        val status = Regex("^(?:HTTP|UPLOAD)_(\\d{3})$").find(code)?.groupValues?.get(1)?.toIntOrNull()
        return backend(status, code, message)
    }

    private fun backend(status: Int?, code: String, message: String): String {
        return when {
            status == 401 || status == 403 || code.contains("JWT", true) -> "Your session expired or you don't have permission for that. Sign in again."
            status == 408 || status == 429 -> "The service is busy. Please wait a moment and try again."
            status != null && status >= 500 -> "The server had a problem. Please try again shortly."
            code.equals("email_not_confirmed", true) -> "Confirm your email address first, then sign in."
            code.equals("invalid_credentials", true) || message.contains("invalid login", true) -> "That email or password isn't right."
            message.isNotBlank() && !looksTechnical(message) -> sentence(message)
            else -> GENERIC
        }
    }

    private fun looksTechnical(text: String) = text.contains("Exception") || text.contains("java.") || text.contains("kotlin.") ||
        text.contains("android.") || text.contains(" at ") && text.contains("(") && text.contains(".kt")

    private fun sentence(text: String): String {
        val t = text.trim().replaceFirstChar { it.uppercase() }
        return if (t.endsWith('.') || t.endsWith('!') || t.endsWith('?')) t else "$t."
    }

    private val timestamp = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}(?::?\d{2})?)?$""")

    /** "2026-11-21T13:21:22.170879+00:00" -> "Nov 21, 2026, 9:21 PM" in [zone]; anything unparseable is returned unchanged. */
    fun formatTimestamp(iso: String?, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
        val value = iso?.trim().orEmpty()
        val m = timestamp.matchEntire(value) ?: return value
        val g = m.groupValues
        val f = g.subList(1, 7).map { it.toInt() }
        val offsetMinutes = g[7].let { z ->
            if (z.isEmpty() || z == "Z") 0 else {
                val sign = if (z[0] == '-') -1 else 1
                val digits = z.substring(1).replace(":", "")
                sign * (digits.substring(0, 2).toInt() * 60 + (digits.getOrNull(2)?.let { digits.substring(2).toInt() } ?: 0))
            }
        }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { clear(); set(f[0], f[1] - 1, f[2], f[3], f[4], f[5]) }
        val instant = utc.timeInMillis - offsetMinutes * 60_000L
        return SimpleDateFormat("MMM d, yyyy, h:mm a", locale).apply { timeZone = zone }.format(java.util.Date(instant))
    }
}

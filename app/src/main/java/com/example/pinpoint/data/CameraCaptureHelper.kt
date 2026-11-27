package com.example.pinpoint.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Pure helpers for password strength, deployed SMS formatting and FileProvider camera capture lifecycle. */
object PasswordPolicy {
    data class Strength(
        val minLength: Boolean,
        val hasUpperAndLower: Boolean,
        val hasDigit: Boolean,
        val hasSymbol: Boolean
    ) {
        val score: Int get() = listOf(minLength, hasUpperAndLower, hasDigit, hasSymbol).count { it }
        val label: String get() = when (score) {
            0, 1 -> "Weak password"
            2 -> "Fair password"
            3 -> "Good password"
            else -> "Strong password"
        }
    }

    fun evaluate(password: String): Strength = Strength(
        minLength = password.length >= 8,
        hasUpperAndLower = password.any { it.isUpperCase() } && password.any { it.isLowerCase() },
        hasDigit = password.any { it.isDigit() },
        hasSymbol = password.any { !it.isLetterOrDigit() }
    )
}

/** Matches the deployed Edge Function parser (supabase/functions/_shared/sms.mjs) exactly. */
object SmsFormatHelper {
    val categoryCodes: Map<String, String> = linkedMapOf(
        "Road Damage" to "ROAD",
        "Streetlight" to "LIGHT",
        "Flooding" to "FLOOD",
        "Garbage" to "GARBAGE",
        "Water Interruption" to "WATER",
        "Other" to "OTHER"
    )
    private val basic = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà".toSet()
    private val extended = "^{}\\[~]|€".toSet()

    fun buildMessage(categoryCode: String, barangay: String, description: String): String {
        val code = categoryCode.trim().uppercase().ifBlank { "ROAD" }
        val area = barangay.trim().ifBlank { "Alangilan" }
        val details = stripPinPrefix(description).replace(';', ',')
        return "PIN $code;$area;$details"
    }

    private val pinPrefix = Regex("""^\s*PIN\s+[A-Za-z]+\s*[;,]\s*[^;,]*[;,]\s*""", RegexOption.IGNORE_CASE)

    /** Someone who types or pastes a whole "PIN ROAD;Alangilan;text" message into the details box must not get the prefix twice. */
    fun stripPinPrefix(description: String): String = description.replaceFirst(pinPrefix, "").trim()

    /** Returns the GSM-7 septet count, or -1 if the text contains non-GSM characters. */
    fun gsm7Septets(text: String): Int {
        var total = 0
        for (c in text) {
            total += when {
                c in extended -> 2
                c in basic -> 1
                else -> return -1
            }
        }
        return total
    }
}

object CameraCaptureHelper {
    private const val DIR_NAME = "camera_captures"

    fun captureDirectory(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { check(exists() || mkdirs()) { "Could not prepare camera storage." } }

    fun createCaptureTarget(context: Context): Pair<File, Uri> {
        val file = File(captureDirectory(context), "capture_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    fun isManagedCaptureFile(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val dir = File(context.filesDir, DIR_NAME).canonicalFile
        val target = File(path).canonicalFile
        return target.parentFile == dir
    }

    fun deleteCaptureFile(context: Context, path: String?) {
        if (isManagedCaptureFile(context, path)) {
            File(requireNotNull(path)).takeIf { it.isFile }?.delete()
        }
    }

    fun deleteCaptureUri(context: Context, uri: Uri?) {
        if (uri == null || uri.authority != "${context.packageName}.fileprovider") return
        val name = uri.lastPathSegment ?: return
        if (!name.startsWith("capture_") || !name.endsWith(".jpg")) return
        val candidate = File(File(context.filesDir, DIR_NAME), name)
        if (isManagedCaptureFile(context, candidate.absolutePath)) {
            candidate.takeIf { it.isFile }?.delete()
        }
    }

    fun purgeStaleCaptures(context: Context, maxAgeMillis: Long = 24L * 60 * 60 * 1000) {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }
}

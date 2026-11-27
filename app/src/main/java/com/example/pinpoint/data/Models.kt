package com.example.pinpoint.data

data class Citizen(val id: String, val email: String, val displayName: String, val official: Boolean = false,val barangay: String?=null,
    val contactNumber: String?=null, val homeLocation: String?=null)

/** Profile fields typed by the citizen. Blank means "not set"; anything else is validated the same way the database CHECKs it. */
object ProfileInput {
    /** Accepts "0917 123 4567", "+63 917-123-4567", "(043) 123 4567"; returns digits with an optional leading +, or null if blank. */
    fun contact(raw: String): String? {
        val cleaned = raw.trim().replace(Regex("""[\s\-().]"""), "")
        if (cleaned.isEmpty()) return null
        require(cleaned.matches(Regex("""\+?[0-9]{7,15}"""))) { "Enter a valid contact number (7 to 15 digits, for example 0917 123 4567)." }
        return cleaned
    }
    fun location(raw: String): String? {
        val trimmed = raw.trim().replace(Regex("""\s+"""), " ")
        if (trimmed.isEmpty()) return null
        require(trimmed.length in 2..120) { "Location must contain 2 to 120 characters." }
        return trimmed
    }
}

data class Report(
    val id: String = "",
    val ownerId: String = "",
    val category: String = "",
    val description: String = "",
    val location: String = "",
    val barangay: String = "",
    val status: String = "Pending",
    val priority: String = "Low",
    val createdAt: String = "",
    val photoPath: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val pendingSync: Boolean = false,
    val voteCount: Int = 0,
    val originalPath: String? = null,
    val originalUploadStatus: String? = null
) {
    val title: String get() = description.lineSequence().firstOrNull().orEmpty().take(80)
}

data class ReportDraft(
    val category: String,
    val description: String,
    val location: String,
    val barangay: String,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    fun validate() {
        require(category in CATEGORIES) { "Select a valid category." }
        require(description.trim().length in 10..2000) { "Description must contain 10 to 2000 characters." }
        require(location.trim().length in 3..200) { "Location must contain 3 to 200 characters." }
        require(barangay.trim().length in 2..100) { "Barangay must contain 2 to 100 characters." }
        require((latitude == null) == (longitude == null)) { "Enter both latitude and longitude, or leave both blank." }
        require(latitude == null || (latitude.isFinite() && latitude in -90.0..90.0)) { "Latitude must be between -90 and 90." }
        require(longitude == null || (longitude.isFinite() && longitude in -180.0..180.0)) { "Longitude must be between -180 and 180." }
    }
}

val CATEGORIES = listOf("Road Damage", "Streetlight", "Flooding", "Garbage", "Water Interruption", "Other")
data class ReportNotification(val id: String, val reportId: String, val message: String, val read: Boolean, val createdAt: String = "")
data class NotificationPage(val items: List<ReportNotification>, val next: ReportCursor?)

data class StatusChange(val status: String,val previousStatus: String,val createdAt: String)

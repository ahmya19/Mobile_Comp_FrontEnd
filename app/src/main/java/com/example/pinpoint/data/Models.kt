package com.example.pinpoint.data

data class Citizen(val id: String, val email: String, val displayName: String, val official: Boolean = false)

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
    val pendingSync: Boolean = false
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
data class ReportNotification(val id: String, val reportId: String, val message: String, val read: Boolean)

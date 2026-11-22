package com.example.pinpoint

import com.example.pinpoint.data.ReportDraft
import org.junit.Assert.assertThrows
import org.junit.Test

class ReportDraftTest {
    private val draft = ReportDraft("Road Damage", "A large pothole near the school.", "Main Road", "Alangilan")

    @Test fun acceptsTextOnlyReportAndValidCoordinates() {
        draft.validate()
        draft.copy(latitude = 13.78, longitude = 121.07).validate()
    }

    @Test fun rejectsIncompleteOrNonFiniteCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { draft.copy(latitude = 13.78).validate() }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(latitude = Double.NaN, longitude = 121.07).validate() }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(latitude = 13.78, longitude = Double.POSITIVE_INFINITY).validate() }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(latitude = 91.0, longitude = 121.07).validate() }
    }

    @Test fun rejectsInvalidContentBeforeStartingUpload() {
        assertThrows(IllegalArgumentException::class.java) { draft.copy(category = "Made up").validate() }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(description = "short").validate() }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(barangay = " ").validate() }
    }
}

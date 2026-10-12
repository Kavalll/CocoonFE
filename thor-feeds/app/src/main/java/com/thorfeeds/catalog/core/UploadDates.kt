package com.thorfeeds.catalog.core

import java.time.Instant
import java.time.Month
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

object UploadDates {
    private val calendarDate = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    fun parseEpochMs(iso: String): Long {
        if (iso.isBlank()) return Long.MIN_VALUE
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            // Fall through to Instant, then give up without throwing to callers.
        }
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: DateTimeParseException) {
            Long.MIN_VALUE
        }
    }

    /** Formats the calendar date written in the feed, without shifting time zones. */
    fun format(iso: String): String {
        val match = calendarDate.find(iso) ?: return iso.ifBlank { "Unknown date" }
        val year = match.groupValues[1]
        val monthNumber = match.groupValues[2].toIntOrNull() ?: return iso
        val day = match.groupValues[3].toIntOrNull() ?: return iso
        val month = try {
            Month.of(monthNumber).getDisplayName(TextStyle.SHORT, Locale.US)
        } catch (_: Exception) {
            return iso
        }
        return "$day $month $year"
    }
}

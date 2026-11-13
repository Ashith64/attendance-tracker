package app.attendancetracker.parser

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure Kotlin date parser for portal attendance dates and session time slots.
 * Supports multiple date formats (dd-MM-yyyy, dd/MM/yyyy, yyyy-MM-dd, dd-MMM-yyyy, etc.).
 * Converts dates to epoch millis for chronological sorting (most recent first)
 * and formats dates cleanly for display (e.g. "17 Aug 2026").
 * Never fabricates dates; preserves raw string if unparseable.
 */
object AttendanceDateParser {

    private val PARSER_FORMATTERS = listOf(
        DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.US),
        DateTimeFormatter.ofPattern("d-M-yyyy", Locale.US),
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("d/M/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US),
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.US),
        DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US),
        DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.US),
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US),
    )

    private val DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)

    data class ParsedDate(
        val epochMillis: Long,
        val formattedDate: String,
        val rawDate: String,
    )

    fun parse(rawDate: String, rawTime: String = ""): ParsedDate {
        val trimmed = rawDate.trim()
        if (trimmed.isEmpty()) {
            return ParsedDate(epochMillis = 0L, formattedDate = "Unknown Date", rawDate = "")
        }

        for (formatter in PARSER_FORMATTERS) {
            try {
                val localDate = LocalDate.parse(trimmed, formatter)
                val time = parseStartTime(rawTime)
                val localDateTime = localDate.atTime(time)
                val millis = localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val display = localDate.format(DISPLAY_FORMATTER)
                return ParsedDate(
                    epochMillis = millis,
                    formattedDate = display,
                    rawDate = trimmed,
                )
            } catch (_: Exception) {
                // Try next format
            }
        }

        // If unparseable by standard formats, keep raw date text faithfully
        return ParsedDate(
            epochMillis = 0L,
            formattedDate = trimmed,
            rawDate = trimmed,
        )
    }

    fun parseStartTime(rawTime: String): LocalTime {
        val cleaned = rawTime.replace(Regex("""\s+"""), " ").trim()
        val firstPart = cleaned.split(Regex("""\s+(TO|-)\s+""", RegexOption.IGNORE_CASE)).firstOrNull()?.trim().orEmpty()
        val timeMatcher = Regex("""(\d{1,2}):(\d{2})""").find(firstPart)
        if (timeMatcher != null) {
            val h = timeMatcher.groupValues[1].toIntOrNull() ?: 0
            val m = timeMatcher.groupValues[2].toIntOrNull() ?: 0
            return LocalTime.of(h.coerceIn(0, 23), m.coerceIn(0, 59))
        }
        return LocalTime.MIDNIGHT
    }

    fun formatSlot(rawTime: String): String {
        val cleaned = rawTime.replace(Regex("""\s+"""), " ").trim()
        if (cleaned.isEmpty() || cleaned == "-") return ""
        return cleaned.replace(Regex("""\s+TO\s+""", RegexOption.IGNORE_CASE), " - ")
    }
}

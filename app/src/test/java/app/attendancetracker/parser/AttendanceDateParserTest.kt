package app.attendancetracker.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class AttendanceDateParserTest {

    @Test
    fun `parses standard dd-MM-yyyy date format`() {
        val result = AttendanceDateParser.parse("17-08-2026", "11:00 TO 12:00")
        assertEquals("17 Aug 2026", result.formattedDate)
        assertEquals("17-08-2026", result.rawDate)
        assertTrue(result.epochMillis > 0L)
    }

    @Test
    fun `parses slash format dd-MM-yyyy and ISO yyyy-MM-dd format`() {
        val slashResult = AttendanceDateParser.parse("17/08/2026", "11:00 - 12:00")
        assertEquals("17 Aug 2026", slashResult.formattedDate)
        assertTrue(slashResult.epochMillis > 0L)

        val isoResult = AttendanceDateParser.parse("2026-08-17", "09:00 TO 10:00")
        assertEquals("17 Aug 2026", isoResult.formattedDate)
        assertTrue(isoResult.epochMillis > 0L)
    }

    @Test
    fun `extracts start time correctly for chronological sorting`() {
        val t1 = AttendanceDateParser.parseStartTime("11:00 TO 12:00")
        assertEquals(LocalTime.of(11, 0), t1)

        val t2 = AttendanceDateParser.parseStartTime("09:30 - 10:30")
        assertEquals(LocalTime.of(9, 30), t2)

        val t3 = AttendanceDateParser.parseStartTime("invalid")
        assertEquals(LocalTime.MIDNIGHT, t3)
    }

    @Test
    fun `multiple sessions on same date sort chronologically by epochMillis`() {
        val session1 = AttendanceDateParser.parse("17-08-2026", "11:00 TO 12:00")
        val session2 = AttendanceDateParser.parse("17-08-2026", "12:00 TO 13:00")
        assertTrue(session2.epochMillis > session1.epochMillis)
    }

    @Test
    fun `formats time slot replacing TO with dash`() {
        assertEquals("11:00 - 12:00", AttendanceDateParser.formatSlot("11:00 TO 12:00"))
        assertEquals("09:00 - 10:00", AttendanceDateParser.formatSlot("09:00 to 10:00"))
        assertEquals("", AttendanceDateParser.formatSlot(""))
        assertEquals("", AttendanceDateParser.formatSlot("-"))
    }

    @Test
    fun `handles invalid or empty date gracefully without crashing`() {
        val emptyResult = AttendanceDateParser.parse("")
        assertEquals("Unknown Date", emptyResult.formattedDate)
        assertEquals(0L, emptyResult.epochMillis)

        val invalidResult = AttendanceDateParser.parse("Not a date")
        assertEquals("Not a date", invalidResult.formattedDate)
        assertEquals(0L, invalidResult.epochMillis)
    }
}

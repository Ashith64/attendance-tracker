package app.attendancetracker.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceEngineTest {

    @Test
    fun `44 of 50 at 75 percent gives SAFE and canMiss 8`() {
        val result = AttendanceEngine.calculate(AttendanceInput(attended = 44, conducted = 50, thresholdPercent = 75))
        assertEquals(AttendanceStatus.SAFE, result.status)
        assertEquals(8, result.canMiss)
        assertEquals(0, result.mustAttendToRecover)
        assertEquals(88.0, result.percentage, 0.001)
        assertFalse(result.recoveryImpossible)
        assertNull(result.errorMessage)
    }

    @Test
    fun `39 of 48 at 75 percent gives SAFE and canMiss 4 and 39 of 49 gives canMiss 3`() {
        val r48 = AttendanceEngine.calculate(AttendanceInput(attended = 39, conducted = 48, thresholdPercent = 75))
        assertEquals(AttendanceStatus.SAFE, r48.status)
        assertEquals(4, r48.canMiss)
        assertEquals(0, r48.mustAttendToRecover)
        assertEquals(81.25, r48.percentage, 0.001)

        val r49 = AttendanceEngine.calculate(AttendanceInput(attended = 39, conducted = 49, thresholdPercent = 75))
        assertEquals(AttendanceStatus.SAFE, r49.status)
        assertEquals(3, r49.canMiss)
    }

    @Test
    fun `30 of 50 at 75 percent gives BELOW and mustAttend 30`() {
        val result = AttendanceEngine.calculate(AttendanceInput(attended = 30, conducted = 50, thresholdPercent = 75))
        assertEquals(AttendanceStatus.BELOW, result.status)
        assertEquals(0, result.canMiss)
        assertEquals(30, result.mustAttendToRecover)
        assertEquals(60.0, result.percentage, 0.001)
        assertFalse(result.recoveryImpossible)
    }

    @Test
    fun `0 of 0 gives NO_DATA without dividing by zero`() {
        val result = AttendanceEngine.calculate(AttendanceInput(attended = 0, conducted = 0, thresholdPercent = 75))
        assertEquals(AttendanceStatus.NO_DATA, result.status)
        assertEquals(0, result.canMiss)
        assertEquals(0, result.mustAttendToRecover)
        assertEquals(0.0, result.percentage, 0.0)
    }

    @Test
    fun `exact 75 percent gives SAFE and canMiss 0`() {
        val r1 = AttendanceEngine.calculate(AttendanceInput(attended = 75, conducted = 100, thresholdPercent = 75))
        assertEquals(AttendanceStatus.SAFE, r1.status)
        assertEquals(0, r1.canMiss)
        assertEquals(0, r1.mustAttendToRecover)

        val r2 = AttendanceEngine.calculate(AttendanceInput(attended = 3, conducted = 4, thresholdPercent = 75))
        assertEquals(AttendanceStatus.SAFE, r2.status)
        assertEquals(0, r2.canMiss)
    }

    @Test
    fun `thresholds 65 80 85 compute exact integer margins`() {
        // 40/50 = 80%: at 65% -> floor((4000 - 3250)/65) = floor(750/65) = 11
        val t65 = AttendanceEngine.calculate(AttendanceInput(40, 50, 65))
        assertEquals(AttendanceStatus.SAFE, t65.status)
        assertEquals(11, t65.canMiss)

        // 40/50 = 80%: at 80% -> canMiss 0
        val t80 = AttendanceEngine.calculate(AttendanceInput(40, 50, 80))
        assertEquals(AttendanceStatus.SAFE, t80.status)
        assertEquals(0, t80.canMiss)

        // 40/50 = 80%: at 85% -> ceil((4250 - 4000)/15) = ceil(250/15) = 17
        // Check: 57/67 = 85.07%, 56/66 = 84.85%
        val t85 = AttendanceEngine.calculate(AttendanceInput(40, 50, 85))
        assertEquals(AttendanceStatus.BELOW, t85.status)
        assertEquals(17, t85.mustAttendToRecover)
    }

    @Test
    fun `threshold 100 marks recovery impossible when below and safe when equal`() {
        val below = AttendanceEngine.calculate(AttendanceInput(9, 10, 100))
        assertEquals(AttendanceStatus.BELOW, below.status)
        assertTrue(below.recoveryImpossible)
        assertEquals(0, below.canMiss)
        assertEquals(0, below.mustAttendToRecover)

        val exact = AttendanceEngine.calculate(AttendanceInput(10, 10, 100))
        assertEquals(AttendanceStatus.SAFE, exact.status)
        assertFalse(exact.recoveryImpossible)
        assertEquals(0, exact.canMiss)
    }

    @Test
    fun `invalid inputs return INVALID status with error message`() {
        val over = AttendanceEngine.calculate(AttendanceInput(11, 10, 75))
        assertEquals(AttendanceStatus.INVALID, over.status)
        assertNotNull(over.errorMessage)

        val negA = AttendanceEngine.calculate(AttendanceInput(-1, 10, 75))
        assertEquals(AttendanceStatus.INVALID, negA.status)

        val negC = AttendanceEngine.calculate(AttendanceInput(0, -5, 75))
        assertEquals(AttendanceStatus.INVALID, negC.status)

        val zeroP = AttendanceEngine.calculate(AttendanceInput(5, 10, 0))
        assertEquals(AttendanceStatus.INVALID, zeroP.status)

        val overP = AttendanceEngine.calculate(AttendanceInput(5, 10, 101))
        assertEquals(AttendanceStatus.INVALID, overP.status)
    }

    @Test
    fun `large numbers do not overflow 32-bit Int`() {
        // 25_000_000 / 30_000_000 at 75%:
        // 100L * 25_000_000 = 2_500_000_000 (> Int.MAX_VALUE)
        // 75L * 30_000_000 = 2_250_000_000 (> Int.MAX_VALUE)
        // diff = 250_000_000; / 75 = 3_333_333
        val large = AttendanceEngine.calculate(AttendanceInput(25_000_000, 30_000_000, 75))
        assertEquals(AttendanceStatus.SAFE, large.status)
        assertEquals(3_333_333, large.canMiss)
    }

    @Test
    fun `simulateAbsences and simulateAttendance update status accurately`() {
        val base = AttendanceInput(44, 50, 75)
        // Missing 8 keeps SAFE (44/58 = 75.86%)
        val miss8 = AttendanceEngine.simulateAbsences(base, 8)
        assertEquals(AttendanceStatus.SAFE, miss8.status)
        assertEquals(0, miss8.canMiss)

        // Missing 9 drops BELOW (44/59 = 74.57%)
        val miss9 = AttendanceEngine.simulateAbsences(base, 9)
        assertEquals(AttendanceStatus.BELOW, miss9.status)
        assertEquals(1, miss9.mustAttendToRecover)

        // Recovering from 30/50 by attending 30 reaches SAFE (60/80 = 75%)
        val recover = AttendanceEngine.simulateAttendance(AttendanceInput(30, 50, 75), 30)
        assertEquals(AttendanceStatus.SAFE, recover.status)
        assertEquals(0, recover.canMiss)

        // Negative simulation deltas return INVALID
        assertEquals(AttendanceStatus.INVALID, AttendanceEngine.simulateAbsences(base, -1).status)
        assertEquals(AttendanceStatus.INVALID, AttendanceEngine.simulateAttendance(base, -1).status)
    }

    @Test
    fun `calculateAll processes arbitrary dynamic collections independently without combining`() {
        assertTrue(AttendanceEngine.calculateAll(emptyList()).isEmpty())

        val dynamicCourses = (1..25).map { idx ->
            val code = "DYN-${100 + idx}"
            val conducted = idx * 4
            val attended = if (idx % 3 == 0) conducted / 2 else conducted
            CourseAttendanceRecord(
                courseCode = code,
                input = AttendanceInput(attended = attended, conducted = conducted, thresholdPercent = 75),
            )
        } + CourseAttendanceRecord(
            courseCode = "NEW-ELECTIVE-X",
            input = AttendanceInput(attended = 0, conducted = 0, thresholdPercent = 75),
        )

        val results = AttendanceEngine.calculateAll(dynamicCourses)
        assertEquals(dynamicCourses.size, results.size)

        results.forEachIndexed { idx, item ->
            val expected = AttendanceEngine.calculate(dynamicCourses[idx].input)
            assertEquals(dynamicCourses[idx].courseCode, item.courseCode)
            assertEquals(expected, item.result)
        }
    }
}

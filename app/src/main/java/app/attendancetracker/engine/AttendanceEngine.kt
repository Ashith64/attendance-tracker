package app.attendancetracker.engine

/**
 * Outcome classification for a single course's attendance calculation.
 */
enum class AttendanceStatus {
    SAFE,
    BELOW,
    NO_DATA,
    INVALID,
}

/**
 * Pure numeric attendance inputs for a single course.
 * Independent of course codes, names, or semester identifiers.
 */
data class AttendanceInput(
    val attended: Int,
    val conducted: Int,
    val thresholdPercent: Int = 75,
)

/**
 * Generic wrapper associating any dynamically discovered course identifier with its [AttendanceInput].
 */
data class CourseAttendanceRecord(
    val courseCode: String,
    val input: AttendanceInput,
)

/**
 * Result of evaluating a single course's attendance using exact integer arithmetic.
 * [percentage] is for display only; all status/margin decisions use integer math.
 */
data class AttendanceResult(
    val percentage: Double,
    val status: AttendanceStatus,
    val canMiss: Int,
    val mustAttendToRecover: Int,
    val recoveryImpossible: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Independent attendance evaluation result paired with its dynamically discovered [courseCode].
 */
data class CourseAttendanceResult(
    val courseCode: String,
    val result: AttendanceResult,
)

/**
 * Pure Kotlin attendance engine.
 * Evaluates each course independently using integer math and never aggregates across subjects.
 */
object AttendanceEngine {

    private fun invalid(reason: String): AttendanceResult = AttendanceResult(
        percentage = 0.0,
        status = AttendanceStatus.INVALID,
        canMiss = 0,
        mustAttendToRecover = 0,
        recoveryImpossible = false,
        errorMessage = reason,
    )

    /**
     * Evaluates attendance for a single course.
     */
    fun calculate(input: AttendanceInput): AttendanceResult {
        val attended = input.attended
        val conducted = input.conducted
        val threshold = input.thresholdPercent

        if (threshold !in 1..100) {
            return invalid("Threshold must be between 1 and 100, got $threshold")
        }
        if (attended < 0 || conducted < 0) {
            return invalid("Attendance counts cannot be negative: attended=$attended, conducted=$conducted")
        }
        if (attended > conducted) {
            return invalid("Attended ($attended) cannot exceed conducted ($conducted)")
        }
        if (conducted == 0) {
            return AttendanceResult(
                percentage = 0.0,
                status = AttendanceStatus.NO_DATA,
                canMiss = 0,
                mustAttendToRecover = 0,
            )
        }

        val a = attended.toLong()
        val c = conducted.toLong()
        val p = threshold.toLong()
        val percentage = (a.toDouble() * 100.0) / c.toDouble()

        val lhs = 100L * a
        val rhs = p * c

        return if (lhs >= rhs) {
            val canMiss = ((lhs - rhs) / p).toInt()
            AttendanceResult(
                percentage = percentage,
                status = AttendanceStatus.SAFE,
                canMiss = canMiss,
                mustAttendToRecover = 0,
            )
        } else if (p == 100L) {
            AttendanceResult(
                percentage = percentage,
                status = AttendanceStatus.BELOW,
                canMiss = 0,
                mustAttendToRecover = 0,
                recoveryImpossible = true,
            )
        } else {
            val deficit = rhs - lhs
            val denom = 100L - p
            // Ceiling division for positive integers: (deficit + denom - 1) / denom
            val mustAttend = ((deficit + denom - 1L) / denom).toInt()
            AttendanceResult(
                percentage = percentage,
                status = AttendanceStatus.BELOW,
                canMiss = 0,
                mustAttendToRecover = mustAttend,
            )
        }
    }

    /**
     * Evaluates an arbitrary collection of dynamically discovered courses independently.
     * Never combines or aggregates attendance across courses.
     */
    fun calculateAll(records: List<CourseAttendanceRecord>): List<CourseAttendanceResult> =
        records.map { record ->
            CourseAttendanceResult(
                courseCode = record.courseCode,
                result = calculate(record.input),
            )
        }

    /**
     * Simulates missing [missedFuture] upcoming classes for a single course.
     */
    fun simulateAbsences(input: AttendanceInput, missedFuture: Int): AttendanceResult {
        if (missedFuture < 0) {
            return invalid("missedFuture cannot be negative: $missedFuture")
        }
        val base = calculate(input)
        if (base.status == AttendanceStatus.INVALID) return base

        val newConducted = input.conducted.toLong() + missedFuture.toLong()
        if (newConducted > Int.MAX_VALUE) {
            return invalid("Simulated conducted count overflows Int")
        }
        return calculate(input.copy(conducted = newConducted.toInt()))
    }

    /**
     * Simulates attending [attendedFuture] upcoming classes for a single course.
     */
    fun simulateAttendance(input: AttendanceInput, attendedFuture: Int): AttendanceResult {
        if (attendedFuture < 0) {
            return invalid("attendedFuture cannot be negative: $attendedFuture")
        }
        val base = calculate(input)
        if (base.status == AttendanceStatus.INVALID) return base

        val newAttended = input.attended.toLong() + attendedFuture.toLong()
        val newConducted = input.conducted.toLong() + attendedFuture.toLong()
        if (newAttended > Int.MAX_VALUE || newConducted > Int.MAX_VALUE) {
            return invalid("Simulated attendance count overflows Int")
        }
        return calculate(
            input.copy(
                attended = newAttended.toInt(),
                conducted = newConducted.toInt(),
            ),
        )
    }
}

package app.attendancetracker.ui

import app.attendancetracker.data.CourseAttendanceUiModel
import app.attendancetracker.data.SnapshotEntity
import app.attendancetracker.data.SnapshotCategory
import app.attendancetracker.data.SnapshotStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateMapperTest {

    private val nowMs = 1_760_000_000_000L

    @Test
    fun mapCourseCard_zeroConducted_neverShowsZeroPercent() {
        val raw = CourseAttendanceUiModel(
            courseCode = "22CSL55",
            courseName = "Systems Lab",
            term = "sem-5",
            attended = 0,
            conducted = 0,
            engineResult = null,
            lastSyncedAt = nowMs - 3_600_000L,
            isStale = false,
        )

        val card = UiStateMapper.mapCourseCard(raw, thresholdPercent = 75, nowMs = nowMs)

        assertEquals(CourseHealth.NO_DATA, card.health)
        assertEquals("No data", card.percentageText)
        assertEquals("No data", card.statusLineText)
        assertEquals("updated 1 h ago", card.updatedAgoText)
    }

    @Test
    fun mapCourseCard_thresholdChange_recomputesHealthAndGuidance() {
        val raw = CourseAttendanceUiModel(
            courseCode = "22CS502",
            courseName = "Computer Networks",
            term = "sem-5",
            attended = 29,
            conducted = 36,
            engineResult = null,
            lastSyncedAt = nowMs - 2 * 3_600_000L,
            isStale = false,
        )

        val at75 = UiStateMapper.mapCourseCard(raw, thresholdPercent = 75, nowMs = nowMs)
        assertEquals(CourseHealth.SAFE, at75.health)
        assertEquals("Above 75%", at75.statusLineText)
        assertEquals("can miss 2", at75.guidanceText)
        assertEquals(7, at75.missed)

        val at85 = UiStateMapper.mapCourseCard(raw, thresholdPercent = 85, nowMs = nowMs)
        assertEquals(CourseHealth.BELOW, at85.health)
        assertEquals("Below 85%", at85.statusLineText)
        assertEquals("attend 11 more", at85.guidanceText)
    }

    @Test
    fun simulateBunk_missAndAttend_computesExactLiveProjection() {
        // 34 / 40 = 85%. Missing 6 -> 34 / 46 = 73.9% -> Below 75%
        val missSim = UiStateMapper.simulateBunk(
            attended = 34,
            conducted = 40,
            thresholdPercent = 75,
            mode = BunkMode.MISS,
            count = 6,
        )
        assertEquals(34, missSim.projectedAttended)
        assertEquals(46, missSim.projectedConducted)
        assertEquals("74%", missSim.projectedPercentageText)
        assertEquals(CourseHealth.BELOW, missSim.projectedHealth)
        assertTrue(missSim.projectedStatusText.contains("attend 2 more"))

        // 20 / 30 = 67%. Attending 10 -> 30 / 40 = 75% -> Safe
        val attendSim = UiStateMapper.simulateBunk(
            attended = 20,
            conducted = 30,
            thresholdPercent = 75,
            mode = BunkMode.ATTEND,
            count = 10,
        )
        assertEquals(30, attendSim.projectedAttended)
        assertEquals(40, attendSim.projectedConducted)
        assertEquals("75%", attendSim.projectedPercentageText)
        assertEquals(CourseHealth.SAFE, attendSim.projectedHealth)
        assertTrue(attendSim.projectedStatusText.contains("On target"))
    }

    @Test
    fun simulateBunk_zeroConducted_returnsNoDataNeverZeroPercent() {
        val simMiss = UiStateMapper.simulateBunk(
            attended = 0,
            conducted = 0,
            thresholdPercent = 75,
            mode = BunkMode.MISS,
            count = 1,
        )
        assertEquals("No data", simMiss.projectedPercentageText)
        assertEquals(CourseHealth.NO_DATA, simMiss.projectedHealth)
        assertEquals("No classes conducted", simMiss.projectedStatusText)

        val simAttend = UiStateMapper.simulateBunk(
            attended = 0,
            conducted = 0,
            thresholdPercent = 75,
            mode = BunkMode.ATTEND,
            count = 1,
        )
        assertEquals("No data", simAttend.projectedPercentageText)
        assertEquals(CourseHealth.NO_DATA, simAttend.projectedHealth)
        assertEquals("No classes conducted", simAttend.projectedStatusText)
    }

    @Test
    fun mapAttendanceHistory_handlesValidAndFailedSnapshots() {
        val snapshots = listOf(
            SnapshotEntity(
                id = 1L,
                courseCode = "22CS501",
                category = SnapshotCategory.ATTENDANCE,
                payloadJson = """{"attended":34,"conducted":40,"stillToGo":0,"unit":"classes"}""",
                term = "sem-5",
                fetchedAt = nowMs - 2 * 3_600_000L,
                status = SnapshotStatus.OK,
                sourceUrl = "https://parents.nie.ac.in/",
            ),
            SnapshotEntity(
                id = 2L,
                courseCode = "22CS501",
                category = SnapshotCategory.ATTENDANCE,
                payloadJson = null,
                term = "sem-5",
                fetchedAt = nowMs - 30 * 60_000L,
                status = SnapshotStatus.FAILED,
                errorMessage = "Timeout",
                sourceUrl = "https://parents.nie.ac.in/",
            ),
        )

        val mapped = UiStateMapper.mapAttendanceHistory(snapshots, nowMs)
        assertEquals(2, mapped.size)
        assertEquals("85%", mapped[0].percentageText)
        assertEquals("2 h ago", mapped[0].timestampLabel)
        assertEquals("No data", mapped[1].percentageText)
        assertEquals("30 m ago", mapped[1].timestampLabel)
        assertNotNull(mapped[1].errorMessage)
    }
}

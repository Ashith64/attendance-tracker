package app.attendancetracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRepositoryTest {

    @Test
    fun fakeRepository_containsSixCourses_withBelowNoDataAndStale() {
        val repo = FakeRepository(FakeRepository.DEFAULT_NOW_MS)
        val state = repo.getHomeUiState(thresholdPercent = 75)

        assertEquals(6, state.courses.size)

        val belowCourses = state.courses.filter { it.health == CourseHealth.BELOW }
        assertEquals(1, belowCourses.size)
        assertTrue(belowCourses.first().guidanceText.startsWith("attend "))

        val noDataCourses = state.courses.filter { it.health == CourseHealth.NO_DATA }
        assertEquals(1, noDataCourses.size)
        assertEquals("No data", noDataCourses.first().percentageText)
        assertNull(noDataCourses.first().missed)

        val staleCourses = state.courses.filter { it.isStale }
        assertEquals(1, staleCourses.size)
        assertEquals("updated 6 h ago", staleCourses.first().updatedAgoText)
    }
}

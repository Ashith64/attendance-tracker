package app.attendancetracker.ui

import app.attendancetracker.data.CourseAttendanceUiModel
import app.attendancetracker.data.SnapshotStatus
import app.attendancetracker.engine.AttendanceEngine
import app.attendancetracker.engine.AttendanceInput
import app.attendancetracker.parser.CieAssessment
import app.attendancetracker.parser.CieData
import app.attendancetracker.parser.LessonPlanData
import app.attendancetracker.parser.LessonPlanEntry

/**
 * In-memory fake repository for UI development, Compose previews, and screenshot-free tests.
 * Contains 6 realistic courses:
 * - 3 safe courses above 75%
 * - 1 course below 75%
 * - 1 course with no data (0 conducted)
 * - 1 stale course (latest fetch failed, showing last good snapshot)
 *
 * Never computes or exposes any combined or overall attendance.
 */
class FakeRepository(
    private val nowMs: Long = DEFAULT_NOW_MS,
) {
    companion object {
        const val DEFAULT_NOW_MS = 1_760_000_000_000L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private const val TERM_ID = "sem-5-2026"
    }

    private data class SeedCourse(
        val code: String,
        val name: String,
        val attended: Int?,
        val conducted: Int?,
        val updatedHoursAgo: Long?,
        val isStale: Boolean = false,
        val errorMessage: String? = null,
        val history: List<AttendanceHistoryEntry>,
        val cie: CieData?,
        val lessonPlan: LessonPlanData?,
    )

    private val seedCourses: List<SeedCourse> = listOf(
        SeedCourse(
            code = "22CS501",
            name = "Software Engineering",
            attended = 34,
            conducted = 40,
            updatedHoursAgo = 2L,
            history = listOf(
                AttendanceHistoryEntry(
                    id = 101L,
                    fetchedAt = nowMs - 2 * HOUR_MS,
                    timestampLabel = "2 h ago",
                    attended = 34,
                    conducted = 40,
                    percentageText = "85%",
                    status = SnapshotStatus.OK,
                ),
                AttendanceHistoryEntry(
                    id = 102L,
                    fetchedAt = nowMs - 1 * DAY_MS,
                    timestampLabel = "1 d ago",
                    attended = 32,
                    conducted = 38,
                    percentageText = "84%",
                    status = SnapshotStatus.OK,
                ),
                AttendanceHistoryEntry(
                    id = 103L,
                    fetchedAt = nowMs - 3 * DAY_MS,
                    timestampLabel = "3 d ago",
                    attended = 29,
                    conducted = 35,
                    percentageText = "83%",
                    status = SnapshotStatus.OK,
                ),
            ),
            cie = CieData(
                courseCode = "22CS501",
                courseName = "Software Engineering",
                currentCie = 41.0,
                attendancePercent = 85,
                assessments = listOf(
                    CieAssessment("Test 1", 17.5, 20.0, 14.2),
                    CieAssessment("Test 2", 16.0, 20.0, 13.8),
                    CieAssessment("Assignment 1", 7.5, 10.0, 8.1),
                ),
            ),
            lessonPlan = LessonPlanData(
                courseCode = "22CS501",
                courseName = "Software Engineering",
                statusText = "12 topics covered",
                entries = listOf(
                    LessonPlanEntry("Unit 1: SDLC & Agile Models"),
                    LessonPlanEntry("Unit 2: Requirements Engineering & SRS"),
                    LessonPlanEntry("Unit 3: Architectural & Component Design"),
                    LessonPlanEntry("Unit 4: Testing Strategies & Verification"),
                ),
            ),
        ),
        SeedCourse(
            code = "22CS502",
            name = "Computer Networks",
            attended = 29,
            conducted = 36,
            updatedHoursAgo = 2L,
            history = listOf(
                AttendanceHistoryEntry(
                    id = 201L,
                    fetchedAt = nowMs - 2 * HOUR_MS,
                    timestampLabel = "2 h ago",
                    attended = 29,
                    conducted = 36,
                    percentageText = "81%",
                    status = SnapshotStatus.OK,
                ),
                AttendanceHistoryEntry(
                    id = 202L,
                    fetchedAt = nowMs - 2 * DAY_MS,
                    timestampLabel = "2 d ago",
                    attended = 27,
                    conducted = 34,
                    percentageText = "79%",
                    status = SnapshotStatus.OK,
                ),
            ),
            cie = CieData(
                courseCode = "22CS502",
                courseName = "Computer Networks",
                currentCie = 36.5,
                attendancePercent = 81,
                assessments = listOf(
                    CieAssessment("Test 1", 15.0, 20.0, 13.5),
                    CieAssessment("Quiz 1", 8.5, 10.0, 7.2),
                    CieAssessment("Lab Evaluation", 13.0, 20.0, 14.0),
                ),
            ),
            lessonPlan = LessonPlanData(
                courseCode = "22CS502",
                courseName = "Computer Networks",
                statusText = "10 topics covered",
                entries = listOf(
                    LessonPlanEntry("Module 1: Physical & Data Link Layers"),
                    LessonPlanEntry("Module 2: IP Addressing & Routing"),
                    LessonPlanEntry("Module 3: TCP Congestion Control"),
                ),
            ),
        ),
        // Below 75% course
        SeedCourse(
            code = "22CS503",
            name = "Database Management Systems",
            attended = 20,
            conducted = 30,
            updatedHoursAgo = 2L,
            history = listOf(
                AttendanceHistoryEntry(
                    id = 301L,
                    fetchedAt = nowMs - 2 * HOUR_MS,
                    timestampLabel = "2 h ago",
                    attended = 20,
                    conducted = 30,
                    percentageText = "67%",
                    status = SnapshotStatus.OK,
                ),
                AttendanceHistoryEntry(
                    id = 302L,
                    fetchedAt = nowMs - 1 * DAY_MS,
                    timestampLabel = "1 d ago",
                    attended = 20,
                    conducted = 28,
                    percentageText = "71%",
                    status = SnapshotStatus.OK,
                ),
                AttendanceHistoryEntry(
                    id = 303L,
                    fetchedAt = nowMs - 4 * DAY_MS,
                    timestampLabel = "4 d ago",
                    attended = 19,
                    conducted = 25,
                    percentageText = "76%",
                    status = SnapshotStatus.OK,
                ),
            ),
            cie = CieData(
                courseCode = "22CS503",
                courseName = "Database Management Systems",
                currentCie = 33.0,
                attendancePercent = 67,
                assessments = listOf(
                    CieAssessment("Test 1", 13.0, 20.0, 14.1),
                    CieAssessment("Test 2", 12.0, 20.0, 13.4),
                    CieAssessment("Mini Project", 8.0, 10.0, 7.8),
                ),
            ),
            lessonPlan = LessonPlanData(
                courseCode = "22CS503",
                courseName = "Database Management Systems",
                statusText = "9 topics covered",
                entries = listOf(
                    LessonPlanEntry("ER Modeling & Relational Algebra"),
                    LessonPlanEntry("SQL Queries & Joins"),
                    LessonPlanEntry("Normalization & Transaction Concurrency"),
                ),
            ),
        ),
        SeedCourse(
            code = "22CS504",
            name = "Theory of Computation",
            attended = 28,
            conducted = 34,
            updatedHoursAgo = 2L,
            history = listOf(
                AttendanceHistoryEntry(
                    id = 401L,
                    fetchedAt = nowMs - 2 * HOUR_MS,
                    timestampLabel = "2 h ago",
                    attended = 28,
                    conducted = 34,
                    percentageText = "82%",
                    status = SnapshotStatus.OK,
                ),
            ),
            cie = CieData(
                courseCode = "22CS504",
                courseName = "Theory of Computation",
                currentCie = 38.0,
                attendancePercent = 82,
                assessments = listOf(
                    CieAssessment("Test 1", 18.0, 20.0, 12.9),
                    CieAssessment("Quiz 1", 9.0, 10.0, 6.8),
                    CieAssessment("Quiz 2", 11.0, 20.0, 11.5),
                ),
            ),
            lessonPlan = LessonPlanData(
                courseCode = "22CS504",
                courseName = "Theory of Computation",
                statusText = "11 topics covered",
                entries = listOf(
                    LessonPlanEntry("Deterministic & Non-Deterministic Finite Automata"),
                    LessonPlanEntry("Context-Free Grammars & Pushdown Automata"),
                    LessonPlanEntry("Turing Machines & Decidability"),
                ),
            ),
        ),
        // No data course (0 classes conducted -> must show 'No data', never 0%)
        SeedCourse(
            code = "22CSL55",
            name = "Systems Programming Lab",
            attended = 0,
            conducted = 0,
            updatedHoursAgo = 2L,
            history = emptyList(),
            cie = null,
            lessonPlan = LessonPlanData(
                courseCode = "22CSL55",
                courseName = "Systems Programming Lab",
                statusText = "Lesson plan not published for this course.",
                entries = emptyList(),
            ),
        ),
        // Stale course (latest fetch failed, keeping last good snapshot)
        SeedCourse(
            code = "22HS506",
            name = "Engineering Economics & Ethics",
            attended = 19,
            conducted = 24,
            updatedHoursAgo = 6L,
            isStale = true,
            errorMessage = "Request timed out — showing last saved data",
            history = listOf(
                AttendanceHistoryEntry(
                    id = 601L,
                    fetchedAt = nowMs - 2 * HOUR_MS,
                    timestampLabel = "2 h ago",
                    attended = null,
                    conducted = null,
                    percentageText = "Failed",
                    status = SnapshotStatus.FAILED,
                    errorMessage = "Request timed out",
                ),
                AttendanceHistoryEntry(
                    id = 602L,
                    fetchedAt = nowMs - 6 * HOUR_MS,
                    timestampLabel = "6 h ago",
                    attended = 19,
                    conducted = 24,
                    percentageText = "79%",
                    status = SnapshotStatus.OK,
                ),
            ),
            cie = CieData(
                courseCode = "22HS506",
                courseName = "Engineering Economics & Ethics",
                currentCie = 22.0,
                attendancePercent = 79,
                assessments = listOf(
                    CieAssessment("Test 1", 15.0, 20.0, 14.6),
                    CieAssessment("Seminar", 7.0, 10.0, 7.9),
                ),
            ),
            lessonPlan = LessonPlanData(
                courseCode = "22HS506",
                courseName = "Engineering Economics & Ethics",
                statusText = "6 topics covered",
                entries = listOf(
                    LessonPlanEntry("Time Value of Money & Cash Flow"),
                    LessonPlanEntry("Professional Ethics & Case Studies"),
                ),
            ),
        ),
    )

    fun getRawCourseModels(thresholdPercent: Int = 75): List<CourseAttendanceUiModel> =
        seedCourses.map { seed ->
            val engineResult = if (seed.attended != null && seed.conducted != null) {
                AttendanceEngine.calculate(
                    AttendanceInput(
                        attended = seed.attended,
                        conducted = seed.conducted,
                        thresholdPercent = thresholdPercent,
                    ),
                )
            } else {
                null
            }
            CourseAttendanceUiModel(
                courseCode = seed.code,
                courseName = seed.name,
                term = TERM_ID,
                attended = seed.attended,
                conducted = seed.conducted,
                engineResult = engineResult,
                lastSyncedAt = seed.updatedHoursAgo?.let { nowMs - it * HOUR_MS },
                isStale = seed.isStale,
                isSuspect = false,
                latestErrorMessage = seed.errorMessage,
            )
        }

    fun getHomeUiState(
        thresholdPercent: Int = 75,
        screenState: DashboardScreenState = DashboardScreenState.Content,
        isSyncing: Boolean = false,
        layoutChangedWarning: Boolean = false,
    ): HomeUiState {
        val cards = if (screenState is DashboardScreenState.Empty) {
            emptyList()
        } else {
            getRawCourseModels(thresholdPercent).map { model ->
                UiStateMapper.mapCourseCard(
                    model = model,
                    thresholdPercent = thresholdPercent,
                    nowMs = nowMs,
                )
            }
        }
        return HomeUiState(
            studentName = "Aarav Sharma",
            termLabel = "Semester 5 · 2026-27",
            lastSyncText = if (isSyncing) "Syncing courses..." else "Synced 2 h ago",
            isSyncing = isSyncing,
            syncProgress = if (isSyncing) 0.55f else null,
            courses = cards,
            screenState = screenState,
            thresholdPercent = thresholdPercent,
            layoutChangedWarning = layoutChangedWarning,
        )
    }

    fun getCourseDetail(
        courseCode: String,
        thresholdPercent: Int = 75,
    ): CourseDetailUiModel? {
        val seed = seedCourses.find { it.code == courseCode } ?: return null
        val raw = getRawCourseModels(thresholdPercent).first { it.courseCode == courseCode }
        val card = UiStateMapper.mapCourseCard(
            model = raw,
            thresholdPercent = thresholdPercent,
            nowMs = nowMs,
        )
        return CourseDetailUiModel(
            card = card,
            attendanceHistory = seed.history,
            cieData = seed.cie,
            lessonPlan = seed.lessonPlan,
        )
    }
}

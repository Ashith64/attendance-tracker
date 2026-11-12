package app.attendancetracker.parser

import kotlinx.serialization.Serializable

@Serializable
data class DiscoveredCourse(
    val code: String,
    val name: String,
    val lessonPlanUrl: String?,
    val attendanceUrl: String?,
    val cieUrl: String?,
    val quickAttendancePercent: Int? = null,
    val quickCieScore: Int? = null,
)

@Serializable
data class CourseListData(
    val semId: String,
    val semesterNumber: Int,
    val termLabel: String,
    val courses: List<DiscoveredCourse>,
)

@Serializable
data class AttendanceSlotRow(
    val slNo: String,
    val date: String,
    val time: String,
    val status: String,
)

@Serializable
data class AttendanceData(
    val courseCode: String,
    val courseName: String,
    val presentCount: Int,
    val absentCount: Int,
    val stillToGoCount: Int,
    val presentRows: List<AttendanceSlotRow>,
    val absentRows: List<AttendanceSlotRow>,
) {
    val conductedCount: Int get() = presentCount + absentCount
}

@Serializable
data class CieAssessment(
    val label: String,
    val obtainedMarks: Double,
    val maxMarks: Double,
    val classAverage: Double,
    val tableCellValue: String? = null,
    val hasTableDiscrepancy: Boolean = false,
)

@Serializable
data class CieData(
    val courseCode: String,
    val courseName: String,
    val currentCie: Double?,
    val attendancePercent: Int?,
    val assessments: List<CieAssessment>,
    val hasTableChartDiscrepancy: Boolean = false,
    val discrepancyNote: String? = null,
)

@Serializable
data class LessonPlanEntry(
    val title: String,
    val url: String? = null,
)

@Serializable
data class LessonPlanData(
    val courseCode: String,
    val courseName: String,
    val statusText: String,
    val entries: List<LessonPlanEntry> = emptyList(),
)

@Serializable
data class ExamCourseGrade(
    val courseCode: String,
    val courseName: String,
    val creditsRegistered: Double,
    val creditsEarned: Double,
    val gradePoints: Double,
    val grade: String,
)

@Serializable
data class ExamSemesterRecord(
    val headerLabel: String,
    val semesterNumber: Int?,
    val creditsRegistered: Double?,
    val creditsEarned: Double?,
    val sgpa: Double?,
    val courses: List<ExamCourseGrade>,
)

@Serializable
data class ExamHistoryData(
    val semesters: List<ExamSemesterRecord>,
)

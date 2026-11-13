package app.attendancetracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class SnapshotCategory {
    ATTENDANCE,
    CIE,
    LESSON_PLAN,
}

enum class SnapshotStatus {
    OK,
    SUSPECT,
    FAILED,
}

/**
 * Represents a single academic semester/term discovered dynamically from the portal.
 * Older terms remain in the database read-only when a new [semId] appears.
 */
@Entity(tableName = "terms")
data class TermEntity(
    @PrimaryKey
    val semId: String,
    val semesterNumber: Int,
    val label: String,
    val firstSeenAt: Long,
    val isLatest: Boolean,
)

/**
 * Represents a dynamically discovered course within a specific [term] ([TermEntity.semId]).
 * Composite primary key (code, term) allows the same course code to exist across multiple terms.
 */
@Entity(
    tableName = "courses",
    primaryKeys = ["code", "term"],
    foreignKeys = [
        ForeignKey(
            entity = TermEntity::class,
            parentColumns = ["semId"],
            childColumns = ["term"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["term"]),
    ],
)
data class CourseEntity(
    val code: String,
    val name: String,
    val term: String,
    val lastSeenAt: Long,
)

/**
 * Immutable fetch attempt snapshot for a course, category, and term.
 * Never stores credentials, cookies, passwords, DOB, or verification digits.
 */
@Entity(
    tableName = "snapshots",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["code", "term"],
            childColumns = ["courseCode", "term"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TermEntity::class,
            parentColumns = ["semId"],
            childColumns = ["term"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["courseCode", "term", "category", "fetchedAt"]),
        Index(value = ["term"]),
    ],
)
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val courseCode: String,
    val category: SnapshotCategory,
    val payloadJson: String?,
    val term: String,
    val fetchedAt: Long,
    val status: SnapshotStatus,
    val errorMessage: String? = null,
    val sourceUrl: String,
)

@Serializable
data class AttendanceRecordItem(
    val date: String,
    val slot: String,
    val status: String,
    val dateMillis: Long = 0L,
    val rawDateText: String = "",
)

@Serializable
data class AttendancePayload(
    val attended: Int,
    val conducted: Int,
    val stillToGo: Int = 0,
    val unit: String = "classes",
    val records: List<AttendanceRecordItem> = emptyList(),
)

/**
 * Individual class session attendance record (Present or Absent).
 * Scraped from portal tables cn-attend-list1 (Present) and cn-attend-list2 (Absent).
 * Scoped by courseCode and term to maintain semester history.
 */
@Entity(
    tableName = "attendance_records",
    primaryKeys = ["courseCode", "term", "sessionKey"],
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["code", "term"],
            childColumns = ["courseCode", "term"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["courseCode", "term"]),
        Index(value = ["dateMillis"]),
    ],
)
data class AttendanceRecordEntity(
    val courseCode: String,
    val term: String,
    val sessionKey: String,
    val date: String,
    val slot: String,
    val status: String,
    val dateMillis: Long,
    val rawDateText: String,
    val fetchedAt: Long,
)

/**
 * Term-level exam history record stored separately from attendance and CIE snapshots.
 */
@Entity(tableName = "exam_history")
data class ExamHistoryEntity(
    @PrimaryKey
    val headerLabel: String,
    val semesterNumber: Int?,
    val creditsRegistered: Double?,
    val creditsEarned: Double?,
    val sgpa: Double?,
    val coursesJson: String,
    val fetchedAt: Long,
    val sourceUrl: String,
)

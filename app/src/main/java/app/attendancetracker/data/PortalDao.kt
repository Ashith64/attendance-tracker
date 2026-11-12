package app.attendancetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PortalDao {

    @Query("SELECT * FROM terms ORDER BY firstSeenAt DESC")
    fun allTerms(): Flow<List<TermEntity>>

    @Query("SELECT * FROM terms WHERE isLatest = 1 LIMIT 1")
    fun latestTerm(): Flow<TermEntity?>

    @Query("SELECT * FROM terms WHERE semId = :semId LIMIT 1")
    suspend fun getTermById(semId: String): TermEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTerm(term: TermEntity)

    @Query("UPDATE terms SET isLatest = 0 WHERE semId != :latestSemId")
    suspend fun clearLatestExcept(latestSemId: String)

    @Transaction
    suspend fun upsertDiscoveredTerm(
        semId: String,
        semesterNumber: Int,
        label: String,
        now: Long,
    ) {
        val existing = getTermById(semId)
        if (existing == null) {
            clearLatestExcept(semId)
            upsertTerm(
                TermEntity(
                    semId = semId,
                    semesterNumber = semesterNumber,
                    label = label,
                    firstSeenAt = now,
                    isLatest = true,
                ),
            )
        } else {
            // Preserve firstSeenAt and keep existing isLatest flag so older terms stay read-only.
            upsertTerm(
                existing.copy(
                    semesterNumber = if (semesterNumber > 0) semesterNumber else existing.semesterNumber,
                    label = label.ifBlank { existing.label },
                ),
            )
        }
    }

    @Query("SELECT * FROM courses ORDER BY term DESC, code ASC")
    fun allCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE term = :term ORDER BY code ASC")
    fun coursesForTerm(term: String): Flow<List<CourseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCourses(courses: List<CourseEntity>)

    @Query(
        """
        SELECT * FROM snapshots
        WHERE courseCode = :courseCode AND category = :category AND term = :term AND status = 'OK'
        ORDER BY fetchedAt DESC, id DESC
        LIMIT 1
        """,
    )
    fun latestOkSnapshot(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
    ): Flow<SnapshotEntity?>

    @Query(
        """
        SELECT status FROM snapshots
        WHERE courseCode = :courseCode AND category = :category AND term = :term
        ORDER BY fetchedAt DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun latestAttemptStatus(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
    ): SnapshotStatus?

    @Query(
        """
        SELECT * FROM snapshots
        WHERE courseCode = :courseCode AND category = :category AND term = :term
        ORDER BY fetchedAt DESC, id DESC
        LIMIT 1
        """,
    )
    fun latestAttempt(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
    ): Flow<SnapshotEntity?>

    @Query(
        """
        SELECT * FROM snapshots
        WHERE courseCode = :courseCode AND category = :category AND term = :term
        ORDER BY fetchedAt DESC, id DESC
        """,
    )
    fun history(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
    ): Flow<List<SnapshotEntity>>

    @Insert
    suspend fun insertSnapshot(snapshot: SnapshotEntity): Long

    @Query(
        """
        DELETE FROM snapshots
        WHERE courseCode = :courseCode AND category = :category AND term = :term
        AND id NOT IN (
            SELECT id FROM snapshots
            WHERE courseCode = :courseCode AND category = :category AND term = :term
            ORDER BY fetchedAt DESC, id DESC
            LIMIT :keepCount
        )
        """,
    )
    suspend fun pruneSnapshots(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
        keepCount: Int = 200,
    )

    @Query(
        """
        SELECT * FROM snapshots
        WHERE term = :term
        ORDER BY fetchedAt DESC, id DESC
        """,
    )
    fun snapshotsForTerm(term: String): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM exam_history ORDER BY semesterNumber ASC, headerLabel ASC")
    fun allExamHistory(): Flow<List<ExamHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExamHistory(records: List<ExamHistoryEntity>)
}

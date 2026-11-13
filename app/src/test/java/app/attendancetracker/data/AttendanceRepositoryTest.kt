package app.attendancetracker.data

import app.attendancetracker.engine.AttendanceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePortalDao : PortalDao {
    private val termsFlow = MutableStateFlow<List<TermEntity>>(emptyList())
    private val coursesFlow = MutableStateFlow<List<CourseEntity>>(emptyList())
    private val snapshotsFlow = MutableStateFlow<List<SnapshotEntity>>(emptyList())
    private val examHistoryFlow = MutableStateFlow<List<ExamHistoryEntity>>(emptyList())
    private val recordsFlow = MutableStateFlow<List<AttendanceRecordEntity>>(emptyList())
    private var nextId = 1L

    override fun allTerms(): Flow<List<TermEntity>> =
        termsFlow.map { list -> list.sortedByDescending { it.firstSeenAt } }

    override fun latestTerm(): Flow<TermEntity?> =
        termsFlow.map { list -> list.firstOrNull { it.isLatest } }

    override suspend fun getTermById(semId: String): TermEntity? =
        termsFlow.value.firstOrNull { it.semId == semId }

    override suspend fun upsertTerm(term: TermEntity) {
        termsFlow.value = termsFlow.value.filterNot { it.semId == term.semId } + term
    }

    override suspend fun clearLatestExcept(latestSemId: String) {
        termsFlow.value = termsFlow.value.map {
            if (it.semId == latestSemId) it else it.copy(isLatest = false)
        }
    }

    override fun allCourses(): Flow<List<CourseEntity>> =
        coursesFlow.map { list ->
            list.sortedWith(compareByDescending<CourseEntity> { it.term }.thenBy { it.code })
        }

    override fun coursesForTerm(term: String): Flow<List<CourseEntity>> =
        coursesFlow.map { list -> list.filter { it.term == term }.sortedBy { it.code } }

    override suspend fun upsertCourses(courses: List<CourseEntity>) {
        val keys = courses.map { it.code to it.term }.toSet()
        coursesFlow.value = coursesFlow.value.filterNot { (it.code to it.term) in keys } + courses
    }

    override fun latestOkSnapshot(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
    ): Flow<SnapshotEntity?> = snapshotsFlow.map { list ->
        list.filter {
            it.courseCode == courseCode &&
                it.category == category &&
                it.term == term &&
                it.status == SnapshotStatus.OK
        }.maxWithOrNull(compareBy<SnapshotEntity> { it.fetchedAt }.thenBy { it.id })
    }

    override suspend fun latestAttemptStatus(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
    ): SnapshotStatus? = snapshotsFlow.value
        .filter { it.courseCode == courseCode && it.category == category && it.term == term }
        .maxWithOrNull(compareBy<SnapshotEntity> { it.fetchedAt }.thenBy { it.id })
        ?.status

    override fun latestAttempt(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
    ): Flow<SnapshotEntity?> = snapshotsFlow.map { list ->
        list.filter { it.courseCode == courseCode && it.category == category && it.term == term }
            .maxWithOrNull(compareBy<SnapshotEntity> { it.fetchedAt }.thenBy { it.id })
    }

    override fun history(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
    ): Flow<List<SnapshotEntity>> = snapshotsFlow.map { list ->
        list.filter { it.courseCode == courseCode && it.category == category && it.term == term }
            .sortedWith(compareByDescending<SnapshotEntity> { it.fetchedAt }.thenByDescending { it.id })
    }

    override fun snapshotsForTerm(term: String): Flow<List<SnapshotEntity>> = snapshotsFlow.map { list ->
        list.filter { it.term == term }
            .sortedWith(compareByDescending<SnapshotEntity> { it.fetchedAt }.thenByDescending { it.id })
    }

    override suspend fun insertSnapshot(snapshot: SnapshotEntity): Long {
        val assigned = snapshot.copy(id = nextId++)
        snapshotsFlow.value = snapshotsFlow.value + assigned
        return assigned.id
    }

    override suspend fun pruneSnapshots(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
        keepCount: Int,
    ) {
        val matching = snapshotsFlow.value
            .filter { it.courseCode == courseCode && it.category == category && it.term == term }
            .sortedWith(compareByDescending<SnapshotEntity> { it.fetchedAt }.thenByDescending { it.id })
        val keepIds = matching.take(keepCount).map { it.id }.toSet()
        val dropIds = matching.drop(keepCount).map { it.id }.toSet()
        if (dropIds.isNotEmpty()) {
            snapshotsFlow.value = snapshotsFlow.value.filterNot { it.id in dropIds && it.id !in keepIds }
        }
    }

    override fun allExamHistory(): Flow<List<ExamHistoryEntity>> =
        examHistoryFlow.map { list ->
            list.sortedWith(compareBy<ExamHistoryEntity> { it.semesterNumber ?: Int.MAX_VALUE }.thenBy { it.headerLabel })
        }

    override suspend fun upsertExamHistory(records: List<ExamHistoryEntity>) {
        val keys = records.map { it.headerLabel }.toSet()
        examHistoryFlow.value = examHistoryFlow.value.filterNot { it.headerLabel in keys } + records
    }

    override fun observeAttendanceRecords(courseCode: String, term: String): Flow<List<AttendanceRecordEntity>> =
        recordsFlow.map { list ->
            list.filter { it.courseCode == courseCode && it.term == term }
                .sortedWith(compareByDescending<AttendanceRecordEntity> { it.dateMillis }.thenByDescending { it.sessionKey })
        }

    override suspend fun upsertAttendanceRecords(records: List<AttendanceRecordEntity>) {
        val keys = records.map { Triple(it.courseCode, it.term, it.sessionKey) }.toSet()
        recordsFlow.value = recordsFlow.value.filterNot { Triple(it.courseCode, it.term, it.sessionKey) in keys } + records
    }

    override suspend fun clearAttendanceRecordsForCourse(courseCode: String, term: String) {
        recordsFlow.value = recordsFlow.value.filterNot { it.courseCode == courseCode && it.term == term }
    }
}

class AttendanceRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun okPayload(attended: Int, conducted: Int): String =
        json.encodeToString(AttendancePayload(attended = attended, conducted = conducted))

    @Test
    fun `course A data never mixes with course B`() = runTest {
        val repo = AttendanceRepository(FakePortalDao())
        repo.recordTermAndCourses(
            semId = "TERM_1",
            semesterNumber = 5,
            label = "Sem 5",
            courses = listOf("SUBJ_A" to "Course Alpha", "SUBJ_B" to "Course Beta"),
            now = 1000L,
        )

        repo.recordSnapshot(
            courseCode = "SUBJ_A",
            category = SnapshotCategory.ATTENDANCE,
            term = "TERM_1",
            status = SnapshotStatus.OK,
            payloadJson = okPayload(44, 50),
            errorMessage = null,
            sourceUrl = "https://parents.nie.ac.in/index.php?task=attendencelist&courseId=1",
            fetchedAt = 1001L,
        )
        repo.recordSnapshot(
            courseCode = "SUBJ_B",
            category = SnapshotCategory.ATTENDANCE,
            term = "TERM_1",
            status = SnapshotStatus.OK,
            payloadJson = okPayload(30, 50),
            errorMessage = null,
            sourceUrl = "https://parents.nie.ac.in/index.php?task=attendencelist&courseId=2",
            fetchedAt = 1002L,
        )

        val models = repo.observeCoursesForTerm("TERM_1").first()
        val a = models.first { it.courseCode == "SUBJ_A" }
        val b = models.first { it.courseCode == "SUBJ_B" }

        assertEquals(44, a.attended)
        assertEquals(50, a.conducted)
        assertEquals(AttendanceStatus.SAFE, a.engineResult?.status)
        assertEquals(8, a.engineResult?.canMiss)

        assertEquals(30, b.attended)
        assertEquals(50, b.conducted)
        assertEquals(AttendanceStatus.BELOW, b.engineResult?.status)
        assertEquals(30, b.engineResult?.mustAttendToRecover)
    }

    @Test
    fun `FAILED snapshot never replaces displayed OK data and marks it stale`() = runTest {
        val repo = AttendanceRepository(FakePortalDao())
        repo.recordTermAndCourses(
            semId = "TERM_1",
            semesterNumber = 5,
            label = "Sem 5",
            courses = listOf("SUBJ_A" to "Course Alpha"),
            now = 1000L,
        )

        repo.recordSnapshot(
            courseCode = "SUBJ_A",
            category = SnapshotCategory.ATTENDANCE,
            term = "TERM_1",
            status = SnapshotStatus.OK,
            payloadJson = okPayload(39, 49),
            errorMessage = null,
            sourceUrl = "https://parents.nie.ac.in/index.php?task=attendencelist",
            fetchedAt = 2000L,
        )

        repo.recordSnapshot(
            courseCode = "SUBJ_A",
            category = SnapshotCategory.ATTENDANCE,
            term = "TERM_1",
            status = SnapshotStatus.FAILED,
            payloadJson = null,
            errorMessage = "Network timeout",
            sourceUrl = "https://parents.nie.ac.in/index.php?task=attendencelist",
            fetchedAt = 3000L,
        )

        val model = repo.observeCoursesForTerm("TERM_1").first().single()
        assertEquals(39, model.attended)
        assertEquals(49, model.conducted)
        assertEquals(2000L, model.lastSyncedAt)
        assertTrue(model.isStale)
        assertEquals("Network timeout", model.latestErrorMessage)
        assertEquals(SnapshotStatus.FAILED, repo.latestAttemptStatus("SUBJ_A", SnapshotCategory.ATTENDANCE, "TERM_1"))
    }

    @Test
    fun `zero is never shown for missing data`() = runTest {
        val repo = AttendanceRepository(FakePortalDao())
        repo.recordTermAndCourses(
            semId = "TERM_1",
            semesterNumber = 5,
            label = "Sem 5",
            courses = listOf("SUBJ_EMPTY" to "Unfetched Course"),
            now = 1000L,
        )

        val model = repo.observeCoursesForTerm("TERM_1").first().single()
        assertNull(model.attended)
        assertNull(model.conducted)
        assertNull(model.engineResult)
        assertNull(model.lastSyncedAt)
        assertFalse(model.isStale)

        // Even after a FAILED attempt with no prior OK snapshot, values remain null (never 0)
        repo.recordSnapshot(
            courseCode = "SUBJ_EMPTY",
            category = SnapshotCategory.ATTENDANCE,
            term = "TERM_1",
            status = SnapshotStatus.FAILED,
            payloadJson = null,
            errorMessage = "Parse error",
            sourceUrl = "https://parents.nie.ac.in/index.php",
            fetchedAt = 1500L,
        )
        val afterFail = repo.observeCoursesForTerm("TERM_1").first().single()
        assertNull(afterFail.attended)
        assertNull(afterFail.conducted)
        assertNull(afterFail.engineResult)
        assertTrue(afterFail.isStale)
    }

    @Test
    fun `same course code can exist in different terms without mixing`() = runTest {
        val repo = AttendanceRepository(FakePortalDao())

        repo.recordTermAndCourses("SEM_OLD", 4, "Sem 4", listOf("CS101" to "Algorithms"), 1000L)
        repo.recordSnapshot(
            "CS101",
            SnapshotCategory.ATTENDANCE,
            "SEM_OLD",
            SnapshotStatus.OK,
            okPayload(18, 20),
            null,
            "https://parents.nie.ac.in/index.php?semId=SEM_OLD",
            1100L,
        )

        repo.recordTermAndCourses("SEM_NEW", 5, "Sem 5", listOf("CS101" to "Algorithms"), 2000L)
        repo.recordSnapshot(
            "CS101",
            SnapshotCategory.ATTENDANCE,
            "SEM_NEW",
            SnapshotStatus.OK,
            okPayload(10, 20),
            null,
            "https://parents.nie.ac.in/index.php?semId=SEM_NEW",
            2100L,
        )

        val oldCourse = repo.observeCoursesForTerm("SEM_OLD").first().single()
        val newCourse = repo.observeCoursesForTerm("SEM_NEW").first().single()

        assertEquals(18, oldCourse.attended)
        assertEquals(AttendanceStatus.SAFE, oldCourse.engineResult?.status)

        assertEquals(10, newCourse.attended)
        assertEquals(AttendanceStatus.BELOW, newCourse.engineResult?.status)
    }

    @Test
    fun `new terms are discovered stored independently and historical terms remain readable`() = runTest {
        val repo = AttendanceRepository(FakePortalDao())

        repo.recordTermAndCourses("SEM_1", 1, "Odd 2024", listOf("C1" to "Course 1"), 1000L)
        repo.recordTermAndCourses("SEM_2", 2, "Even 2025", listOf("C2" to "Course 2"), 2000L)
        repo.recordTermAndCourses("SEM_3", 3, "Odd 2025", listOf("C3" to "Course 3"), 3000L)

        val allTerms = repo.observeAllTerms().first()
        assertEquals(3, allTerms.size)
        assertEquals(listOf("SEM_3", "SEM_2", "SEM_1"), allTerms.map { it.semId })

        // Historical terms remain readable
        assertEquals("C1", repo.observeCoursesForTerm("SEM_1").first().single().courseCode)
        assertEquals("C2", repo.observeCoursesForTerm("SEM_2").first().single().courseCode)
        assertEquals("C3", repo.observeCoursesForTerm("SEM_3").first().single().courseCode)
    }

    @Test
    fun `latest term is identified correctly and flips when a new semId arrives`() = runTest {
        val repo = AttendanceRepository(FakePortalDao())

        repo.recordTermAndCourses("SEM_A", 5, "Sem 5", listOf("X1" to "Subject X1"), 1000L)
        assertEquals("SEM_A", repo.observeLatestTerm().first()?.semId)
        assertEquals("X1", repo.observeCoursesForLatestTerm().first().single().courseCode)

        // New term discovered -> isLatest flips to SEM_B
        repo.recordTermAndCourses("SEM_B", 6, "Sem 6", listOf("Y1" to "Subject Y1"), 2000L)
        val latest = repo.observeLatestTerm().first()
        assertNotNull(latest)
        assertEquals("SEM_B", latest?.semId)
        assertTrue(latest?.isLatest == true)

        val allTerms = repo.observeAllTerms().first()
        assertFalse(allTerms.first { it.semId == "SEM_A" }.isLatest)
        assertTrue(allTerms.first { it.semId == "SEM_B" }.isLatest)

        // Dashboard latest-term flow now emits SEM_B courses
        assertEquals("Y1", repo.observeCoursesForLatestTerm().first().single().courseCode)
    }

    @Test
    fun `cleanup keeps last 200 snapshots per course category and term and sanitizes URLs`() = runTest {
        val dao = FakePortalDao()
        val repo = AttendanceRepository(dao)
        repo.recordTermAndCourses("T1", 5, "Sem 5", listOf("C1" to "Course 1"), 1000L)

        repeat(205) { idx ->
            repo.recordSnapshot(
                courseCode = "C1",
                category = SnapshotCategory.ATTENDANCE,
                term = "T1",
                status = SnapshotStatus.OK,
                payloadJson = okPayload(idx % 50, 50),
                errorMessage = null,
                sourceUrl = "https://parents.nie.ac.in/index.php?task=attendencelist&courseId=1&ksign=SECRET_SIG&token=SECRET_TOK",
                fetchedAt = 10_000L + idx,
            )
        }

        val history = repo.observeHistory("C1", SnapshotCategory.ATTENDANCE, "T1").first()
        assertEquals(200, history.size)
        assertEquals(10_204L, history.first().fetchedAt)
        assertEquals(10_005L, history.last().fetchedAt)
        assertFalse(history.first().sourceUrl.contains("ksign"))
        assertFalse(history.first().sourceUrl.contains("token"))
    }

    @Test
    fun `recordAttendanceRecords stores, deduplicates and sorts records chronologically`() = runTest {
        val dao = FakePortalDao()
        val repo = AttendanceRepository(dao)

        val r1 = AttendanceRecordEntity(
            courseCode = "C1",
            term = "T1",
            sessionKey = "17-08-2026|11:00 TO 12:00|PRESENT|1",
            date = "17 Aug 2026",
            slot = "11:00 - 12:00",
            status = "PRESENT",
            dateMillis = 1000L,
            rawDateText = "17-08-2026",
            fetchedAt = 5000L,
        )
        val r2 = AttendanceRecordEntity(
            courseCode = "C1",
            term = "T1",
            sessionKey = "18-08-2026|09:00 TO 10:00|ABSENT|2",
            date = "18 Aug 2026",
            slot = "09:00 - 10:00",
            status = "ABSENT",
            dateMillis = 2000L,
            rawDateText = "18-08-2026",
            fetchedAt = 5000L,
        )

        repo.recordAttendanceRecords(listOf(r1, r2))

        val records = repo.observeAttendanceRecords("C1", "T1").first()
        assertEquals(2, records.size)
        // Most recent dateMillis first
        assertEquals("ABSENT", records[0].status)
        assertEquals("PRESENT", records[1].status)

        // Upserting the same records again does not duplicate
        repo.recordAttendanceRecords(listOf(r1, r2))
        val recordsAfterDuplicate = repo.observeAttendanceRecords("C1", "T1").first()
        assertEquals(2, recordsAfterDuplicate.size)
    }
}

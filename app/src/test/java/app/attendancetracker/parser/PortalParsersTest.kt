package app.attendancetracker.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PortalParsersTest {

    private fun readFixture(name: String): String {
        val candidates = listOf(
            File("../tests/fixtures/2026-27/$name"),
            File("tests/fixtures/2026-27/$name"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Fixture not found: $name (checked ${candidates.map { it.absolutePath }})")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `parseCourseList extracts dynamic courses semId empty secId and quick charts`() {
        val html = readFixture("courses.html")
        val result = PortalParsers.parseCourseList(html)
        assertTrue("Expected Success but got $result", result is ParseResult.Success)
        val data = (result as ParseResult.Success).data

        assertEquals("43", data.semId)
        assertEquals(5, data.semesterNumber)
        assertEquals(10, data.courses.size)

        val first = data.courses.first()
        assertEquals("BIS501", first.code)
        assertEquals("Software Engineering and Project Management", first.name)
        assertEquals(80, first.quickAttendancePercent)
        assertEquals(20, first.quickCieScore)
        assertTrue(first.attendanceUrl!!.contains("task=attendencelist"))
        assertTrue(first.cieUrl!!.contains("task=ciedetails"))
        assertTrue(first.lessonPlanUrl!!.contains("task=getuploadlession"))

        // Elective with empty secId= must be preserved verbatim
        val elective = data.courses.first { it.code == "BIS516B" }
        assertTrue(elective.attendanceUrl!!.contains("secId=&semId=43"))
    }

    @Test
    fun `parseAttendance extracts Present Absent StillToGo and validates slot rows`() {
        val html = readFixture("attendance.html")
        val result = PortalParsers.parseAttendance(html, expectedCourseCode = "BIS501")
        assertTrue("Expected Success but got $result", result is ParseResult.Success)
        val data = (result as ParseResult.Success).data

        assertEquals("BIS501", data.courseCode)
        assertEquals(28, data.presentCount)
        assertEquals(7, data.absentCount)
        assertEquals(13, data.stillToGoCount)
        assertEquals(35, data.conductedCount)
        assertEquals(28, data.presentRows.size)
        assertEquals(7, data.absentRows.size)
        // 2-hour slot counts as 1 row
        assertTrue(data.absentRows.any { it.time.contains("16:00") && it.time.contains("18:00") })
    }

    @Test
    fun `parseCie handles trailing commas distinguishes obtained vs class average and flags table discrepancy`() {
        val html = readFixture("cie.html")
        val result = PortalParsers.parseCie(html, expectedCourseCode = "BIS501")
        assertTrue("Expected Success but got $result", result is ParseResult.Success)
        val data = (result as ParseResult.Success).data

        assertEquals("BIS501", data.courseCode)
        assertEquals(20.0, data.currentCie!!, 0.01)
        assertEquals(80, data.attendancePercent)
        assertEquals(4, data.assessments.size)

        val tst1 = data.assessments.first { it.label == "tst1" }
        assertEquals(20.0, tst1.obtainedMarks, 0.01)
        assertEquals(25.0, tst1.maxMarks, 0.01)
        assertEquals(19.0, tst1.classAverage, 0.01)
        assertTrue(tst1.hasTableDiscrepancy)

        assertTrue(data.hasTableChartDiscrepancy)
        assertNotNull(data.discrepancyNote)
    }

    @Test
    fun `parseLessonPlan returns none uploaded when no content exists`() {
        val html = readFixture("lesson.html")
        val result = PortalParsers.parseLessonPlan(html, expectedCourseCode = "BIS501")
        assertTrue("Expected Success but got $result", result is ParseResult.Success)
        val data = (result as ParseResult.Success).data

        assertEquals("BIS501", data.courseCode)
        assertEquals("none uploaded", data.statusText)
        assertTrue(data.entries.isEmpty())
    }

    @Test
    fun `parseExamHistory extracts all semesters SGPA credits and grades independently`() {
        val html = readFixture("exam_history.html")
        val result = PortalParsers.parseExamHistory(html)
        assertTrue("Expected Success but got $result", result is ParseResult.Success)
        val data = (result as ParseResult.Success).data

        assertEquals(4, data.semesters.size)
        val sem1 = data.semesters[0]
        assertEquals(1, sem1.semesterNumber)
        assertEquals(20.0, sem1.creditsRegistered!!, 0.01)
        assertEquals(20.0, sem1.creditsEarned!!, 0.01)
        assertEquals(8.05, sem1.sgpa!!, 0.01)
        assertEquals(8, sem1.courses.size)

        val sem3 = data.semesters[2]
        assertEquals(3, sem3.semesterNumber)
        assertEquals(8.33, sem3.sgpa!!, 0.01)
        assertTrue(sem3.courses.any { it.grade == "PP" && it.creditsEarned == 0.0 })
    }

    @Test
    fun `corrupted fixtures return Failure for all required failure cases`() {
        val attHtml = readFixture("attendance.html")
        val coursesHtml = readFixture("courses.html")
        val cieHtml = readFixture("cie.html")
        val lessonHtml = readFixture("lesson.html")
        val examHtml = readFixture("exam_history.html")
        val login1 = readFixture("login_step1.html")
        val login2 = readFixture("login_step2.html")

        // 1. Empty page
        assertTrue(PortalParsers.parseCourseList("") is ParseResult.Failure)
        assertTrue(PortalParsers.parseAttendance("   ") is ParseResult.Failure)
        assertTrue(PortalParsers.parseCie("") is ParseResult.Failure)
        assertTrue(PortalParsers.parseLessonPlan("") is ParseResult.Failure)
        assertTrue(PortalParsers.parseExamHistory("") is ParseResult.Failure)

        // 2. Login page returned instead of data page
        assertTrue(PortalParsers.parseCourseList(login1) is ParseResult.Failure)
        assertTrue(PortalParsers.parseAttendance(login2) is ParseResult.Failure)
        assertTrue(PortalParsers.parseCie(login1) is ParseResult.Failure)
        assertTrue(PortalParsers.parseLessonPlan(login2) is ParseResult.Failure)
        assertTrue(PortalParsers.parseExamHistory(login1) is ParseResult.Failure)

        // 3. Missing table
        assertTrue(PortalParsers.parseCourseList(coursesHtml.replace("cn-pay-table", "other-table")) is ParseResult.Failure)
        assertTrue(PortalParsers.parseAttendance(attHtml.replace("cn-attend-list1", "other-list")) is ParseResult.Failure)
        assertTrue(PortalParsers.parseCie(cieHtml.replace("cn-cie-table", "other-cie")) is ParseResult.Failure)
        assertTrue(PortalParsers.parseLessonPlan(lessonHtml.replace("cn-stu-info", "other-info")) is ParseResult.Failure)
        assertTrue(PortalParsers.parseExamHistory(examHtml.replace("result-table", "other-res")) is ParseResult.Failure)

        // 4. Incorrect course code
        assertTrue(PortalParsers.parseAttendance(attHtml, expectedCourseCode = "WRONG999") is ParseResult.Failure)
        assertTrue(PortalParsers.parseCie(cieHtml, expectedCourseCode = "WRONG999") is ParseResult.Failure)
        assertTrue(PortalParsers.parseLessonPlan(lessonHtml, expectedCourseCode = "WRONG999") is ParseResult.Failure)

        // 5. Missing required fields
        assertTrue(PortalParsers.parseAttendance(attHtml.replace("Present[28]", "Present")) is ParseResult.Failure)
        assertTrue(PortalParsers.parseCie(cieHtml.replace("var chartData", "var removedData")) is ParseResult.Failure)

        // 6. Invalid attendance counts (Present + Absent != table rows)
        assertTrue(PortalParsers.parseAttendance(attHtml.replace("Present[28]", "Present[99]")) is ParseResult.Failure)

        // 7. Unexpected HTML structure
        assertTrue(PortalParsers.parseCourseList("<html><body><div>Hello</div></body></html>") is ParseResult.Failure)
    }

    @Test
    fun `parsers handle arbitrary dynamic course counts semIds and assessment names`() {
        val dynamicDashboard = """
            <html><body>
              <div class="cn-stu-data1"><p>M.Tech, SEM 02, SEC A</p></div>
              <table class="cn-pay-table"><tbody>
                <tr>
                  <td>DYN101</td><td>Dynamic Subject One</td>
                  <td><a href="index.php?option=com_bvbsims&controller=uploadlession&task=getuploadlession&courseId=9001&secId=&semId=99&deptId=5&streamId=2">Lesson Plan</a></td>
                  <td><a href="index.php?option=com_studentdashboard&controller=studentdashboard&task=attendencelist&courseId=9001&secId=&semId=99">Attendance</a></td>
                  <td><a href="index.php?option=com_studentdashboard&controller=studentdashboard&task=ciedetails&courseId=9001&secId=&semId=99">CIE</a></td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()

        val dash = PortalParsers.parseCourseList(dynamicDashboard) as ParseResult.Success
        assertEquals("99", dash.data.semId)
        assertEquals(2, dash.data.semesterNumber)
        assertEquals(1, dash.data.courses.size)
        assertEquals("DYN101", dash.data.courses[0].code)

        val dynamicCie = """
            <html><body>
              <h3 class="md-card-head-text"><span>DYN101 - Dynamic Subject One</span></h3>
              <table class="cn-cie-table">
                <caption>Dynamic Subject One( DYN101)</caption>
                <tbody><tr>
                  <td>LAB1 : 18</td><td>PROJECT_VIVA : 27</td>
                  <td>Current CIE : 45</td><td>ATTENDANCE : 92%</td>
                </tr></tbody>
              </table>
              <script>
                var chartData = [
                  { "xaxis": "LAB1", "col1": 18, "col2": 2, "maxmarks": 20, "optainmarks": 15, "linevalue": 15, },
                  { "xaxis": "PROJECT_VIVA", "col1": 27, "col2": 3, "maxmarks": 30, "optainmarks": 22, "linevalue": 22, }
                ];
              </script>
            </body></html>
        """.trimIndent()

        val cie = PortalParsers.parseCie(dynamicCie, "DYN101") as ParseResult.Success
        assertFalse(cie.data.hasTableChartDiscrepancy)
        assertEquals(listOf("LAB1", "PROJECT_VIVA"), cie.data.assessments.map { it.label })
        assertEquals(27.0, cie.data.assessments[1].obtainedMarks, 0.01)
    }
}

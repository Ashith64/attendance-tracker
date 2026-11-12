package app.attendancetracker.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Pure Kotlin HTML parsers for the NIE Contineo parent portal.
 * Every parser validates structure and returns [ParseResult.Failure] rather than guessing.
 */
object PortalParsers {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Removes trailing commas before `]` or `}` outside string literals so JS arrays
     * like `chartData` and Billboard.js `columns` parse cleanly as JSON.
     */
    internal fun stripTrailingCommas(raw: String): String {
        val sb = StringBuilder(raw.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (escaped) {
                sb.append(ch)
                escaped = false
                i++
                continue
            }
            if (ch == '\\' && inString) {
                sb.append(ch)
                escaped = true
                i++
                continue
            }
            if (ch == '"') {
                inString = !inString
                sb.append(ch)
                i++
                continue
            }
            if (!inString && ch == ',') {
                var j = i + 1
                while (j < raw.length && raw[j].isWhitespace()) j++
                if (j < raw.length && (raw[j] == ']' || raw[j] == '}' || raw[j] == ',')) {
                    i++
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun checkLoginOrErrorPage(html: String, doc: Document): String? {
        if (html.isBlank()) return "Empty HTML response"
        if (doc.selectFirst(Selectors.LOGIN_FORM) != null ||
            doc.selectFirst(Selectors.LOGIN_OTP_TASK) != null ||
            doc.selectFirst(Selectors.LOGIN_FINAL_TASK) != null
        ) {
            return "Session expired or login page returned"
        }
        val modal = doc.selectFirst(Selectors.LOGIN_ERROR_MODAL)
        if (modal != null && modal.text().contains("Login Failed", ignoreCase = true)) {
            return "Portal login error page returned"
        }
        return null
    }

    private fun parseQuickChartMap(doc: Document, bindToSelector: String): Map<String, Int> {
        val scriptData = doc.select(Selectors.SCRIPT_TAGS)
            .firstOrNull { it.data().contains(bindToSelector) }
            ?.data()
            ?: return emptyMap()
        val match = Selectors.REGEX_CHART_COLUMNS.find(scriptData) ?: return emptyMap()
        val cleaned = stripTrailingCommas(match.groupValues[1])
        return runCatching {
            val arr = lenientJson.parseToJsonElement(cleaned).jsonArray
            buildMap {
                for (elem in arr) {
                    val pair = (elem as? JsonArray) ?: continue
                    if (pair.size >= 2) {
                        val code = pair[0].jsonPrimitive.content.trim()
                        val value = pair[1].jsonPrimitive.intOrNull
                        if (code.isNotEmpty() && value != null) {
                            put(code, value)
                        }
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun parseCourseHeader(doc: Document): Pair<String, String>? {
        val element = doc.selectFirst(Selectors.COURSE_HEAD_TEXT) ?: doc.selectFirst("h3.md-card-head-text")
        val raw = element?.text()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val delimiterIdx = raw.indexOfAny(charArrayOf('-', ':'))
        if (delimiterIdx > 0) {
            val code = raw.substring(0, delimiterIdx).trim()
            val name = raw.substring(delimiterIdx + 1).trim()
            if (code.isNotEmpty()) return code to name
        }
        val firstWord = raw.split(Regex("""\s+""")).firstOrNull()?.trim().orEmpty()
        return if (firstWord.isNotEmpty()) firstWord to raw else null
    }

    private fun extractCourseCodeFromCaption(caption: String): String? {
        val open = caption.lastIndexOf('(')
        val close = caption.lastIndexOf(')')
        if (open < 0 || close <= open + 1) return null
        return caption.substring(open + 1, close).trim().ifEmpty { null }
    }

    fun parseCourseList(html: String): ParseResult<CourseListData> {
        val doc = Jsoup.parse(html)
        checkLoginOrErrorPage(html, doc)?.let { return ParseResult.Failure(it) }

        val table = doc.selectFirst(Selectors.DASHBOARD_COURSE_TABLE)
            ?: return ParseResult.Failure("Missing course registration table (${Selectors.DASHBOARD_COURSE_TABLE})")

        val rows = table.select("tbody tr")
        if (rows.isEmpty()) {
            return ParseResult.Failure("Course registration table has no rows")
        }

        val quickCie = parseQuickChartMap(doc, "#barPadding")
        val quickAtt = parseQuickChartMap(doc, "#gaugeTypeMulti")

        val courses = mutableListOf<DiscoveredCourse>()
        var discoveredSemId: String? = null

        for (row in rows) {
            val cells = row.select("td")
            if (cells.size < 2) {
                return ParseResult.Failure("Unexpected course row column count: ${cells.size}")
            }
            val code = cells[0].text().trim()
            val name = cells[1].text().trim()
            if (code.isEmpty() || name.isEmpty()) {
                return ParseResult.Failure("Course row missing code or name")
            }

            val lessonUrl = cells.getOrNull(2)?.selectFirst("a[href]")?.attr("href")?.trim()?.ifEmpty { null }
            val attUrl = cells.getOrNull(3)?.selectFirst("a[href]")?.attr("href")?.trim()?.ifEmpty { null }
            val cieUrl = cells.getOrNull(4)?.selectFirst("a[href]")?.attr("href")?.trim()?.ifEmpty { null }

            if (discoveredSemId == null) {
                val anyUrl = attUrl ?: cieUrl ?: lessonUrl
                if (anyUrl != null) {
                    discoveredSemId = Selectors.REGEX_SEM_ID_PARAM.find(anyUrl)?.groupValues?.get(1)?.trim()
                }
            }

            courses.add(
                DiscoveredCourse(
                    code = code,
                    name = name,
                    lessonPlanUrl = lessonUrl,
                    attendanceUrl = attUrl,
                    cieUrl = cieUrl,
                    quickAttendancePercent = quickAtt[code],
                    quickCieScore = quickCie[code],
                ),
            )
        }

        val semId = discoveredSemId?.ifEmpty { null }
            ?: return ParseResult.Failure("Could not discover semId from course links")

        val metaText = doc.selectFirst(Selectors.DASHBOARD_STUDENT_META)?.text()?.trim().orEmpty()
        val semesterNumber = Selectors.REGEX_SEM_NUMBER.find(metaText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val termLabel = if (semesterNumber > 0) "Sem $semesterNumber" else "Term $semId"

        return ParseResult.Success(
            CourseListData(
                semId = semId,
                semesterNumber = semesterNumber,
                termLabel = termLabel,
                courses = courses,
            ),
        )
    }

    fun parseAttendance(html: String, expectedCourseCode: String? = null): ParseResult<AttendanceData> {
        val doc = Jsoup.parse(html)
        checkLoginOrErrorPage(html, doc)?.let { return ParseResult.Failure(it) }

        val (courseCode, courseName) = parseCourseHeader(doc)
            ?: return ParseResult.Failure("Missing course header on Attendance page")

        if (expectedCourseCode != null && !courseCode.equals(expectedCourseCode.trim(), ignoreCase = true)) {
            return ParseResult.Failure("Course code mismatch: expected $expectedCourseCode, got $courseCode")
        }

        val presentText = doc.selectFirst(Selectors.ATTENDANCE_PRESENT_BADGE)?.text().orEmpty()
        val absentText = doc.selectFirst(Selectors.ATTENDANCE_ABSENT_BADGE)?.text().orEmpty()
        val stillText = doc.selectFirst(Selectors.ATTENDANCE_STILL_BADGE)?.text().orEmpty()

        val presentCount = Selectors.REGEX_PRESENT_COUNT.find(presentText)?.groupValues?.get(1)?.toIntOrNull()
            ?: return ParseResult.Failure("Missing Present[n] badge")
        val absentCount = Selectors.REGEX_ABSENT_COUNT.find(absentText)?.groupValues?.get(1)?.toIntOrNull()
            ?: return ParseResult.Failure("Missing Absent [n] badge")
        val stillCount = Selectors.REGEX_STILL_COUNT.find(stillText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        if (doc.selectFirst(Selectors.ATTENDANCE_PRESENT_TABLE) == null) {
            return ParseResult.Failure("Missing Present table (${Selectors.ATTENDANCE_PRESENT_TABLE})")
        }
        if (doc.selectFirst(Selectors.ATTENDANCE_ABSENT_TABLE) == null) {
            return ParseResult.Failure("Missing Absent table (${Selectors.ATTENDANCE_ABSENT_TABLE})")
        }

        val presentRows = parseAttendanceRows(doc.select(Selectors.ATTENDANCE_PRESENT_ROWS))
            ?: return ParseResult.Failure("Malformed row in Present table")
        val absentRows = parseAttendanceRows(doc.select(Selectors.ATTENDANCE_ABSENT_ROWS))
            ?: return ParseResult.Failure("Malformed row in Absent table")

        val totalRows = presentRows.size + absentRows.size
        if (presentCount != presentRows.size || absentCount != absentRows.size || presentCount + absentCount != totalRows) {
            return ParseResult.Failure(
                "Attendance count mismatch: Present($presentCount) vs rows(${presentRows.size}), " +
                    "Absent($absentCount) vs rows(${absentRows.size})",
            )
        }
        if (presentCount > presentCount + absentCount) {
            return ParseResult.Failure("Attended classes exceed conducted classes")
        }

        return ParseResult.Success(
            AttendanceData(
                courseCode = courseCode,
                courseName = courseName,
                presentCount = presentCount,
                absentCount = absentCount,
                stillToGoCount = stillCount,
                presentRows = presentRows,
                absentRows = absentRows,
            ),
        )
    }

    private fun parseAttendanceRows(rows: org.jsoup.select.Elements): List<AttendanceSlotRow>? {
        val result = mutableListOf<AttendanceSlotRow>()
        for (row in rows) {
            val cells = row.select("td")
            if (cells.size < 4) return null
            result.add(
                AttendanceSlotRow(
                    slNo = cells[0].text().trim(),
                    date = cells[1].text().trim(),
                    time = cells[2].text().replace(Regex("""\s+"""), " ").trim(),
                    status = cells[3].text().trim(),
                ),
            )
        }
        return result
    }

    fun parseCie(html: String, expectedCourseCode: String? = null): ParseResult<CieData> {
        val doc = Jsoup.parse(html)
        checkLoginOrErrorPage(html, doc)?.let { return ParseResult.Failure(it) }

        val headerPair = parseCourseHeader(doc)
        val captionText = doc.selectFirst(Selectors.CIE_TABLE_CAPTION)?.text()?.trim().orEmpty()
        val captionCode = extractCourseCodeFromCaption(captionText)

        val courseCode = headerPair?.first ?: captionCode
            ?: return ParseResult.Failure("Missing course code on CIE page")
        val courseName = headerPair?.second ?: captionText.substringBefore('(').trim()

        if (expectedCourseCode != null && !courseCode.equals(expectedCourseCode.trim(), ignoreCase = true)) {
            return ParseResult.Failure("Course code mismatch: expected $expectedCourseCode, got $courseCode")
        }

        val cieTable = doc.selectFirst(Selectors.CIE_TABLE)
            ?: return ParseResult.Failure("Missing CIE summary table (${Selectors.CIE_TABLE})")

        val tableCells = cieTable.select("tbody tr td")
        val cellMap = mutableMapOf<String, String>()
        var tableCurrentCie: Double? = null
        var tableAttendancePct: Int? = null

        for (cell in tableCells) {
            val text = cell.text().trim()
            val curMatch = Selectors.REGEX_CURRENT_CIE.find(text)
            if (curMatch != null) {
                tableCurrentCie = curMatch.groupValues[1].toDoubleOrNull()
                continue
            }
            val attMatch = Selectors.REGEX_CIE_ATTENDANCE.find(text)
            if (attMatch != null) {
                tableAttendancePct = attMatch.groupValues[1].toIntOrNull()
                continue
            }
            val colonIdx = text.indexOf(':')
            if (colonIdx > 0) {
                val label = text.substring(0, colonIdx).trim()
                val value = text.substring(colonIdx + 1).trim()
                cellMap[label] = value
            }
        }

        val legendCie = doc.selectFirst(Selectors.CIE_LEGEND_MARKS)?.text()
            ?.let { Selectors.REGEX_FINAL_CIE.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        val legendAtt = doc.selectFirst(Selectors.CIE_LEGEND_ATTENDANCE)?.text()
            ?.let { Regex("""(\d+)\s*%""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val currentCie = tableCurrentCie ?: legendCie
        val attendancePct = tableAttendancePct ?: legendAtt

        val scripts = doc.select(Selectors.SCRIPT_TAGS).joinToString("\n") { it.data() }
        val chartMatch = Selectors.REGEX_CIE_CHART_DATA.find(scripts)
            ?: return ParseResult.Failure("Missing chartData script array on CIE page")

        val cleanedChartJson = stripTrailingCommas(chartMatch.groupValues[1])
        val chartArray = runCatching { lenientJson.parseToJsonElement(cleanedChartJson).jsonArray }
            .getOrElse { return ParseResult.Failure("Malformed chartData JSON: ${it.message}") }

        var anyDiscrepancy = false
        val discrepancyReasons = mutableListOf<String>()
        val assessments = mutableListOf<CieAssessment>()

        for (item in chartArray) {
            val obj = item.jsonObject
            val label = obj["xaxis"]?.jsonPrimitive?.content?.trim()
                ?: return ParseResult.Failure("chartData entry missing 'xaxis'")
            val obtained = obj["col1"]?.jsonPrimitive?.doubleOrNull
                ?: return ParseResult.Failure("chartData entry '$label' missing 'col1'")
            val maxMarks = obj["maxmarks"]?.jsonPrimitive?.doubleOrNull
                ?: return ParseResult.Failure("chartData entry '$label' missing 'maxmarks'")
            // Backend uses 'optainmarks' / 'linevalue' for Class Average (see AmGraph graph2)
            val classAvg = obj["optainmarks"]?.jsonPrimitive?.doubleOrNull
                ?: obj["linevalue"]?.jsonPrimitive?.doubleOrNull
                ?: 0.0

            val tableVal = cellMap[label]
            var cellDiscrepancy = false
            if (tableVal != null) {
                val numericTable = tableVal.toDoubleOrNull()
                if (tableVal == "-" && (obtained > 0.0 || maxMarks > 0.0)) {
                    cellDiscrepancy = true
                    discrepancyReasons.add("$label: table='-' vs chart=$obtained/$maxMarks")
                } else if (numericTable != null && kotlin.math.abs(numericTable - obtained) > 0.01) {
                    cellDiscrepancy = true
                    discrepancyReasons.add("$label: table=$numericTable vs chart=$obtained")
                }
            }
            if (cellDiscrepancy) anyDiscrepancy = true

            assessments.add(
                CieAssessment(
                    label = label,
                    obtainedMarks = obtained,
                    maxMarks = maxMarks,
                    classAverage = classAvg,
                    tableCellValue = tableVal,
                    hasTableDiscrepancy = cellDiscrepancy,
                ),
            )
        }

        return ParseResult.Success(
            CieData(
                courseCode = courseCode,
                courseName = courseName,
                currentCie = currentCie,
                attendancePercent = attendancePct,
                assessments = assessments,
                hasTableChartDiscrepancy = anyDiscrepancy,
                discrepancyNote = if (anyDiscrepancy) discrepancyReasons.joinToString("; ") else null,
            ),
        )
    }

    fun parseLessonPlan(html: String, expectedCourseCode: String? = null): ParseResult<LessonPlanData> {
        val doc = Jsoup.parse(html)
        checkLoginOrErrorPage(html, doc)?.let { return ParseResult.Failure(it) }

        if (doc.selectFirst(Selectors.LESSON_PLAN_INFO_TABLE) == null) {
            return ParseResult.Failure("Missing course details table (${Selectors.LESSON_PLAN_INFO_TABLE})")
        }

        var courseCode = ""
        var courseName = ""
        for (cell in doc.select(Selectors.LESSON_PLAN_INFO_CELLS)) {
            val text = cell.text().trim()
            when {
                text.startsWith("Course Code:", ignoreCase = true) ->
                    courseCode = text.substringAfter(":").trim()
                text.startsWith("Course Name:", ignoreCase = true) ->
                    courseName = text.substringAfter(":").trim()
            }
        }

        if (courseCode.isEmpty()) {
            return ParseResult.Failure("Missing Course Code on Lesson Plan page")
        }
        if (expectedCourseCode != null && !courseCode.equals(expectedCourseCode.trim(), ignoreCase = true)) {
            return ParseResult.Failure("Course code mismatch: expected $expectedCourseCode, got $courseCode")
        }

        val entries = mutableListOf<LessonPlanEntry>()
        for (row in doc.select(Selectors.LESSON_PLAN_CONTENT_TABLES)) {
            val rowText = row.text().trim()
            val link = row.selectFirst("a[href]")?.attr("href")?.trim()?.ifEmpty { null }
            if (rowText.isNotEmpty()) {
                entries.add(LessonPlanEntry(title = rowText, url = link))
            }
        }
        if (entries.isEmpty()) {
            for (linkEl in doc.select(Selectors.LESSON_PLAN_FILE_LINKS)) {
                val href = linkEl.attr("href").trim()
                if (href.isNotEmpty() && !href.startsWith("#")) {
                    entries.add(LessonPlanEntry(title = linkEl.text().trim().ifEmpty { href }, url = href))
                }
            }
        }

        return ParseResult.Success(
            LessonPlanData(
                courseCode = courseCode,
                courseName = courseName,
                statusText = if (entries.isEmpty()) "none uploaded" else "available",
                entries = entries,
            ),
        )
    }

    fun parseExamHistory(html: String): ParseResult<ExamHistoryData> {
        val doc = Jsoup.parse(html)
        checkLoginOrErrorPage(html, doc)?.let { return ParseResult.Failure(it) }

        val blocks = doc.select(Selectors.EXAM_RESULT_BLOCKS)
        if (blocks.isEmpty()) {
            return ParseResult.Failure("Missing semester result blocks (${Selectors.EXAM_RESULT_BLOCKS})")
        }

        val semesters = mutableListOf<ExamSemesterRecord>()
        for (block in blocks) {
            val h6 = block.selectFirst(Selectors.EXAM_HEADER_H6)
                ?: return ParseResult.Failure("Result block missing header h6")

            // Own text before spans holds e.g. "Odd 2024-25 - Regular - Semester : 1"
            val headerLabel = h6.ownText().trim().ifEmpty { h6.text().trim() }
            if (headerLabel.isEmpty()) {
                return ParseResult.Failure("Result block has empty semester header")
            }

            val semNumber = Selectors.REGEX_EXAM_SEMESTER.find(headerLabel)?.groupValues?.get(1)?.toIntOrNull()
            val credReg = h6.selectFirst(Selectors.EXAM_CREDITS_REG)?.text()
                ?.let { Selectors.REGEX_TRAILING_NUMBER.find(it.trim())?.groupValues?.get(1)?.toDoubleOrNull() }
            val credEarned = h6.selectFirst(Selectors.EXAM_CREDITS_EARNED)?.text()
                ?.let { Selectors.REGEX_TRAILING_NUMBER.find(it.trim())?.groupValues?.get(1)?.toDoubleOrNull() }
            val sgpa = h6.selectFirst(Selectors.EXAM_SGPA)?.text()
                ?.let { Selectors.REGEX_TRAILING_NUMBER.find(it.trim())?.groupValues?.get(1)?.toDoubleOrNull() }

            val table = block.selectFirst(Selectors.EXAM_TABLE)
                ?: return ParseResult.Failure("Result block '$headerLabel' missing table (${Selectors.EXAM_TABLE})")

            val courses = mutableListOf<ExamCourseGrade>()
            for (row in table.select("tbody tr")) {
                val cells = row.select("td")
                if (cells.size < 6) {
                    return ParseResult.Failure("Malformed exam history row in '$headerLabel'")
                }
                courses.add(
                    ExamCourseGrade(
                        courseCode = cells[0].text().trim(),
                        courseName = cells[1].text().trim(),
                        creditsRegistered = cells[2].text().trim().toDoubleOrNull() ?: 0.0,
                        creditsEarned = cells[3].text().trim().toDoubleOrNull() ?: 0.0,
                        gradePoints = cells[4].text().trim().toDoubleOrNull() ?: 0.0,
                        grade = cells[5].text().trim(),
                    ),
                )
            }

            semesters.add(
                ExamSemesterRecord(
                    headerLabel = headerLabel,
                    semesterNumber = semNumber,
                    creditsRegistered = credReg,
                    creditsEarned = credEarned,
                    sgpa = sgpa,
                    courses = courses,
                ),
            )
        }

        return ParseResult.Success(ExamHistoryData(semesters = semesters))
    }
}

package app.attendancetracker.parser

/**
 * Single source of truth for all CSS selectors and extraction patterns
 * derived from docs/PORTAL_RECON.md.
 */
object Selectors {
    // Login & session-expiry detection
    const val LOGIN_FORM = "form#login-form"
    const val LOGIN_OTP_TASK = "input[name=task][value=loginOtp]"
    const val LOGIN_FINAL_TASK = "input[name=task][value=login]"
    const val LOGIN_ERROR_MODAL = "#myloginModal"

    // Dashboard (HOME PAGE)
    const val DASHBOARD_STUDENT_META = ".cn-stu-data1 p"
    const val DASHBOARD_COURSE_TABLE = "table.cn-pay-table"
    const val DASHBOARD_COURSE_ROWS = "table.cn-pay-table tbody tr"
    const val SCRIPT_TAGS = "script"

    val REGEX_SEM_NUMBER = Regex("""SEM\s*(\d+)""", RegexOption.IGNORE_CASE)
    val REGEX_SEM_ID_PARAM = Regex("""(?:^|[?&])semId=([^&\s]+)""")
    val REGEX_CHART_COLUMNS = Regex("""columns\s*:\s*(\[\[[\s\S]*?\])\s*,\s*type\s*:""")

    // Course header shared by Attendance & CIE
    const val COURSE_HEAD_TEXT = "h3.md-card-head-text span"

    // Attendance page
    const val ATTENDANCE_PRESENT_BADGE = ".cn-legend .cn-attend"
    const val ATTENDANCE_ABSENT_BADGE = ".cn-legend .cn-absent"
    const val ATTENDANCE_STILL_BADGE = ".cn-legend .cn-still"
    const val ATTENDANCE_PRESENT_TABLE = "table.cn-attend-list1"
    const val ATTENDANCE_PRESENT_ROWS = "table.cn-attend-list1 tbody tr"
    const val ATTENDANCE_ABSENT_TABLE = "table.cn-attend-list2"
    const val ATTENDANCE_ABSENT_ROWS = "table.cn-attend-list2 tbody tr"

    val REGEX_PRESENT_COUNT = Regex("""Present\s*\[(\d+)\]""", RegexOption.IGNORE_CASE)
    val REGEX_ABSENT_COUNT = Regex("""Absent\s*\[(\d+)\]""", RegexOption.IGNORE_CASE)
    val REGEX_STILL_COUNT = Regex("""Still\s+to\s+go\s*\[(\d+)\]""", RegexOption.IGNORE_CASE)

    // CIE page
    const val CIE_TABLE = "table.cn-cie-table"
    const val CIE_TABLE_CAPTION = "table.cn-cie-table caption"
    const val CIE_TABLE_CELLS = "table.cn-cie-table tbody tr td"
    const val CIE_LEGEND_MARKS = ".cn-cie-legend .cn-color-green"
    const val CIE_LEGEND_ATTENDANCE = ".cn-cie-legend .cn-color-red"

    val REGEX_CURRENT_CIE = Regex("""Current\s+CIE\s*:\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
    val REGEX_FINAL_CIE = Regex("""Final\s+CIE\s+Marks\s+Obtained\s*:\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
    val REGEX_CIE_ATTENDANCE = Regex("""ATTENDANCE\s*:\s*(\d+)\s*%""", RegexOption.IGNORE_CASE)
    val REGEX_CIE_CHART_DATA = Regex("""var\s+chartData\s*=\s*(\[[\s\S]*?\]);""")

    // Lesson Plan page
    const val LESSON_PLAN_FORM = "form#lessionUpload"
    const val LESSON_PLAN_INFO_TABLE = "div.cn-basic-details table.cn-stu-info"
    const val LESSON_PLAN_INFO_CELLS = "div.cn-basic-details table.cn-stu-info tbody tr td"
    const val LESSON_PLAN_CONTENT_TABLES = "form#lessionUpload table:not(.cn-stu-info) tbody tr"
    const val LESSON_PLAN_FILE_LINKS = "form#lessionUpload a[href]"

    // Exam History page
    const val EXAM_RESULT_BLOCKS = "div.result-table"
    const val EXAM_HEADER_H6 = "div.cn-blocks h6"
    const val EXAM_CREDITS_REG = "span.cn-color-green"
    const val EXAM_CREDITS_EARNED = "span.cn-bgcolor3"
    const val EXAM_SGPA = "span.cn-bgcolor1"
    const val EXAM_TABLE = "table.res-table"
    const val EXAM_TABLE_ROWS = "table.res-table tbody tr"

    val REGEX_EXAM_SEMESTER = Regex("""Semester\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
    val REGEX_TRAILING_NUMBER = Regex("""([0-9]+(?:\.[0-9]+)?)\s*$""")
}

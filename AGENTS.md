# Attendance Tracker: agent rules

Product: native Android app (Kotlin, Jetpack Compose, Room, WorkManager, BiometricPrompt, Android Keystore) that reads the NIE parent portal https://parents.nie.ac.in/ and shows SUBJECT-WISE attendance, CIE marks and lesson plans.

Hard rules:
1. No AI features, no cloud backend, no analytics. All data stays on the device.
2. No combined or overall attendance anywhere. Every number belongs to exactly one course.
3. Everything on D: only. SDK=D:/Android/Sdk, GRADLE_USER_HOME=D:/Gradle/.gradle, ANDROID_USER_HOME=D:/Android/UserHome, TEMP=D:/Temp. Never write to C:.
4. Secrets: never print, log, commit or screenshot USN, DOB, phone digits, cookies or tokens. Use placeholders like <USN>.
5. Never bypass CAPTCHA or OTP. If the portal asks, the app asks the student.
6. A failed fetch never overwrites good data and never shows 0. Validate: attended <= conducted, course code matches, values present.
7. Architecture is decided ONLY by docs/PORTAL_RECON.md. Do not assume WebView scraping before that file exists.
8. Use the portal's own attendance unit (classes vs hours). Integer math only. For threshold p percent: canMiss = floor((100*A - p*C) / p) when 100*A >= p*C; mustAttend = ceil((p*C - 100*A) / (100 - p)) when below.
9. Parsers are pure Kotlin functions tested against saved HTML fixtures in tests/fixtures with personal data replaced by placeholders.
10. Discover courses dynamically from the portal. Never hardcode course codes.
11. After every step update PROJECT_STATUS.md (done, next, blockers) and re-read AGENTS.md at the start of every task.
12. Nothing about a year or semester is hardcoded: no semId, courseId, secId, department or stream IDs, course codes, number of courses, or assessment names (tst1, tst2, EL1, QZ1 are only examples).
13. Term model: each semester is a Term (semId, semester number read from the page, label, firstSeenAt). The dashboard shows the newest term. Older terms stay in the database read-only, because the portal drops them from the home page when a new semester starts.
14. Use every course link exactly as the home page gives it. secId can be empty (electives), and labs or project courses may have missing buttons or empty pages.
15. CIE is parsed generically: read every assessment label with its maximum and obtained marks from the page. Different courses have different assessments.
16. A course with zero classes conducted shows 'No data', never 0 percent.
17. Change detection: every parser returns Failure with a reason when the structure is unexpected. After 2 failures in a row show 'Portal layout may have changed' and keep the last good data. All selectors live in one file (parser/Selectors.kt).
18. Student details are inputs, never constants: USN, DOB, relation (1 father, 2 mother, 5 guardian) and the 4 digits.
19. Fixtures are kept per academic year in tests/fixtures/<year>/ so old layouts keep passing when the site changes.

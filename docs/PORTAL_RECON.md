# NIE Parent Portal — Reconnaissance Report (`docs/PORTAL_RECON.md`)

**Target:** `https://parents.nie.ac.in/` (Contineo / Joomla)  
**Captured Sources:** All 9 `.htm` files in `docs/` (`1ST PAGE.htm`, `LOGIN PAGE.htm`, `index.php.htm`, `HOME PAGE.htm`, `ATTENDANCE.htm`, `CIE.htm`, `LESSON PLAN.htm`, `EXAM HISTORY.htm`, `TIME TABLE.htm`)  
**Sanitized Fixtures:** `tests/fixtures/2026-27/`  
**Scope Note:** Per project rules, no AI, no calendar, no timetable (`TIME TABLE.htm` inspected for completeness only; out of scope), and no Events features.

---

## 1. Two-Stage Login Flow & Architecture Verdict

The portal uses a two-stage POST login flow to `index.php` (`option=com_user`).

### Stage 1 — USN & Date of Birth (`docs/1ST PAGE.htm`, `docs/LOGIN PAGE.htm`)
- **Form:** `form#login-form[action="index.php"][method="post"][onsubmit="return submitLogin()"]`
- **Visible Fields:**
  - `input#username[name="username"]` — Student USN (text)
  - `select#dd[name="dd"]` — Day (`01`..`31`, zero-padded)
  - `select#mm[name="mm"]` — Month (`01`..`12`, zero-padded)
  - `select#yyyy[name="yyyy"]` — Year (`1960`..`2026`)
- **Hidden Fields:**
  - `input#passwd[name="passwd"]` — Populated client-side by `submitLogin()`: formats `dob = yyyy + '-' + mm.trim() + '-' + dd.trim()`, appends 2 random alphanumeric characters (`randomstring(2)`) after every character of `dob`, and Base64-encodes via `btoa(randsting)`.
  - `input[name="remember"][value="No"]`
  - `input[name="option"][value="com_user"]`
  - `input[name="task"][value="loginOtp"]`
  - `input[name="return"][value="aW5kZXgucGhw"]`
  - `input#recaptcha-token[name="token"]` — Currently empty in Stage 1 (`grecaptcha.execute(..., {action: 'login'})` is commented out with a `TODO` comment, so it can be re-enabled server-side at any time).
  - `input[name="<CSRF_TOKEN>"][value="1"]` — 32-character hex Joomla CSRF token name.
- **Error Modal:** `#myloginModal` (`<h3>Login Failed</h3>`, `"You have entered an invalid username or password."`, `"0 attempts remaining"`).
- **CAPTCHA / OTP / Dynamic Token:**
  - CAPTCHA on Stage 1: **NO** (currently commented out, may be enabled anytime).
  - OTP: **NO**.
  - Dynamic CSRF token: **YES** (32-char hex input name).

### Stage 2 — Parent Mobile Last 4 Digits + reCAPTCHA v3 (`docs/index.php.htm`)
- **Form:** `form#login-form[action="index.php"][method="post"][onsubmit="return combineDigits();"]`
- **Visible Fields:**
  - `select#id-type-select[name="idType"]`:
    - `1` = Father Mobile Last 4 Digits
    - `2` = Mother Mobile Last 4 Digits
    - `5` = Guardian Mobile Last 4 Digits
  - 4 single-digit inputs: `input.digit-input[maxlength="1"][pattern="[0-9]"]`
- **Hidden Fields:**
  - `input#enteredid[name="enteredid"]` — Combined 4 digits populated by `combineDigits()`.
  - `input[name="option"][value="com_user"]`
  - `input[name="task"][value="login"]`
  - `input[name="remember"][value="No"]`
  - `input[name="username"]`, `input[name="passwd"]` (carried from Stage 1)
  - `input[name="return"]` (present twice in captured HTML)
  - `input[name="<CSRF_TOKEN>"][value="1"]`
  - `input#recaptcha-token[name="token"]` — Populated on `DOMContentLoaded` by Google reCAPTCHA v3 (`grecaptcha.execute(..., { action: 'result_form' })`).
  - `input[name="action"][value="result_form"]`
- **CAPTCHA / OTP / Dynamic Token:**
  - CAPTCHA on Stage 2: **YES** (Google reCAPTCHA v3 invisible, score-based).
  - OTP: **NO** (static parent mobile last-4-digits verification).
  - Dynamic CSRF token: **YES**.

### Architecture Verdict: **B — WebView for Login Only + OkHttp & Jsoup for Data**
- **Why B:** Stage 1 requires the page's own JavaScript (`submitLogin()`) to obfuscate the DOB into `#passwd`, and Stage 2 requires Google reCAPTCHA v3 (`grecaptcha.execute`) to populate `#recaptcha-token`. Running login in a real Android `WebView` lets the portal's own scripts generate valid tokens without faking or bypassing anything. Once logged in, cookies are flushed from `CookieManager` into OkHttp's cookie jar, and all data pages (`HOME PAGE`, `ATTENDANCE`, `CIE`, `LESSON PLAN`, `EXAM HISTORY`) are plain server-rendered HTML fetched via HTTP GET and parsed with Jsoup.
- **Fallback inside B:** If auto-submit is rejected, timed out, or triggers a visible challenge, display the WebView visibly with fields pre-filled so the student can tap Submit or complete the challenge.

### Session & Expiry
- Authenticated pages use Joomla session cookies (and internal `ksign` signed navigation parameters in embedded nav scripts).
- **Session Lifetime:** Unknown from static HTML captures; must be measured during Step 12 integration by logging timestamps only (never cookies).
- **Expired Session Signal:** Redirect to login (`#login-form` or `task=loginOtp` / `task=login`) instead of `task=dashboard` / expected data table.

---

## 2. Dashboard & Dynamic Course Discovery (`docs/HOME PAGE.htm`)

- **URL:** `https://parents.nie.ac.in/index.php?option=com_studentdashboard&controller=studentdashboard&task=dashboard`
- **Student & Term Header:**
  - `.cn-stu-data1 h3` — Student Name
  - `.cn-stu-data1 h2` — USN
  - `.cn-stu-data1 p` — Program, Semester, Section (e.g. `B.E-IS,  SEM 05,  SEC D`)
  - `p.cn-last-update` — `Last Updated On: DD/MM/YYYY`
- **Dynamic Course Table:** `table.cn-pay-table` (caption: `"Course registration - CIE and attendance status"`):
  - Rows in `table.cn-pay-table tbody tr` (10 courses in the 2026-27 Sem 5 capture; count and codes vary per student/semester):
    - `td:nth-child(1)` — Course Code (e.g. `BIS501`, `BIS516B`)
    - `td:nth-child(2)` — Course Name
    - `td:nth-child(3) a[href]` — Lesson Plan URL
    - `td:nth-child(4) a[href]` — Attendance URL
    - `td:nth-child(5) a[href]` — CIE URL
- **Home Chart Scripts (Billboard.js `bb.generate`):**
  - `#barPadding` script (`type: "bar"`): `columns: [["<CODE>", <cie_score>], ..., ,]` — Current CIE per course.
  - `#gaugeTypeMulti` script (`type: "gauge"`): `columns: [["<CODE>", <att_pct>], ..., ,]` — Attendance % per course.
  - **Note:** Both `columns` arrays contain trailing commas (`, ,]`), which are invalid strict JSON and must be parsed leniently.
  - `.cn-att-legend .cn-attend` — Portal's 85% threshold summary text (e.g. `"5 below 85% (...)"`).

---

## 3. Attendance Selectors & Present/Absent Tables (`docs/ATTENDANCE.htm`)

- **URL Pattern:** `index.php?option=com_studentdashboard&controller=studentdashboard&task=attendencelist&courseId=<id>&secId=<sec>&semId=<sem>`
- **Course Identity:** `h3.md-card-head-text span:first-of-type` -> `<COURSE_CODE> - <COURSE_NAME>` (e.g. `BIS501 - Software Engineering and Project Management`).
- **Summary Counts (`.cn-legend`):**
  - `span.cn-attend` -> `Present[28]`
  - `span.cn-absent` -> `Absent [7]` *(note space before `[`)*
  - `span.cn-still` -> `Still to go [13]` *(note space before `[`)*
  - Progress bar attributes (`.cn-stu-progress .sc-progress-bar[title]`): `Present:28`, `Absent:7`, `Still to go:13`.
- **Present Table:** `table.cn-attend-list1` (`caption` -> `Present Classes 28`):
  - Columns (`tbody tr td`): `Sl No`, `Date` (`DD-MM-YYYY`), `Time` (`HH:MM TO HH:MM`), `Status` (`Present`).
  - Row count in capture: `28`.
- **Absent Table:** `table.cn-attend-list2` (`caption` -> `Absent List Classes 7`):
  - Columns (`tbody tr td`): `Sl No`, `Date` (`DD-MM-YYYY`), `Time` (`HH:MM TO HH:MM`), `Status` (`Absent`).
  - Row count in capture: `7`.
- **Unit & Slot Verification:**
  - Attendance is counted in **classes / class slots** (explicitly labeled `Classes 28`, `Classes 7`), **not hours**.
  - Evidence: Row 2 in `table.cn-attend-list2` has time `16:00 TO 18:00` (a 2-hour slot), yet counts as **1 row** and **1** in `Absent [7]`.
  - Validation check confirmed: `Present (28) + Absent (7) = 35` equals total rows (`28 + 7 = 35`), and `28 / 35 = 80.0%` matches the dashboard gauge (`80%`).

---

## 4. CIE Assessment Data & `chartData` (`docs/CIE.htm`)

- **URL Pattern:** `index.php?option=com_studentdashboard&controller=studentdashboard&task=ciedetails&courseId=<id>&secId=<sec>&semId=<sem>`
- **Course Switcher Tabs:** `ul.uk-tab li a[onclick]` (contains `ciedetails` URLs for all registered courses).
- **Course Identity:**
  - `h3.md-card-head-text span:first-of-type` -> `<COURSE_CODE> - <COURSE_NAME>`
  - `table.cn-cie-table caption` -> `<COURSE_NAME>( <COURSE_CODE>)`
- **Summary Badges (`.cn-cie-legend`):**
  - `span.cn-color-green` -> `Final CIE Marks Obtained: 20`
  - `span.cn-color-red` -> `Attendance: 80%`
- **Summary Table (`table.cn-cie-table tbody tr td`):**
  - Assessment cells (generic, vary per course): `tst1 : -`, `tst2 : -`, `EL1 : -`, `QZ1 : -`
  - `Current CIE : 20`
  - `ATTENDANCE : 80%`
- **`chartData` Script Array (AmCharts 3.17.3):**
  - JavaScript variable `var chartData = [ { ... }, ... ];`
  - Keys per object:
    - `"xaxis"`: assessment label (e.g. `"tst1"`, `"tst2"`, `"EL1"`, `"QZ1"`)
    - `"col1"`: **Marks Obtained** (bound to AmGraph `"Marks Obtained"` `valueField = "col1"`, e.g. `20`)
    - `"col2"`: remaining marks (`maxmarks - col1`, e.g. `5`)
    - `"maxmarks"`: maximum marks (e.g. `25`, or `0` if not yet entered)
    - `"optainmarks"` / `"linevalue"`: **Class Average** (bound to AmGraph `"Class Average"` `valueField = "linevalue"`, `labelText = "[[optainmarks]]"`, e.g. `19` — note the misleading backend key name `optainmarks`).
  - **Trailing Commas:** Each object ends with `"linevalue":<n>,` (trailing comma), which is **not valid JSON** and must be parsed leniently.
- **Table vs. Chart Disagreement:**
  - In `docs/CIE.htm`, `table.cn-cie-table` shows `tst1 : -` (dash), while `chartData` has `"xaxis": "tst1", "col1": 20, "maxmarks": 25` and the summary row shows `Current CIE : 20`.
  - **Rule:** Parse `chartData` for per-assessment obtained/max marks (using `col1` and `maxmarks`), and cross-check `ATTENDANCE : <N>%` against the Attendance page's computed percentage (within 1 point; otherwise mark snapshot `SUSPECT`).

---

## 5. Lesson Plan Structure (`docs/LESSON PLAN.htm`)

- **URL Pattern:** `index.php?option=com_bvbsims&controller=uploadlession&task=getuploadlession&courseId=<id>&secId=<sec>&semId=<sem>&deptId=<dept>&streamId=<stream>`
- **Course Metadata Table (`div.cn-basic-details table.cn-stu-info`):**
  - `Stream:` (e.g. `B.E`)
  - `Department:` (e.g. `Information Science and Engineering`)
  - `Semester:` (e.g. `5`)
  - `Course Name:` (e.g. `Software Engineering and Project Management`)
  - `Course Code:` (e.g. `BIS501`)
- **Content State:** In `docs/LESSON PLAN.htm`, no lesson plan entries, tables, or file download links appear after `.cn-basic-details` inside `form#lessionUpload`. When no lesson plan is uploaded, store `'none uploaded'` (normal state per Rule 14).

---

## 6. Exam History & Semester Records (`docs/EXAM HISTORY.htm`)

- **URL:** `https://parents.nie.ac.in/index.php?option=com_history&task=getResult&usn=<USN>` (linked from top navbar `"Exam History"`).
- **Overall Credit Cards (`.detail3 .credits-sec1`):**
  - `Credits Earned So Far` (`h3` + `p`)
  - `Credits to be Earned`
  - `Credits required to earn degree`
  - `Batch` (e.g. `2024 - 2028`)
- **Per-Semester Blocks (`div.result-table`):**
  - Header (`div.cn-blocks h6`):
    - Text node: e.g. `Odd 2024-25 - Regular - Semester : 1`
    - `span.uk-label.cn-color-green` -> `Credits Registered : 20`
    - `span.uk-label.cn-bgcolor3` -> `Credits Earned : 20`
    - `span.uk-label.cn-bgcolor1` -> `SGPA: 8.05`
    - `span.uk-label.cn-bgcolor4.uk-hidden` -> `CGPA:` (hidden)
  - Grade Table (`table.res-table tbody tr`):
    - `td:nth-child(1)` — Course Code
    - `td:nth-child(2)` — Course Name
    - `td:nth-child(3)` — Credits Reg.
    - `td:nth-child(4)` — Credits Earned
    - `td:nth-child(5)` — GP (Grade Points)
    - `td:nth-child(6)` — Grade (e.g. `O`, `A+`, `A`, `B+`, `B`, `C`, `PP`)
  - Note: Pass/fail 0-credit courses (e.g. `BPEK359`, `BPEK459`) appear with `Credits Reg. = 0`, `Credits Earned = 0`, `GP = 0`, `Grade = PP`.

---

## 7. Dynamic Academic-Year & Semester Identifiers

Nothing about year, semester, department, or course is static:
- `semId` (e.g. `43` in the 2026-27 Sem 5 capture) is assigned per semester by Contineo and appears in all course links (`attendencelist`, `ciedetails`, `getuploadlession`).
- Semester number (`SEM 05` / `Semester: 5`) and section (`SEC D`) appear in `.cn-stu-data1 p` on the dashboard and `table.cn-stu-info` on Lesson Plan.
- `courseId` is unique per course offering per term.
- `secId` is the section ID (e.g. `692`), and **can be empty** (`secId=`) for electives (e.g. `BIS516B`).
- `deptId` (e.g. `16`, `12`, `30`) and `streamId` (e.g. `1`) vary per course/department (especially open/inter-departmental courses like `BESK508`, `BPEK559`).
- Alternate term portals may be linked from the login page (`/parentseven/`, `/parentssupply/`, `/parentssupplypart2/`).

---

## 8. Course-Specific Navigation Links & Parameters

Every link must be taken verbatim from `table.cn-pay-table` on `HOME PAGE.htm`:

| Page | `option` | `controller` | `task` | Query Parameters |
| :--- | :--- | :--- | :--- | :--- |
| Dashboard | `com_studentdashboard` | `studentdashboard` | `dashboard` | — |
| Attendance | `com_studentdashboard` | `studentdashboard` | `attendencelist` | `courseId`, `secId` (may be empty), `semId` |
| CIE | `com_studentdashboard` | `studentdashboard` | `ciedetails` | `courseId`, `secId` (may be empty), `semId` |
| Lesson Plan | `com_bvbsims` | `uploadlession` | `getuploadlession` | `courseId`, `secId` (may be empty), `semId`, `deptId`, `streamId` |
| Exam History | `com_history` | — | `getResult` | `usn` |

---

## 9. Exact Selectors & Extraction Patterns (`parser/Selectors.kt`)

| Page | Target Field | CSS Selector / Regex Pattern | Changes Per Semester / Student |
| :--- | :--- | :--- | :--- |
| Login 1 | Form & inputs | `form#login-form`, `#username`, `#dd`, `#mm`, `#yyyy`, `#passwd`, `input[name="task"][value="loginOtp"]` | CSRF token name changes per session |
| Login 2 | Relation, digits, reCAPTCHA | `#id-type-select`, `.digit-input`, `#enteredid`, `#recaptcha-token`, `input[name="task"][value="login"]` | `idType` & digits per student; token per load |
| Home | Student USN | `.cn-stu-data1 h2` | Per student |
| Home | Semester / Section | `.cn-stu-data1 p` (regex `SEM\s*(\d+)`) | Per student & semester |
| Home | Course rows | `table.cn-pay-table tbody tr` | Per student & semester |
| Home | Code / Name / Links | `td:nth-child(1)`, `td:nth-child(2)`, `td:nth-child(3) a[href]`, `td:nth-child(4) a[href]`, `td:nth-child(5) a[href]` | Per student & semester |
| Home | Quick CIE / Att % | `<script>` with `bindto:\s*"#barPadding"` / `"#gaugeTypeMulti"` -> `columns:\s*(\[.*?\])` | Per student & semester |
| Attendance | Course verification | `h3.md-card-head-text span` | Per course |
| Attendance | Present / Absent / Still to go | `.cn-legend .cn-attend` (`Present\s*\[(\d+)\]`), `.cn-absent` (`Absent\s*\[(\d+)\]`), `.cn-still` (`Still to go\s*\[(\d+)\]`) | Per student & course |
| Attendance | Present / Absent rows | `table.cn-attend-list1 tbody tr`, `table.cn-attend-list2 tbody tr` | Per student & course |
| CIE | Course verification | `h3.md-card-head-text span`, `table.cn-cie-table caption` | Per course |
| CIE | Summary badges | `.cn-cie-legend .cn-color-green`, `.cn-cie-legend .cn-color-red` | Per student & course |
| CIE | Summary table cells | `table.cn-cie-table tbody tr td` (`Current CIE\s*:\s*(\d+)`, `ATTENDANCE\s*:\s*(\d+)%`) | Assessment names vary per course |
| CIE | `chartData` | `<script>` regex `var\s+chartData\s*=\s*(\[[\s\S]*?\]);` -> `xaxis`, `col1`, `maxmarks`, `optainmarks` | Assessment labels & counts vary per course |
| Lesson Plan | Course metadata | `div.cn-basic-details table.cn-stu-info tbody tr td` | Per course & department |
| Exam History | Semester blocks | `div.result-table` | Per student & completed semesters |
| Exam History | Semester header & SGPA | `div.cn-blocks h6`, `span.cn-color-green`, `span.cn-bgcolor3`, `span.cn-bgcolor1` | Per semester |
| Exam History | Course grade rows | `table.res-table tbody tr` (`td:nth-child(1..6)`) | Per semester |

---

## 10. Assumptions That Could Break Across Academic Years, Departments, or Semesters

1. **reCAPTCHA v3 on Stage 1:** Currently commented out with a `TODO`; if enabled, pure HTTP Stage 1 would fail, validating our choice of **Architecture B** (WebView login).
2. **Empty `secId` on Electives:** `BIS516B` has `secId=` in all its links; parsers/fetchers must never require `secId` to be non-empty or numeric.
3. **Missing Buttons or Empty Pages for Labs/Projects:** Labs or project courses may omit Lesson Plan or CIE data, or have `0` classes conducted (must display `'No data'`, never `0%`).
4. **Varying CIE Assessment Labels:** `tst1`, `tst2`, `EL1`, `QZ1` are course-specific; other courses/departments may use `CIE1`, `LAB`, `Assign`, etc. Must parse `chartData` generically.
5. **CIE Table vs. `chartData` Disagreement:** Table cells can show `-` while `chartData` and `Current CIE` hold actual marks (`20/25`).
6. **Non-Strict JSON in `chartData` and Home Charts:** Trailing commas (`,]` and `,}`) will crash strict JSON parsers; lenient stripping/parsing is mandatory.
7. **`optainmarks` Key Semantics:** In `chartData`, `col1` is the student's obtained marks, while `optainmarks` / `linevalue` is the **Class Average**.
8. **Attendance Slot Duration vs. Count:** A 2-hour slot (`16:00 TO 18:00`) is 1 row and 1 class unit; do not compute hours from time strings.
9. **Term Dropoff on Dashboard:** Contineo drops older semesters from `HOME PAGE` when a new `semId` begins; older terms must be retained read-only in Room.
10. **Alternate Term Sub-Portals:** Contineo may host Even/Supply terms on sub-paths (`/parentseven/`, `/parentssupply/`) if the main root changes.
11. **Layout Changes:** CSS classes (`cn-pay-table`, `cn-attend-list1`, `cn-attend-list2`, `cn-cie-table`, `res-table`) could change in future Contineo updates; all selectors must live in `parser/Selectors.kt` with 2-failure change detection.

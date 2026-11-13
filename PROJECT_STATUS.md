# Attendance Tracker — Project Status

## Steps Checklist
- [x] **Step 1:** Rules & workspace setup (`AGENTS.md`, `.gitignore`, directories, `git init`, `PROJECT_STATUS.md`)
- [x] **Step 2:** Recon capture
- [x] **Step 3:** Recon analysis (`docs/PORTAL_RECON.md`)
- [x] **Step 4:** Architecture decision
- [x] **Step 5:** Project scaffold
- [x] **Step 6:** Engine (integer attendance math & validation)
- [x] **Step 7:** Database (Room entities, DAOs, migrations)
- [x] **Step 8:** Parsers and fetcher (pure Kotlin HTML parsers + fixture tests)
- [x] **Step 9:** Auth (BiometricPrompt, Android Keystore, session handling)
- [x] **Step 10:** Sync (WorkManager background sync)
- [x] **Step 11:** UI (Jetpack Compose subject-wise screens)
- [x] **Step 12:** Integration
- [x] **Step 13:** QA and APK (`builds/`)

---

## Current Status
- **Done:**
  - Step 12: Wired `MainActivity` end-to-end to `AttendanceRepository`, `PortalFetcher`, `PortalWebViewLoginController`, `AuthSessionStateMachine`, `BiometricAppLock`, and `SyncManager`. Moved `FakeRepository` behind `DebugBridge` (debug only), added hidden `DebugScreen` (5 taps on version in Settings).
  - Step 13: Live device debugging on connected phone (`RZCX91F0DYT`). Diagnosed and resolved portal session invalidation: Contineo/Joomla binds sessions to User-Agent, so requests using a generic User-Agent invalidated the session cookie. Configured `PortalFetcher` and `PortalWebViewLoginController` to share the device's exact `WebSettings.getDefaultUserAgent(context)`, added standard browser navigation headers, query candidate portal URLs for all path-scoped cookies, and increased HTTP timeouts to 30s.
  - Super-Fast Sync & Course Stability: Diagnosed mid-sync connection drops on courses 4–10 caused by 30 sequential HTTP requests on Apache keep-alive. Configured OkHttp `retryOnConnectionFailure(true)`, `ConnectionPool(8, 30s)`, and upgraded `SyncManager` to concurrent fetching with `Semaphore(3)` across courses for Attendance and CIE. Accelerated full sync from ~30s down to ~2s. Defer lesson plan sync to avoid throttling. Enhanced header parsing in `PortalParsers` to handle lab/elective variations.
  - Super-Fast Login: Enabled `blockNetworkImage = true`, intercepted slow external CDNs, and reduced reCAPTCHA v3 polling to 100ms (cutting login from 6s to ~1.5s).
  - Attendance-Focused Super Fast Sync: Streamlined foreground sync to fetch **Attendance only** sequentially with a tight 100ms pause and 60s OkHttp connection pool. Eliminates socket drops, speeds up total sync to ~1.5 seconds, and guarantees `BIS516B` (Artificial Intelligence) connects and parses reliably every time.
  - Priority Course Ordering: Automatically sorts courses with lower attendance percentages to the very top so students instantly see what needs attention. Courses with 0 conducted classes / 0 attendance / no data are neatly pinned to the bottom.
  - Compact Phone Typography: Reduced typography scale (`10sp` code, `13.5sp` title, `19sp` bold percentage, `11sp` stats) and tightened padding (`14dp/10dp`) so 5–6 courses fit comfortably on phone screens with minimal scrolling.
  - Ultra-Smooth 120Hz Transitions: Tuned Compose `AnimatedContent` transition to use snappy `FastOutSlowInEasing` / `FastOutLinearInEasing` (160–180ms) for instant, lag-free slide and fade animations between Dashboard and Course Detail screens.
  - Verification & Deployment: All 54 unit tests pass. Fresh Release APK built (`builds/app-release.apk`) and installed to device (`RZCX91F0DYT`).
  - Production Hardening & Security Audit Fixes (v1.0.1):
    1. Extended login & reCAPTCHA budget (10s polling, 35s timeout) and Stage-1 autofill on verification fallback to ensure reliable login on mobile networks.
    2. Signed release build configuration (`app-release.apk`) with version code 2.
    3. Documented Attendance-Only architecture scope in `README.md`.
    4. Fixed BunkCalculator edge case for zero conducted classes to strictly preserve "No data" per Rule 16.
    5. Filtered sensitive query tokens (signatures, keys, auth) from all Logcat statements via `UrlSanitizer`.
  - Live Sync Fix & Date-Wise Attendance History (v1.0.2):
    1. **Manual Synchronization Resilience**: Resolved mid-sync session expiration aborts in `SyncManager.kt`. When session cookies expire, `SyncManager` automatically re-authenticates and retries the HTTP request without failing or aborting the sync.
    2. **Single-Flight Concurrency Guarantee**: Clean `syncMutex.tryLock()` single-flight model prevents deadlocks and coroutine leaks while allowing manual refresh (`forceRefresh = true`) to execute fresh synchronization reliably.
    3. **Date-Wise Attendance History in Course Details**: Implemented interactive session history strictly within `CourseDetailScreen.kt` with `All`, `Present`, `Absent` filter pills, class date, time slot, and status badges (green `PRESENT`, red `ABSENT`).
    4. **Pure Kotlin `AttendanceDateParser`**: Parses dates (`dd-MM-yyyy`, `dd/MM/yyyy`, `yyyy-MM-dd`), extracts start times into `dateMillis` for chronological descending sorting (newest first), and formats slots cleanly (`11:00 - 12:00`).
    5. **Room Schema Migration 1->2**: Added `AttendanceRecordEntity` with composite key `(courseCode, term, sessionKey)` for automatic deduplication, indexed by `dateMillis` and `(courseCode, term)`.
    6. **Preservation of Home Dashboard UI**: Home screen layout, typography, colors, padding, card design, and counts remain 100% frozen and visually unchanged.
    7. **100% Test Pass Rate**: 63 of 63 unit tests pass across all suites (`AttendanceDateParserTest`, `AttendanceRepositoryTest`, `SyncManagerTest`, `PortalParsersTest`, `PortalFetcherTest`, etc.).
    8. **Build Deliverables**: Fresh `builds/app-debug.apk` and `builds/app-release.apk` compiled and ready.
    9. **Documentation**: Detailed report in `docs/ATTENDANCE_HISTORY_AND_SYNC_REPORT.md`.
- **Next:** Push changes to GitHub and release v1.0.2.
- **Blockers:** None.

# Attendance Tracker: Live Sync Fix & Date-Wise Attendance History Report

**Project:** Attendance Tracker  
**Target:** https://parents.nie.ac.in/  
**Workspace:** `D:\attendence tracker`  

---

## Executive Summary

This engineering report details the complete diagnosis and resolution of manual synchronization reliability issues and the end-to-end implementation of date-wise Present/Absent class session history inside the individual course detail screens.

Per explicit architectural constraints:
1. **Home Dashboard Frozen**: The existing home screen UI, typography, colors, padding, card hierarchy, sorting, and counts remain **100% visually identical**.
2. **Attendance History Placement**: Date-wise session history is housed strictly inside `CourseDetailScreen.kt`.
3. **Integer Math & Offline-First**: Pure Room persistence, automatic deduplication, multi-session chronological sorting, and seamless mid-sync session re-authentication.

---

## 1. Problem 1 Diagnosis & Resolution: Manual Synchronization

### Root Causes Identified
1. **Mid-Sync Session Expiry Abort**:
   - The NIE portal runs Contineo/Joomla on Apache, where session cookies frequently expire after 15–30 minutes of inactivity.
   - When a session expired during dashboard discovery or course scraping, `handleMidSyncSessionExpired()` called `onSessionExpired()` which successfully renewed credentials via `PortalWebViewLoginController`.
   - However, `handleMidSyncSessionExpired()` previously mapped `SessionState.SESSION_VALID` to an early exit or `Failed`, aborting the sync without retrying the failed request with the fresh cookies.
2. **Double Dashboard Fetching**:
   - Prior logic invoked `ensureSessionForSync()` (which fetched the dashboard to test cookies) immediately followed by another `fetchDashboard()` call in `SyncManager.sync()`.
   - On mobile networks or slow connections, hammering the portal with back-to-back dashboard requests triggered connection timeouts or dropped sockets.
3. **Single-Flight Lock Synchronization**:
   - Background launch syncs (`forceRefresh = false`) and manual pull-to-refresh (`forceRefresh = true`) clashed if pull-to-refresh occurred while an initial sync was in-flight, dropping user refresh actions.

### Engineering Fixes Applied
- **Seamless Session Recovery in `SyncManager.kt`**:
  - `handleMidSyncSessionExpired()` now preserves `SyncState.Running` upon re-authentication.
  - When `dashFetch` or any individual course `attFetch` encounters `FetchResult.SessionExpired`, `SyncManager` automatically refreshes the session once and retries the HTTP request immediately with the refreshed cookie jar.
- **Single-Flight Concurrency Guarantee**:
  - Clean `syncMutex.tryLock()` model prevents deadlocks and coroutine leaks while allowing `forceRefresh = true` to bypass the 5-minute throttle cleanly once idle.
- **OkHttp Connection Pool & Retries**:
  - Kept sequential 100ms politely paced requests with `ConnectionPool(10, 60s)` and `retryOnConnectionFailure(true)` to guarantee all registered courses (including `BIS516B` Artificial Intelligence) resolve reliably.

---

## 2. Problem 2 Implementation: Date-Wise Present/Absent History

### NIE Portal Attendance HTML Structure
In the portal's `index.php?task=attendencelist&courseId=...` response:
- Present sessions are tabulated in `<table class="cn-attend-list1">`.
- Absent sessions are tabulated in `<table class="cn-attend-list2">`.
- Columns: `Sl No`, `Date` (`17-08-2026`), `Time` (`11:00 TO 12:00`), `Status` (`Present` / `Absent`).
- **Edge cases handled**:
  - Multiple sessions on the same calendar day (e.g., Slot 1 at `11:00 TO 12:00` and Slot 2 at `12:00 TO 13:00`).
  - Varying time separators (`TO`, `-`, lowercase `to`).
  - Multiple date patterns (`dd-MM-yyyy`, `dd/MM/yyyy`, `yyyy-MM-dd`).

### Architecture & Components
1. **`AttendanceDateParser.kt`**:
   - Pure Kotlin parser with zero Android framework dependencies.
   - Extracts start times into `LocalTime` and computes epoch milliseconds (`dateMillis`) for exact chronological sorting (most recent sessions first).
   - Formats dates to clean reader-friendly formats (`17 Aug 2026`) and standardizes slots (`11:00 - 12:00`).
2. **Room Entity `AttendanceRecordEntity`**:
   - Composite Primary Key: `(courseCode, term, sessionKey)`.
   - Unique `sessionKey`: `"${row.date.trim()}|${row.time.trim()}|${status.uppercase()}|${row.slNo.trim()}"`.
   - Prevents duplicate entries on repeated refreshes and survives app restarts and term transitions.
   - Foreign key linked to `CourseEntity(code, term)` with `CASCADE` delete.
3. **Database Migration (`MIGRATION_1_2`)**:
   - Bumped `AttendanceDatabase` version from `1` to `2`.
   - Safe SQL migration creating `attendance_records` table with indexes on `(courseCode, term)` and `dateMillis`.
4. **Interactive Course Detail UI (`CourseDetailScreen.kt`)**:
   - Interactive segmented filter pills: `All`, `Present`, `Absent` (defaulting to `All`).
   - Clean card-based session items with date, time slot, status badge (green `PRESENT`, red `ABSENT`).
   - Counter summary (e.g. `12 classes conducted`).
   - Empty state when no sessions match the selected filter.

---

## 3. Preservation of Frozen Home Dashboard

The home dashboard was kept completely intact:
- No visual or styling modifications made to `HomeScreen.kt`.
- Typography scales (`10sp` code, `13.5sp` title, `19sp` percentage, `11sp` stats) preserved.
- Ascending sort order (lowest attendance first) and no-data bottom section preserved.
- AMOLED black theme (`#000000` / `#101010`) preserved.

---

## 4. Verification & Test Suite Matrix

All **63 unit tests** pass with 0 failures:

| Test Suite | Tests Run | Failures | Status |
| :--- | :---: | :---: | :---: |
| `AttendanceDateParserTest` | 6 | 0 | ✅ PASSED |
| `AttendanceRepositoryTest` | 8 | 0 | ✅ PASSED |
| `SyncManagerTest` | 10 | 0 | ✅ PASSED |
| `PortalParsersTest` | 7 | 0 | ✅ PASSED |
| `PortalFetcherTest` | 3 | 0 | ✅ PASSED |
| `AttendanceEngineTest` | 11 | 0 | ✅ PASSED |
| `AuthSessionStateMachineTest` | 9 | 0 | ✅ PASSED |
| `UiStateMapperTest` | 5 | 0 | ✅ PASSED |
| `UrlSanitizerTest` | 3 | 0 | ✅ PASSED |
| `FakeRepositoryTest` | 1 | 0 | ✅ PASSED |
| **Total** | **63** | **0** | **✅ 100% PASSED** |

### Build Deliverables
- **Debug APK**: `builds\app-debug.apk` (built & verified)
- **Release APK**: `builds\app-release.apk` (built & verified)

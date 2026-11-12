# Environment Setup Report: D: Drive Configuration

**Project:** Attendance Tracker  
**Date:** November 12, 2026  
**Workspace:** `D:\attendence tracker`  

---

## 1. Executive Summary

Stage 2 (D: Drive Environment Configuration) has been completed successfully. 
To protect the constrained C: drive (2.93 GB free), the Android SDK, Gradle user home cache, Android user home, and user-level temporary files have been offloaded to D: (264.92 GB free). 

All operations adhered strictly to safety constraints:
- Safe file transfer via `robocopy` with complete directory, file count, and byte parity verified.
- Original files on `C:\Users\ashit\AppData\Local\Android\Sdk` were retained intact (no deletions).
- No new packages were downloaded.
- Java 17 and existing PATH entries remained completely untouched.
- No application source code was generated.

---

## 2. Task 1: Android SDK Migration Results

### Source SDK Pre-Migration Inspection
- **Source Location:** `C:\Users\ashit\AppData\Local\Android\Sdk`
- **Active Process Check:** Verified no active Android processes (`adb`, `emulator`, `studio64`, `java`) were holding locks on SDK files.
- **Source Size:** ~2.83 GB (3,044,134,891 bytes)
- **Directory / File Count:** 12,078 directories, 55,235 files
- **Installed Platforms:** `android-33`, `android-34`, `android-35`, `android-36`
- **Installed Build-Tools:** `36.0.0`
- **Platform-Tools:** Present (`adb.exe` v1.0.41, fastboot, etc.)
- **Command-line Tools:** `cmdline-tools\latest` present (`sdkmanager.bat`, etc.)

### Migration & Verification
- **Target Location:** `D:\Android\Sdk`
- **Copy Mechanism:** Multi-threaded `robocopy` with retry controls (`/E /R:3 /W:1 /MT:8`).
- **Post-Copy Verification:**
  - `robocopy /L /E ...`: Total 12,078 dirs, 55,235 files, 3,044,134,891 bytes.
  - **Mismatches / Failed:** 0
  - **Extras / Skipped:** 0
  - Byte-for-byte exact duplicate established on D:.

---

## 3. Task 2: Environment Variables Configuration

The following user-level environment variables were set and verified:

| Variable | User-Level Value | Purpose |
| :--- | :--- | :--- |
| `ANDROID_HOME` | `D:\Android\Sdk` | Primary Android SDK pointer |
| `ANDROID_SDK_ROOT` | `D:\Android\Sdk` | Legacy/Tool compatibility SDK pointer |
| `ANDROID_USER_HOME` | `D:\Android\UserHome` | Redirects `.android` configuration, avd definitions, and metrics away from `C:\Users\ashit` |
| `GRADLE_USER_HOME` | `D:\Gradle\.gradle` | Redirects all Gradle dependency caches, wrappers, and daemon logs away from `C:\Users\ashit\.gradle` |
| `TEMP` | `D:\Temp` | User temporary directory redirected to D: |
| `TMP` | `D:\Temp` | User temporary directory redirected to D: |

### Directories Created on D:
- `D:\Android\UserHome`
- `D:\Gradle\.gradle`
- `D:\Temp`

### Unchanged System & Tool Configurations
- `JAVA_HOME`: Preserved at `C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot\`
- `PATH`: No existing paths were overwritten.

---

## 4. Task 3: Comprehensive Verification Matrix

| Verification Item | Tested Path / Command | Result | Details |
| :--- | :--- | :--- | :--- |
| **SDK Accessibility** | `Test-Path D:\Android\Sdk` | **PASS** | Directory exists and accessible |
| **Build Tools (aapt2)** | `D:\Android\Sdk\build-tools\36.0.0\aapt2.exe version` | **PASS** | `Android Asset Packaging Tool (aapt) 2.20-13193326` |
| **Platform Tools (adb)**| `D:\Android\Sdk\platform-tools\adb.exe version` | **PASS** | `Android Debug Bridge version 1.0.41 (37.0.0-14910828)` |
| **Android Platforms** | `android.jar` in `android-33`, `34`, `35`, `36` | **PASS** | All four platform jars verified accessible |
| **Command-line Tools** | `D:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat` | **PASS** | Accessible |
| **Gradle User Home** | `[Environment]::GetEnvironmentVariable('GRADLE_USER_HOME', 'User')` | **PASS** | Set to `D:\Gradle\.gradle` |
| **Android User Home** | `[Environment]::GetEnvironmentVariable('ANDROID_USER_HOME', 'User')` | **PASS** | Set to `D:\Android\UserHome` |
| **TEMP / TMP** | `[Environment]::GetEnvironmentVariable('TEMP', 'User')` | **PASS** | Set to `D:\Temp` |
| **Java 17 Execution** | `java -version` | **PASS** | `OpenJDK Runtime Environment Microsoft-13106358 (build 17.0.18+8-LTS)` |
| **Git Execution** | `git --version` | **PASS** | `git version 2.52.0.windows.1` |

---

## 5. Storage Status & Remaining C: Dependencies

### Disk Space Comparison
- **C: Drive:** 2.93 GB free (164.17 GB used)
- **D: Drive:** 264.92 GB free (42.70 GB used)

### Remaining C: Dependencies to Keep in Mind
1. **JDK 17 Installation:** Stored in `C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot` (~350 MB static read-only footprint). Does not grow during builds.
2. **Git Installation:** Stored in `C:\Program Files\Git` (read-only static footprint).
3. **Original Android SDK on C:** Intact at `C:\Users\ashit\AppData\Local\Android\Sdk`. Once you verify everything is running smoothly from D:, you may elect to manually delete the original C: SDK folder to immediately recover ~2.83 GB on C:.

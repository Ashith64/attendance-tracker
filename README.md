# 📱 Attendance Tracker (NIE Parent Portal)

A native Android application built with **Kotlin, Jetpack Compose, Room, OkHttp, and Android Keystore** that provides fast, subject-wise attendance tracking, bunk calculations, and biometric security for students of the **National Institute of Engineering (NIE)** parent portal.

---

## ✨ Features

- **⚡ Super Fast Foreground Sync (~1.5s)**: Streamlined HTTP fetching with OkHttp connection pooling, delivering instant, reliable scraping without server session drops.
- **🎯 Focus on Attendance**: Clean, subject-wise metrics — no clutter or irrelevant features.
- **📊 Subject Priority Ordering**:
  - Courses with lower attendance percentages are automatically sorted to the top so you immediately see courses needing attention.
  - Courses with no conducted classes or no records are neatly organized in a separate bottom section.
- **📐 Compact Phone UI**: AMOLED pure black design (`#000000` / `#101010`) proportioned for mobile screens with minimal vertical scrolling.
- **🧈 Buttery-Smooth 120Hz Transitions**: Snappy horizontal slide and fade screen animations between the Dashboard and Course Details.
- **🧮 Offline Bunk Calculator**: Deterministic integer-math bunk simulation for standard target thresholds (65%, 75%, 80%, 85%).
- **🔒 Biometric Security**: BiometricPrompt & Android Keystore integration for secure offline dashboard locking.
- **🌐 100% Dynamic Discovery**: Works across semesters and academic years without hardcoded course codes, department IDs, or semester IDs. Fully supports elective subjects like **Artificial Intelligence (`BIS516B`)**.

---

## 📥 Download APK

You can download the latest pre-built signed Release APK (`app-release.apk`) directly from the **[GitHub Releases](https://github.com/Ashith64/attendance-tracker/releases)** section.

---

## 🎯 Architecture Scope: Attendance Focused

Per the core product directive, the application focuses strictly on **instantaneous, rock-solid attendance tracking**:
- **Omitted Features**: CIE marks and lesson plans have been omitted by design. By removing secondary endpoints, network load on `parents.nie.ac.in` was cut by ~70%, preventing mid-sync connection drops and enabling 100% stable sync even on unstable mobile data.
- **Privacy & Security**: Zero cloud backend, zero third-party analytics. AES-256 encrypted credentials in Android Keystore, and sensitive URL query parameters (signatures, tokens, credentials) are stripped before logging or database persistence.
- **Connection Resilience**: 12-second reCAPTCHA latency budget and automated Stage-1 credential autofill for smooth operation on cellular networks.

---

## 🛠️ Tech Stack & Architecture

- **Language**: Kotlin 2.0+
- **UI**: Jetpack Compose & Material 3
- **Database**: Room Database with composite term & course keys
- **Network**: OkHttp 4.12 with persistent cookie persistence & session reuse
- **HTML Parsing**: Jsoup pure Kotlin parsers
- **Security**: Android Keystore AES-256-GCM + BiometricPrompt
- **Background Work**: WorkManager periodic sync

---

## 🏗️ Building from Source

### Prerequisites
- Android SDK (API 35, Build Tools 35.0.0)
- Java 17+

### Build Commands
```bash
# Clone the repository
git clone https://github.com/Ashith64/attendance-tracker.git
cd attendance-tracker

# Run unit tests
./gradlew testDebugUnitTest

# Assemble Release APK (signed)
./gradlew assembleRelease
```
The output APK will be located at `app/build/outputs/apk/release/app-release.apk` (and `builds/app-release.apk`).

---

## 📄 License
This project is open-source and intended for student personal use.

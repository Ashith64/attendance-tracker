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

You can download the latest pre-built APK directly from the **[GitHub Releases](https://github.com/Ashith64/attendance-tracker/releases)** section.

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

# Assemble Debug APK
./gradlew assembleDebug
```
The output APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License
This project is open-source and intended for student personal use.

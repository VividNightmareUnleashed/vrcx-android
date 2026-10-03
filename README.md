<div align="center">

# VRCX Android

A native Android companion app for VRChat social tracking, alerts, world browsing, and account utilities.

[![Release](https://img.shields.io/badge/release-1.7.0-blue)](https://github.com/VividNightmareUnleashed/vrcx-android/releases/tag/v1.7.0)
[![VirusTotal Scan](https://img.shields.io/badge/VirusTotal-1.7.0%20scan-394EFF?logo=virustotal&logoColor=white)](https://www.virustotal.com/gui/file/1c559a6b71ff6c19a6bdf73d5cadba3255b47ca4b481c8e0f3745cb6229e38d1)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](https://developer.android.com/compose)

</div>

> [!NOTE]
> This branch is the 1.x app, kept for reference and no longer updated. VRCX Android 2.0 on the
> [main branch](https://github.com/VividNightmareUnleashed/vrcx-android) replaces it and installs over 1.7.0.

## What is this?

VRCX Android brings the everyday companion workflows of desktop [VRCX](https://github.com/vrcx-team/VRCX) to your phone. It talks directly to the VRChat API and WebSocket pipeline so you can keep an eye on friends, worlds, invites, notifications, favorites, and account activity without needing your PC nearby.

This is a companion app, not a replacement for the VRChat game client. It helps you monitor and manage your VRChat account from Android; it does not render or join VRChat worlds itself.

## Highlights

### Stay close to friends

- Live friends list with platform, status, trust rank, last-seen, VIP filters, and current-world context.
- Real-time activity feed for online/offline, status, GPS, travel, and friend events, with deduplication to keep noisy reconnects readable.
- Friends Locations and Friends Roster views for quickly finding who is online, where they are, and which instances are active.
- Friend Log for adds, removals, display-name changes, and trust-rank changes.
- Per-friend Android notifications, invite handling, and friend-request management.

### Browse VRChat from mobile

- Search users, worlds, avatars, and groups with paginated results and useful filters.
- World detail pages with images, descriptions, platform support, tags, capacity, and active instances.
- Instance actions for self-invite, copy URL, and share, useful when moving between phone and headset.
- Avatar browsing, filtering, details, selecting, and favoriting.
- Favorites for friends, worlds, and avatars with names, images, and fast cache-backed loading.

### Keep your account organized

- Unified V1/V2 notifications inbox with local persistence so recent notifications appear quickly on cold start.
- Profile editing for status, status description, bio, and pronouns.
- User profiles with groups, worlds, notes, favorite status, invites, and guarded destructive actions.
- Groups, group posts, and permission-gated member removal for admins.
- Gallery browsing and uploads, moderation tools, dashboard summaries, charts, and deep links from `vrcx://` and `https://vrchat.com/home/...`.
- Material 3 dark/light themes, dynamic colors, custom wallpaper support, and background WebSocket service controls.

## Download

The last 1.x APK is attached to the [1.7.0 release](https://github.com/VividNightmareUnleashed/vrcx-android/releases/tag/v1.7.0).

Requirements:

- Android 8.0 or newer, API 26+
- A VRChat account

To check the 1.7.0 download:

- APK: `vrcx-android-1.7.0.apk`
- SHA-256: `1c559a6b71ff6c19a6bdf73d5cadba3255b47ca4b481c8e0f3745cb6229e38d1`
- VirusTotal: [public report](https://www.virustotal.com/gui/file/1c559a6b71ff6c19a6bdf73d5cadba3255b47ca4b481c8e0f3745cb6229e38d1)
- Scanned on 2026-08-30: 0 malicious and 0 suspicious detections

## Build from source

### Prerequisites

- JDK 17
- Android SDK API 35
- `ANDROID_HOME` set to your Android SDK path

### Debug build

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
```

On Windows PowerShell:

```powershell
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"
.\gradlew.bat assembleDebug
```

The settings script permits one Gradle build at a time per checkout, including
wrapper, IDE, and Tooling API builds. Wait for the active build to finish if a
second invocation reports that another build is running.

The debug APK is written to:

```text
app/build/outputs/versioned-apk/debug/vrcx-android-<version>.apk
```

### Quality checks

```bash
./gradlew spotlessCheck :app:detektDebug :app:detektDebugUnitTest test :app:koverVerifyDebug :app:lint :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests "com.vrcx.android.data.repository.AuthRepositoryTest"
./gradlew spotlessApply
./gradlew :app:koverHtmlReportDebug
```

Spotless with ktlint enforces formatting, detekt performs Kotlin static analysis,
Kover enforces a 56% debug line-coverage floor, and Android lint treats
deterministic warnings as errors. The checks for newer dependencies and target
SDKs only warn, since new versions come out whatever the code does.
Both Detekt baselines are empty. CI fails on new findings and on new baseline
entries; removing entries is allowed.

### Release builds

Release signing reads its settings from environment variables. Generate your own keystore:

```bash
keytool -genkeypair -v -keystore release-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias vrcx-android -storepass YOUR_PASSWORD -keypass YOUR_PASSWORD
```

Then set the password and build the signed release:

```bash
export VRCX_KEYSTORE_PASSWORD=YOUR_PASSWORD
./gradlew assembleRelease
```

`VRCX_KEYSTORE_FILE` and `VRCX_KEY_ALIAS` point the build at another keystore or alias than
`release-keystore.jks` and `vrcx-android`. Do not commit keystores or passwords.

## Architecture

- Kotlin single-activity Android app
- Jetpack Compose and Material 3 UI
- MVVM with Hilt dependency injection
- Retrofit, OkHttp, and Kotlinx Serialization for VRChat REST APIs
- Dedicated OkHttp WebSocket client for the VRChat pipeline
- Room for account-scoped local data, with committed migration schemas
- DataStore for preferences and platform Keystore/JCA with atomic files for session storage; AndroidX Security Crypto remains read-only for legacy-data migration
- Coil 3 for authenticated image loading
- WorkManager and a foreground service for background reconnect and notifications
- JUnit, Robolectric, Mockito, MockWebServer, and Room migration tests

Kotlin sources live in `app/src/main/kotlin/com/vrcx/android/`. Unit tests live in `app/src/test/kotlin/`.

## Credits

Developer: [AyaDreamsOfYou](https://x.com/AyaDreamsOfYou)

Inspired by [VRCX](https://github.com/vrcx-team/VRCX), the open-source VRChat companion app for desktop.

## Disclaimer

This is an independent project. It is not affiliated with or endorsed by the [VRCX Team](https://github.com/vrcx-team/VRCX) or VRChat Inc.

"VRChat" is a trademark of VRChat Inc. Use of the VRChat API is subject to the [VRChat Terms of Service](https://hello.vrchat.com/legal).

This software is provided as-is with no guarantee of functionality.

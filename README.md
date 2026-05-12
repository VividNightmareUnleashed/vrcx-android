<div align="center">

# VRCX Android

A native Android companion app for VRChat social tracking, alerts, world browsing, and account utilities.

[![Latest Release](https://img.shields.io/github/v/release/VividNightmareUnleashed/vrcx-android?label=latest)](https://github.com/VividNightmareUnleashed/vrcx-android/releases/latest)
[![VirusTotal Scan](https://img.shields.io/badge/VirusTotal-1.5.2%20scan-394EFF?logo=virustotal&logoColor=white)](https://www.virustotal.com/gui/file/65282ca4bc37ac0ccdbc6fabae45ee81e0a6fd36b6e80466e1a02a520f938416)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](https://developer.android.com/compose)

</div>

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

Download the latest APK from [GitHub Releases](https://github.com/VividNightmareUnleashed/vrcx-android/releases/latest).

Requirements:

- Android 8.0 or newer, API 26+
- A VRChat account

Current signed release integrity:

- APK: `vrcx-android-1.5.2.apk`
- SHA-256: `65282ca4bc37ac0ccdbc6fabae45ee81e0a6fd36b6e80466e1a02a520f938416`
- VirusTotal: [public report](https://www.virustotal.com/gui/file/65282ca4bc37ac0ccdbc6fabae45ee81e0a6fd36b6e80466e1a02a520f938416)
- Last analysis: 2026-05-12, with 0 malicious and 0 suspicious detections in the release evidence

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

The debug APK is written to:

```text
app/build/outputs/apk/debug/vrcx-android-<version>.apk
```

### Tests and lint

```bash
./gradlew test
./gradlew :app:testDebugUnitTest --tests "com.vrcx.android.data.repository.AuthRepositoryTest"
./gradlew lint
```

There is no ktlint, detekt, spotless, or `.editorconfig` in this project. Android lint is the configured quality gate.

### Release builds

Release signing is driven by environment variables. Generate your own local keystore:

```bash
keytool -genkeypair -v -keystore release-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias vrcx-android -storepass YOUR_PASSWORD -keypass YOUR_PASSWORD
```

Create a local `.env` from `.env.example`:

```bash
VRCX_KEYSTORE_PASSWORD=YOUR_PASSWORD
VRCX_KEY_ALIAS=vrcx-android
VIRUSTOTAL_API_KEY=YOUR_VIRUSTOTAL_API_KEY
```

Then build the signed release:

```bash
./gradlew assembleRelease
```

For release publishing evidence, run:

```bash
python scripts/release_sign_and_scan.py
```

The script signs the APK, verifies it with `apksigner`, computes SHA-256, queries or uploads to VirusTotal, and writes JSON plus Markdown evidence under `build/release-evidence/`. Do not commit `.env`, keystores, passwords, API keys, or machine-specific paths.

## Architecture

- Kotlin single-activity Android app
- Jetpack Compose and Material 3 UI
- MVVM with Hilt dependency injection
- Retrofit, OkHttp, and Kotlinx Serialization for VRChat REST APIs
- Dedicated OkHttp WebSocket client for the VRChat pipeline
- Room for account-scoped local data, with committed migration schemas
- DataStore and AndroidX Security Crypto for preferences and session storage
- Coil 3 for authenticated image loading
- WorkManager and a foreground service for background reconnect and notifications
- JUnit, Robolectric, Mockito, MockWebServer, and Room migration tests

Kotlin sources live in `app/src/main/kotlin/com/vrcx/android/`. Unit tests live in `app/src/test/kotlin/`.

## Credits

Developer: [AyaDreamsOfYou](https://x.com/AyaDreamsOfYou)

Inspired by [VRCX](https://github.com/vrcx-team/VRCX), the open-source VRChat companion app for desktop.

Built with [Claude](https://claude.ai) by Anthropic.

## Disclaimer

This is an independent project. It is not affiliated with or endorsed by the [VRCX Team](https://github.com/vrcx-team/VRCX) or VRChat Inc.

"VRChat" is a trademark of VRChat Inc. Use of the VRChat API is subject to the [VRChat Terms of Service](https://hello.vrchat.com/legal).

This software is provided as-is with no guarantee of functionality.

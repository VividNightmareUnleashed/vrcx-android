<div align="center">

# VRCX Android

A native Android companion app for VRChat — track friends, browse worlds, manage avatars, and get real-time notifications, all from your phone.

[![Latest Release](https://img.shields.io/github/v/release/VividNightmareUnleashed/vrcx-android?label=latest)](https://github.com/VividNightmareUnleashed/vrcx-android/releases/latest)
[![Min SDK](https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android&logoColor=white)](https://developer.android.com)

</div>

## About

VRCX Android is a mobile companion app that brings the core functionality of the desktop [VRCX](https://github.com/vrcx-team/VRCX) to your Android device. It connects directly to the VRChat API and WebSocket pipeline to give you real-time visibility into your VRChat social circle without needing a PC.

## Features

- **Friends list** — See who's online, their current world, platform (PC/Quest/iOS), and status. Sort by name, last seen, or trust rank. Filter by VIP.
- **Real-time feed** — Live activity feed of friend status changes, online/offline events, and GPS updates. Searchable with VIP filter.
- **Friends locations** — See where your friends are with world thumbnails, names, capacity, and instance details. Filter by Online/Favorite/Active.
- **Notifications** — View and respond to friend requests, invites, and other VRChat notifications with accept/decline actions and type filters.
- **Search** — Find users, worlds, avatars, and groups with paginated results.
- **World details** — Banner images, descriptions, capacity, platform support, tags, and active instances.
- **Avatar management** — Browse your avatars, filter by visibility and platform, view details, select, and favorite.
- **Favorites** — View and manage your favorite worlds, avatars, and friends with actual names and thumbnails.
- **User profiles** — Detailed user view with Info/Groups/Worlds tabs, favorite star, invite buttons, avatar moderation, and personal notes.
- **Profile editing** — Edit your own VRChat status and bio directly from the app.
- **Invite system** — Send invites and request invites from user profiles.
- **Charts** — View your instance activity history with daily visit charts and most-visited worlds.
- **Groups** — Browse groups you belong to and view group details.
- **Friend log** — Track friend additions, removals, display name changes, and trust level changes with search and type filters.
- **Moderation** — Manage blocks, mutes, avatar visibility, and interaction settings.
- **Gallery** — Browse your VRChat gallery with auto-refresh on new uploads.
- **Background notifications** — Foreground service keeps a WebSocket connection alive for real-time Android notifications (per-friend opt-in). Auto-restarts on device boot.
- **Theming** — VRChat-branded Material 3 dark and light themes with wallpaper-based dynamic scaling.

## Download

Grab the latest APK from [Releases](https://github.com/VividNightmareUnleashed/vrcx-android/releases/latest).

Requires **Android 8.0** (API 26) or newer.

## Building from Source

### Prerequisites

- Android Studio or the Android SDK (API 35)
- JDK 17+

### Build

```bash
export ANDROID_HOME=/path/to/your/Android/Sdk
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/vrcx-android-<version>.apk`.

### Release builds

Release builds require a signing keystore. Generate one:

```bash
keytool -genkeypair -v -keystore release-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias vrcx-android -storepass YOUR_PASSWORD -keypass YOUR_PASSWORD
```

Then build:

```bash
export VRCX_KEYSTORE_PASSWORD='YOUR_PASSWORD'
./gradlew assembleRelease
```

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Architecture**: Single-activity MVVM with Hilt DI
- **Networking**: Retrofit + OkHttp + Kotlinx Serialization
- **Real-time**: WebSocket (OkHttp) via foreground service
- **Database**: Room
- **Images**: Coil 3 (with GIF support)
- **Charts**: Vico

## Credits & Disclaimer

**Developer** — [AyaDreamsOfYou](https://x.com/AyaDreamsOfYou)

**Inspired by** [VRCX](https://github.com/vrcx-team/VRCX) — the open-source VRChat companion app for desktop.

**Built with** [Claude](https://claude.ai) by Anthropic.

This is an independent project. It is **not affiliated with or endorsed by the [VRCX Team](https://github.com/vrcx-team/VRCX) or VRChat Inc.** "VRChat" is a trademark of VRChat Inc. Use of the VRChat API is subject to the [VRChat Terms of Service](https://hello.vrchat.com/legal).

This software is provided as-is with **no guarantee of functionality**.

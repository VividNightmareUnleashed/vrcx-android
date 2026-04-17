<div align="center">

# VRCX Android

A native Android companion app for VRChat — track friends, browse worlds, manage avatars, and get real-time notifications, all from your phone.

[![Latest Release](https://img.shields.io/github/v/release/VividNightmareUnleashed/vrcx-android?label=latest)](https://github.com/VividNightmareUnleashed/vrcx-android/releases/latest)
[![VirusTotal Scan](https://img.shields.io/badge/VirusTotal-1.5.0%20scan-394EFF?logo=virustotal&logoColor=white)](https://www.virustotal.com/gui/file/733a6e531df806788a5b7cd41da9a64fe5928d5bbcf7fb7f3efcf27e74c7419d)
[![Min SDK](https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android&logoColor=white)](https://developer.android.com)

</div>

## About

VRCX Android is a mobile companion app that brings the core functionality of the desktop [VRCX](https://github.com/vrcx-team/VRCX) to your Android device. It connects directly to the VRChat API and WebSocket pipeline to give you real-time visibility into your VRChat social circle without needing a PC.

## Features

### Social

- **Friends list** — See who's online, their current world, platform (PC/Quest/iOS), and status. Sort by name, last seen, or trust rank. Filter by VIP.
- **Real-time feed** — Live activity feed of friend status changes, online/offline events, and GPS updates with per-event dedup so a busy pipeline doesn't flood the list with repeats. Searchable with VIP filter; feed size cap is configurable in Settings.
- **Friends locations** — See where your friends are with world thumbnails, names, capacity, and instance details. Filter by Online/Favorite/Active.
- **Friends Roster** — Scoped roster view with granular filtering and sort, useful for quickly finding specific friends without scrolling the main list.
- **Activity History** — Timeline view over the feed-backed history used elsewhere in the app, scoped to the current account and history-depth setting.
- **Friend log** — Track friend additions, removals, display name changes, and trust-level changes with search and type filters.
- **User profiles** — Detailed user view with Info/Groups/Worlds tabs, favorite star, invite buttons, avatar moderation, and personal notes. Destructive actions (block, unfriend) require confirmation.
- **Friend-request management** — Accept, decline, or cancel incoming/outgoing friend requests directly from the relevant profile screens.
- **Invite system** — Send invites and request invites from user profiles, including preset invite messages and auto-request replies from the desktop-parity invite flow.
- **Notifications** — Unified V1/V2 inbox with type filters and accept/decline actions. Persisted locally so the latest state renders instantly on cold start, before the network round-trip completes.
- **Profile editing** — Edit your own VRChat status, status description, bio, and pronouns from the app.

### Worlds and avatars

- **World details** — Banner images, descriptions, capacity, platform support, tags, and active instances. Instances are tappable and expose a self-invite, copy-URL, and share sheet — handy for accepting the resulting in-app invite from a headset without the desktop client.
- **Avatar management** — Browse your avatars, filter by visibility and platform, view details, select, and favorite.
- **Favorites** — View and manage your favorite worlds, avatars, and friends with actual names and thumbnails. Fast to open on accounts with many favorites thanks to bulk endpoints and background cache patching.
- **Search** — Find users, worlds, avatars, and groups with paginated results. User search supports bio-text queries and last-login sort; world search supports mode + tag filters; avatar search supports remote provider sources in addition to your own avatars.

### Groups and gallery

- **Groups** — Browse groups you belong to, view group details, and see group posts. Admins with the `group-members-manage` permission can remove members directly from group detail.
- **Charts** — Instance activity history with a daily bar chart that renders cleanly at any density, range-filtered metric cards (visits / worlds / active days), and most-visited worlds breakdown. Hour-of-day and weekday breakdowns included. Pull-to-refresh with explicit error surfacing.
- **Gallery** — Browse your VRChat gallery with auto-refresh on new uploads.
- **Moderation** — Manage blocks, mutes, avatar visibility, and interaction settings with persistent tab state across rotation.

### Platform integration

- **Deep links** — `vrcx://` and `https://vrchat.com/home/{user,world,avatar,group}/...` URLs open directly in the app. Any depth of trailing path segments routes to the right detail screen.
- **Background notifications** — Foreground service keeps a WebSocket connection alive for real-time Android notifications (per-friend opt-in). Auto-restarts on device boot; Android 15 uses a "tap to reconnect" notification gated on login state and the background-service preference.
- **Theming** — VRChat-branded Material 3 dark and light themes with wallpaper-based dynamic scaling.
- **Tools** — Quick-link hub with an ID jump flow for opening users, worlds, avatars, and groups directly by ID.
- **Dashboard** — Activity snapshot for one-glance situational awareness — online favorites, recent feed, activity breakdown, and current user context.
- **Settings** — Theme mode, dynamic colors, wallpaper, notification toggles, feed history cap, background-service toggle, auto-login, profile-picture cache controls, and Sign Out. Sign out tears down session state, clears the bulk favorites cache, and stops the websocket service.

## Download

Grab the latest APK from [Releases](https://github.com/VividNightmareUnleashed/vrcx-android/releases/latest).

The current signed `1.5.0` release is published at [VirusTotal](https://www.virustotal.com/gui/file/733a6e531df806788a5b7cd41da9a64fe5928d5bbcf7fb7f3efcf27e74c7419d) with SHA-256 `733a6e531df806788a5b7cd41da9a64fe5928d5bbcf7fb7f3efcf27e74c7419d`.

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

Then create a local `.env` file. You can start from `.env.example` and fill in the real values:

```bash
VRCX_KEYSTORE_PASSWORD='YOUR_PASSWORD'
VRCX_KEY_ALIAS='vrcx-android'
VIRUSTOTAL_API_KEY='YOUR_VIRUSTOTAL_API_KEY'
```

If you only want the signed APK, Gradle still works directly:

```bash
./gradlew assembleRelease
```

If you want the signed APK plus a VirusTotal evidence bundle for release notes, run:

```bash
python scripts/release_sign_and_scan.py
```

That command loads `.env`, builds the signed release APK, computes its SHA-256, looks up or uploads the APK to VirusTotal, and writes two files under `build/release-evidence/`: a JSON payload with the raw evidence and a Markdown snippet you can paste into a GitHub release.

The Markdown report includes the APK SHA-256, a VirusTotal report link, scan verdict counts, and any contacted domains VirusTotal observed during analysis. If your VirusTotal plan supports private scanning, you can also set `VIRUSTOTAL_PRIVATE_SCANNING=true` and `VIRUSTOTAL_ENABLE_INTERNET=true` in `.env` to request internet-enabled sandbox evidence for the domain list.

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3, `collectAsStateWithLifecycle` everywhere for lifecycle-aware state collection
- **Architecture**: Single-activity MVVM with Hilt DI, Navigation-Compose for routing + deep links
- **Networking**: Retrofit + OkHttp + Kotlinx Serialization, with a custom request-dedup + failure-caching interceptor for 403/404/429 handling
- **Real-time**: WebSocket (OkHttp) via foreground service, with reconnect orchestration driven by WorkManager on boot
- **Persistence**: Room (account-scoped tables for feed, friend log, notifications, gallery cache, profile-pic cache)
- **Images**: Coil 3 (with GIF support), wired to the authenticated OkHttp client so image fetches carry VRChat cookies
- **Testing**: JUnit + Robolectric + Mockito, plus Room migration tests that track schema evolution across versions

## Credits & Disclaimer

**Developer** — [AyaDreamsOfYou](https://x.com/AyaDreamsOfYou)

**Inspired by** [VRCX](https://github.com/vrcx-team/VRCX) — the open-source VRChat companion app for desktop.

**Built with** [Claude](https://claude.ai) by Anthropic.

This is an independent project. It is **not affiliated with or endorsed by the [VRCX Team](https://github.com/vrcx-team/VRCX) or VRChat Inc.** "VRChat" is a trademark of VRChat Inc. Use of the VRChat API is subject to the [VRChat Terms of Service](https://hello.vrchat.com/legal).

This software is provided as-is with **no guarantee of functionality**.

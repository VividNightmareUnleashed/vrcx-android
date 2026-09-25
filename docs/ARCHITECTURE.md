# VRCX for Android: architecture

This document records the decisions the port follows, including the backend return values the frontend depends on
(§4.5, §5).

## 1. Goals and constraints

- Port VRCX (upstream commit in `UPSTREAM_COMMIT`) to Android phones and tablets with feature parity.
- Keep the PC look. The phone layout is a translation of the desktop frame (see `docs/DESIGN.md`), not a redesign.
- Anything that needs the PC is either given an Android equivalent or hidden. The only PC-side data source is the
  Windows companion (`companion/`), which sends VRChat log bytes plus two process flags (`vrchatRunning`,
  `steamVrRunning`) over the local network, never beyond it (`docs/PROTOCOL.md`).
- Performance and battery are design constraints:
  - no wake locks by default, and no per-second native round trips;
  - the UI ships inside the APK and never waits on the network to start;
  - large payloads cross the bridge once, asynchronously.

## 2. Repository layout

```
UPSTREAM_COMMIT        upstream VRCX commit the vendored frontend is based on
upstream/VRCX/         reference clone of upstream VRCX for its C# and Electron sources (gitignored)
web/                   vendored VRCX frontend (Vue 3 + Tailwind 4 + shadcn-vue). Android changes are a patch set on
                       top of commit "chore: vendor upstream VRCX frontend" plus Android-only files.
  src/platform/android/  Android-only frontend modules (compact layout, touch helpers, companion UI, ...)
android/               Gradle project (AGP 8.7.3, Kotlin 2.1.0, compileSdk 35, minSdk 26, JDK 17)
  app/src/main/java/io/github/vrcxandroid/
    VrcxApplication.kt       process-wide singletons
    host/                    Activity, WebView holder, foreground service, notifications, pickers, insets, back
    bridge/                  JS <-> Kotlin transport and the backend classes (AppApi, WebApi, SQLite, ...)
    logwatcher/              Kotlin port of Dotnet/LogWatcher.cs + the local log mirror
    companion/               LAN client for the Windows companion (discovery, pairing, session)
  app/src/main/assets/shim/vrcx-android-shim.js   document-start script (see §4)
companion/             Windows companion (.NET 8, WinForms tray app)
docs/                  this file, PROTOCOL.md, DESIGN.md
```

## 3. Runtime overview

```
┌─────────────────────────── Android app process ──────────────────────────────┐
│ WebView (app-scoped, survives Activity recreation)                            │
│   https://appassets.androidplatform.net/assets/web/index.html                 │
│   document-start shim → window.interopApi / window.electron / polyfills       │
│   vendored VRCX frontend (LINUX=true, WINDOWS=false, ANDROID=true)            │
│        │ postMessage {id,c,m,a}                     ▲ {id,ok,r|e} / {ev,d}    │
│ ───────┼────────────────────────────────────────────┼──────────────────────── │
│ BridgeDispatcher (per-class FIFO executors, WebApi concurrent)                │
│   AppApiElectron  WebApi(OkHttp+cookie jar)  SQLite(bundled)  VRCXStorage     │
│   LogWatcher(Kotlin port)  Discord(stub)  AssetBundleManager(stub)  AndroidHost│
│                                     ▲ mirrored log bytes + process flags      │
│ CompanionManager ── TLS over TCP, LAN only ──────────────────────────┐        │
│ VrcxForegroundService (keeps the process alive while background mode is on)   │
└──────────────────────────────────────────────────────────────────────┼────────┘
                                                                       │ Wi-Fi LAN
                                             Windows PC: VRCX Companion (tray app)
                                             reads %LOCALAPPDATA%Low\VRChat\VRChat\output_log_*.txt
```

## 4. Frontend host contract

### 4.1 Platform globals

- Runtime globals set by the shim before any page script runs: `window.WINDOWS = false`, `window.LINUX = true`,
  `window.ANDROID = true` (all non-writable). `LINUX=true` routes the frontend through the Electron code paths (JSON-string
  WebApi/SQLite transport, polled LogWatcher and game state, `window.electron.*`), which is what the Kotlin bridge
  implements. Every `LINUX` branch has been audited; the ones that must not apply on
  Android are gated with `isAndroid`.
- Build-time define `ANDROID` (`true` in the Android build, `false` in desktop builds and vitest). Frontend code reads it
  through `web/src/shared/utils/platform.js` (`isAndroid`, plus capability helpers such as `hasLocalGame`,
  `hasVrOverlay`, `hasDesktopShell`). Do not test `LINUX` directly in new code.
- `<html>` gets class `is-android` from the shim. The compact-layout class `vrcx-compact` is managed by the frontend
  (see DESIGN.md).

### 4.2 Transport

- The page is served by `WebViewAssetLoader` at `https://appassets.androidplatform.net/assets/web/index.html`
  (`AssetsPathHandler` on `/assets/`). The built frontend lives under APK assets `web/`. A second path handler
  `/local/` serves app-private files (see §6.6). Never use `file://`.
- JS → native: `WebViewCompat.addWebMessageListener(webView, "VRCXNative", setOf("https://appassets.androidplatform.net"))`.
  The shim sends `JSON.stringify({id, c, m, a})`: `c` class name, `m` method name, `a` argument array. Arguments go
  through a replacer: `Map` → plain object, `Set` → array, `undefined` → `null`, `BigInt` → string.
- Native → JS replies: `{id, ok:true, r:<json>}` or `{id, ok:false, e:"<ExceptionType>: <message>"}` posted through the
  `JavaScriptReplyProxy` of the calling frame. The shim resolves or rejects (with `new Error(e)`) the pending promise.
  `r` is the JSON value itself, not a string, except where the contract says the method returns a JSON **string**
  (for example `WebApi.ExecuteJson`, `SQLite.ExecuteJson`): then `r` is that string.
- Native → JS events: `{ev:"<name>", d:<json>}` on the same proxy. Event names: `launch-command`, `focus`, `insets`,
  `tts-voices`, `tts-event`, `network-changed`, `companion-state`, `log-available`, `game-state`, `visibility`.
  The shim dispatches them to its registered handlers and as `window` `CustomEvent("vrcx-android:<name>")`.
- Native → JS calls that need a result (back button) use `webView.evaluateJavascript("window.__vrcxAndroid.handleBack()")`.
- Class names received from the frontend: `AppApiElectron`, `WebApi`, `VRCXStorage`, `SQLite`, `LogWatcher`, `Discord`,
  `AssetBundleManager`, `AppApiVrElectron` (only from the VR page, which is not shipped) and the Android-only `AndroidHost`.
  An unknown class or method rejects with `"MissingMethodException: Method <m> does not exist on class <c>"`.

### 4.3 Ordering and threading

- Each bridge class has one single-thread FIFO executor, so calls to one class complete in arrival order. SQLite is the
  critical case: JS transactions span several un-awaited calls on one connection. `WebApi` requests run concurrently
  (OkHttp dispatcher, max 10 per host).
- `AndroidHost.RestartApp` / `electron.restartApp` / `AppApi.RestartApplication` wait for all executors to drain, flush
  VRCXStorage and the cookie jar, then restart the process.
- No bridge work ever runs on the main thread except UI calls that must (intents, system bars), which hop to it.

### 4.4 Shim responsibilities (`android/app/src/main/assets/shim/vrcx-android-shim.js`)

1. The globals from §4.1, `window.interopApi.callDotNetMethod(className, methodName, args)` returning a Promise, and
   `window.electron` exactly as `upstream/VRCX/src-electron/preload.js:43-68`.
2. Resolve locally, without crossing the bridge, calls that are PC-only and frequent: `AppApiElectron.ExecuteVrOverlayFunction`,
   `SetVR`, `XSNotification`, `OVRTNotification`, `SendIpc`, `IPCAnnounceStart`, `SetUserAgent`, `electron.updateVr`,
   `Discord.SetAssets`, and `Discord.SetActive` (resolves `false`).
3. Cheap polling: `LogWatcher.GetLogLines`, `AppApiElectron.IsGameRunning`, `IsSteamVRRunning` are answered from a JS-side
   cache when native has not signalled `log-available` / `game-state` since the last call. Only then does a call cross
   the bridge.
4. Polyfills: `speechSynthesis` + `SpeechSynthesisUtterance` backed by native TextToSpeech (voices cached from the
   `tts-voices` event so `getVoices()` is synchronous); `navigator.clipboard.writeText/readText/write` fallbacks backed by
   `AndroidHost`; download interception for `<a download>` with `data:`/`blob:` hrefs (`URL.createObjectURL` wrapper,
   `HTMLAnchorElement.prototype.click` patch, capturing click listener) → `AndroidHost.SaveFile(name, mime, base64)`.
5. `WebSocket` wrapper that tracks the VRChat pipeline socket and force-closes it (code 4000 path) on `network-changed`
   or when resuming after more than 60 s in the background, so the frontend's reconnect logic runs.
6. `window.__vrcxAndroid` = native → JS entry points: `handleBack()` (algorithm in DESIGN.md §6), `setInsets()`,
   `onEvent()`.
7. Sets CSS variables `--safe-top/right/bottom/left` and `--ime-bottom` on `<html>` from the `insets` event.

### 4.5 Values the frontend reads from the bridge

The traps to get right:
- `AppApiElectron.GetColourBulk` resolves to an **array of `[userId, hue]` pairs**.
- `GetVersion` resolves to exactly `VRCX <web/Version>` (or `VRCX Nightly <...>`), generated at build time. The OkHttp
  User-Agent is that same string. The WebView UA is its default plus ` VRCX/<version>`, set before the first `loadUrl`.
- Registry: `GetVRChatRegistryKeyString`/`GetVRChatRegistryKey`/`GetVRChatRegistry` → `null`,
  `HasVRChatRegistryFolder` → `true`, `SetVRChatRegistryKey` → `false`, `GetVRChatRegistryJson` and
  `SetVRChatRegistry` reject. The frontend also gates the registry flows with `isAndroid`.
- SQLite results: positional row arrays, NULL → `null`.
- Errors carry the native text verbatim, prefixed with a .NET-like type (`SQLiteException: database is locked`,
  `UnauthorizedAccessException: ...`, `FileNotFoundException: Could not find file '...'`).

## 5. Backend classes (Kotlin, package `bridge`)

| Class | Implementation |
|---|---|
| `WebApi` | OkHttp; persistent cookie jar stored in the SQLite `cookies` table (key `default`, base64 of .NET `Cookie` JSON, Secure cookies kept); the four upload variants; image responses → `data:image/png;base64,...`; proxy from `VRCX_ProxyServer` applied to OkHttp and, when supported, `ProxyController` |
| `SQLite` | `androidx.sqlite:sqlite-bundled` (SQLite ≥ 3.46) on one connection and one thread; `@name` binding with a literal/comment-aware tokenizer; DB at `filesDir/VRCX/VRCX.sqlite3`; WAL + `busy_timeout=5000` |
| `VRCXStorage` | flat string→string JSON at `filesDir/VRCX/VRCX.json`, BOM-tolerant, 500 ms debounced atomic save; native `GetArray/SetArray/GetObject/SetObject` |
| `LogWatcher` | Kotlin port of `LogWatcher.cs` over the log mirror (package `logwatcher`); pull mode (`GetLogLines`) |
| `AppApiElectron` | Android implementation of the ~86 methods: PORTABLE ones ported byte-exact (colour hash, MD5, `SignFile` librsync, image resize/crop, PNG metadata), ANDROID-EQUIVALENT ones through `HostServices`, PC-only ones return the safe values |
| `Discord`, `AssetBundleManager` | stubs with the exact safe values |
| `AndroidHost` | Android-only helpers: `SaveFile`, `CopyText`, `CopyImage`, `ReadClipboardText`, TTS (`TtsSpeak`, `TtsCancel`, `TtsGetVoices`), companion (`CompanionGetState`, `CompanionDiscover`, `CompanionScanQr`, `CompanionPair`, `CompanionConnectManual`, `CompanionForget`, `CompanionSetActive`), `ImportDatabase`, `ExportDatabase`, `GetBackgroundMode`, `SetBackgroundMode`, `RequestIgnoreBatteryOptimizations`, `OpenNotificationSettings`, `RestartApp`, `GetDeviceInfo` |

The Kotlin interfaces the packages share live in `bridge/BridgeModule.kt` and `host/HostServices.kt`.

## 6. Android host (package `host`)

1. **WebView lifetime.** One WebView per process, created by `WebViewHolder` with a `MutableContextWrapper`. The Activity
   attaches it in `onCreate` and detaches it in `onDestroy` without destroying it, swapping the wrapper's base context to
   the Activity while attached (needed for `<select>`, date pickers and file choosers). `webView.onPause()` and
   `pauseTimers()` are never called while background mode is on. Renderer priority is `RENDERER_PRIORITY_IMPORTANT`
   (`waivedWhenNotVisible=false`).
2. **Settings.** JavaScript and DOM storage on; `allowFileAccess=false`; `textZoom=100`; zoom controls off; algorithmic
   darkening off; `mediaPlaybackRequiresUserGesture=false`; `MIXED_CONTENT_COMPATIBILITY_MODE`; `setSupportMultipleWindows(false)`.
   Web contents debugging on in debug builds.
3. **Startup gate.** Require WebView major version ≥ 120 and the features `WEB_MESSAGE_LISTENER` and
   `DOCUMENT_START_SCRIPT`; otherwise show a native screen asking the user to update Android System WebView.
4. **Navigation policy.** Same-origin navigations only. `vrcx:` → launch command. `http(s)` → Custom Tab / `ACTION_VIEW`.
   Everything else is blocked.
5. **Renderer crash.** `onRenderProcessGone` returns true, recreates the WebView and sets the pending launch command
   `crash/Browser crashed.` or `crash/Browser was killed.`.
6. **Local files.** Anything the page must show as `<img src>` (picked files, cached images, screenshots) is copied or
   written under `filesDir/local/` or `cacheDir/...` and exposed through the `/local/` path handler as
   `https://appassets.androidplatform.net/local/<relative path>`. AppApi file methods accept both that URL form and
   the absolute path.
7. **Insets and theme.** Edge-to-edge (targetSdk 35). The WebView fills the window; system bar, cutout and IME insets are
   sent to the page as CSS variables (`insets` event), and the page pads its own chrome. `AppApi.ChangeTheme(0|1|2)`
   sets the system bar icon appearance and the window background colour.
8. **Back.** `OnBackPressedCallback` → `window.__vrcxAndroid.handleBack()`; `false` → `moveTaskToBack(true)`.
9. **Deep links.** `singleTask` activity, intent-filter `vrcx://`. Cold start: the command (prefix stripped, trimmed) is
   returned once by `AppApi.GetLaunchCommand`. `onNewIntent`: `launch-command` event. `ACTION_SEND` text is delivered as
   the launch command `search/<text>` handled by the frontend (Direct Access).
10. **Pickers and downloads.** `WebChromeClient.onShowFileChooser` (Photo Picker for `image/*`, SAF otherwise; always
    answer the callback, `null` on cancel). `AndroidHost.SaveFile` uses `ACTION_CREATE_DOCUMENT`.
11. **Notifications.** Channel `vrcx_notifications` for VRCX desktop-style notifications (`electron.desktopNotification`,
    large icon from `GetImage`, content intent opens the app). Channel `vrcx_service` (low importance) for the
    foreground service. Request `POST_NOTIFICATIONS` on Android 13+ the first time the app is opened after login.

## 7. Background mode and battery

- Setting `AndroidHost.GetBackgroundMode/SetBackgroundMode`, **default on**, stored natively (`SharedPreferences`), shown
  in Settings → System where the PC shows "Close to tray".
- On: `VrcxForegroundService` (type `specialUse`, subtype "keeps the VRChat connection and PC companion stream open")
  runs while the app process is alive. Its notification shows the connection state and has Open and Quit actions
  (the Android equivalent of the tray icon; `electron.setTrayIconNotification` sets a dot/"new activity" text on it).
- Battery-lean rules while in background mode:
  - no wake locks; JS timers only run while the device is awake for other reasons, such as network traffic;
  - the per-second PC-only and polling calls are answered inside the shim (§4.4 items 2–3), so a tick costs no native
    work;
  - the companion stream is push-based: native appends to the mirror and runs `LogWatcher.Update()` on arrival, then
    emits `log-available` at most once per second;
  - an optional "ignore battery optimization" prompt, only after the user opts in from Settings.
- Off: when the Activity has been stopped for 60 s, pause the WebView (`onPause` + `pauseTimers`) and disconnect the
  companion. Resume both on `onStart`. VRCX's existing reconnect and refresh logic then catches up.
- Performance defaults: `KeepAlive` in the phone layout is capped (`max` 6); overlays have no `backdrop-filter`; images
  load straight from VRChat's CDN through the WebView HTTP cache; the frontend is built with Chrome 120 targets.

## 8. Companion integration (packages `companion` and `logwatcher`)

- The wire protocol, pairing and security are in `docs/PROTOCOL.md`.
- The phone keeps an exact byte mirror of the PC's `output_log_*.txt` files (plus their PC metadata) under
  `filesDir/logmirror/<companionId>/`. The Kotlin LogWatcher reads the mirror with the PC's metadata, never the Android
  file attributes, and converts timestamps with the PC's time zone.
- `vrchatRunning` / `steamVrRunning` feed `IsGameRunning` / `IsSteamVRRunning`. The last known state is kept for 120 s
  after a disconnect, then falls back to `false`. Missed data is replayed before the new process state.
- One active companion at a time; several can be paired.
- The frontend shows a "PC companion" settings group (status, pair by QR or code, manual address, forget, PC time zone)
  and companion-aware empty states in Game Log, Player List and the related dashboard widgets. Phones without a PC work
  like VRCX with game logging unavailable.
- No companion traffic ever leaves the LAN. The client refuses non-private addresses. The companion only accepts
  private-address peers and makes no outbound connections.

## 9. Decisions on PC-only features

| Feature | Android behaviour |
|---|---|
| VR overlay, SteamVR overlay settings, wrist feed, XSOverlay/OVRToolkit | Hidden. VR tab removed; "show user images" moves to the Notifications tab |
| Discord Rich Presence | Hidden (Integrations group) |
| Launch VRChat / Launch options / Start as desktop | `StartGame` fires the `vrchat://launch?...` intent only if an app resolves it; otherwise Launch is hidden. "Open in-game" is disabled (`canOpenInstanceInGame=false`), so self-invite is used. Crash relaunch and QuitFix are always off |
| Registry backup, VRChat config.json, cache (AssetBundleManager), folders (VRC photos, Steam screenshots, VRCX/VRChat data, crash dumps) | Hidden (Tools marked `pcOnly`, settings rows gated) |
| Screenshot helper (writes metadata into PC screenshots) | Hidden |
| Screenshot Metadata tool | Ported over SAF: works on PNGs on the phone |
| Prints/stickers/emoji auto-save | Saved to MediaStore `Pictures/VRCX/<type>/<YYYY-MM>/` by default; an SAF folder can be chosen |
| Desktop notifications, TTS | Android notifications and TextToSpeech. On the first Android run, `desktopToast` is seeded to `Always` if unset. Conditions needing VR/HMD state ("Inside VR", "Outside VR", "while AFK") are hidden |
| Tray, start with OS, start minimized, GPU acceleration, updater, zoom | Hidden. Updates come from the APK distribution; `electron.getNoUpdater()` → `true` |
| Proxy | Implemented (OkHttp + `ProxyController`); restart to apply |
| Custom CSS/JS | `getExternalFilesDir(null)/custom.css` and `custom.js`; a Settings action reloads them |
| Import PC data | Settings → PC companion → "Import VRCX database": SAF pick `VRCX.sqlite3` (and optionally `VRCX.json`), integrity check, restart. "Export database" checkpoints WAL first. Backups exclude the database (it holds saved credentials) |
| Request-invite platform | Unchanged (`standalonewindows`): the user joins on PC |

## 10. Build

- `npm run build:android` in `web/` builds the page: Vite with `VRCX_TARGET=android`, which means the `ANDROID`
  define, Chrome 120 CSS/JS targets, no `vr.html`, `outDir` `web/build/android/web`.
- The Gradle module adds `../../web/build/android` as an extra assets directory, so the page ends up at APK asset path
  `web/index.html`. `GetVersion` is generated from `web/Version` into `BuildConfig.VRCX_VERSION`.
- `gradlew assembleDebug` (or `assembleRelease`) in `android/` then builds the APK.
- The companion: `dotnet publish companion/VrcxCompanion -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true`.
- Tests: `npm test` (vitest) in `web/`; `gradlew testDebugUnitTest` (LogWatcher golden tests, SQL binder, librsync
  signature, colour hash); `dotnet test companion/` (protocol, LAN filter, tailer).

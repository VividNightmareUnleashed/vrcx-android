# Android UI preview harness (dev only)

A desktop-browser preview of the Android phone UI, for checking how the phone shell and the views and dialogs adapted
to it look. It runs the real frontend (`app.js`, stores, router, views) against a fake Android host,
so no emulator, device or VRChat account is needed.

Nothing in this folder is part of a build: `npm run build:android` and the desktop build only use `src/index.html`,
nothing imports these files, and `styles/globals.css` excludes the folder from Tailwind's class scan.

## Run it

```sh
cd web
npx vite --config src/platform/android/dev/vite.config.js
```

Then open <http://localhost:9010/platform/android/dev/index.html> and set the browser's device toolbar (or the
viewport) to a phone size. Useful sizes:

| Size | What it shows |
|---|---|
| 360 x 780 | small phone, portrait: app bar, page card, dock |
| 412 x 915 | large phone, portrait |
| 780 x 360 | phone landscape: 48 px rail, 40 px app bar, friends panel as a right column |
| 800 x 1280 | tablet: the upstream desktop frame with the touch fixes |

Turn on touch emulation in the device toolbar to check the coarse-pointer rules (long-press tooltips, visible
hover-only controls, no column resize handles). The port can be changed with `VRCX_PREVIEW_PORT`.

## Query parameters

| Parameter | Values | Default |
|---|---|---|
| `theme` | `system`, `light`, `dark`, `midnight` | `system` |
| `color` | `default`, `blue`, `green`, `orange`, `red`, `rose`, `violet`, `yellow` | stored value |
| `companion` | `none` (no PC paired), `paired` (paired, not connected), `connected`, `playing` (VRChat running) | `playing` |
| `insets` | `top,right,bottom,left` in CSS px, as the native `insets` event sends them | `24,0,16,0` |
| `onboarding` | `1` shows the first-run onboarding | off |

Example: `index.html?theme=dark&companion=none&insets=32,0,24,0`.

## Keys and console helpers

- **F8**: the Android back button (`window.__vrcxAndroid.handleBack()`). The console logs `handled` or
  `moveTaskToBack`. F8 is a real key press, so Chrome treats the focus that returns to a menu trigger afterwards as
  keyboard focus; on a phone the last input is a touch, so an occasional tooltip that appears here after F8 does not
  appear on a device.
- **F9**: toggles a 300 px soft keyboard (`--ime-bottom`), to check IME-aware padding.
- `__vrcxDev.back()`, `__vrcxDev.setIme(px)`: the same from the console.
- `__vrcxAndroid.setInsets({ top, right, bottom, left, ime })`: sends an `insets` event.
- `$pinia.<store>`: every store, as in the app (for example `$pinia.modal.prompt({ title: 'Rename' })` or
  `$pinia.gallery.showFullscreenImageDialog(url)`).

## What is faked (`mockBridge.js`)

- Platform globals as the shim sets them: `WINDOWS=false`, `LINUX=true`, `ANDROID=true`, `html.is-android`, and the
  `ANDROID` build define (set by `vite.config.js`).
- `window.interopApi.callDotNetMethod` with in-memory classes:
  - `VRCXStorage`: a `Map`;
  - `SQLite`: empty results, except the `configs` table (which makes the app log in as the fixture user) and the
    feed tables (rows built from `fixtures.json`);
  - `WebApi`: canned JSON for `config`, `auth/user`, `auth/user/friends`, users, worlds and instances from
    `fixtures.json`; every other VRChat endpoint returns an empty result, and nothing touches the network;
  - `AppApiElectron`: the Android safe values (`GetVersion`, `IsGameRunning` from the companion mode, registry
    `null`, no zoom), anything else resolves `null`;
  - `LogWatcher`, `Discord`, `AssetBundleManager` stubs, and `AndroidHost` (companion state, background mode,
    device info).
- `window.electron` (preload contract) and `window.__vrcxAndroid` (`on`, `emit`, `setInsets`, `handleBack`).
- The VRChat pipeline WebSocket is replaced by a silent socket; every image is a locally generated SVG.

`fixtures.json` holds the made-up current user, friends, worlds and feed entries. Add to it when a view needs more
data to preview; keep the ids in the `usr_00000000-...` / `wrld_00000000-...` ranges so they can never match a real
account.

// Host platform helpers for the Android port (docs/ARCHITECTURE.md §4.1).
// `ANDROID` is a build-time define: true in `npm run build:android`, false in desktop builds and tests.
// On Android the runtime globals are WINDOWS=false and LINUX=true (the Electron code paths), so new code must use
// these helpers instead of testing LINUX/WINDOWS directly.

/** Running inside the Android WebView host. */
export const isAndroid = typeof ANDROID !== 'undefined' && ANDROID === true;

/** A VRChat PC client can be started, attached to or controlled on this device. */
export const hasLocalGame = !isAndroid;

/** The SteamVR/OpenVR overlay (vr.html) exists. */
export const hasVrOverlay = !isAndroid;

/** Desktop shell features: tray, start with OS, window state, zoom, updater. */
export const hasDesktopShell = !isAndroid;

/** Local VRChat files on this device (registry, config.json, cache, screenshots folder, crash dumps). */
export const hasLocalVrchatFiles = !isAndroid;

/** Discord Rich Presence through a local Discord client. */
export const hasDiscordPresence = !isAndroid;

/** Windows CEF host (VRCX for Windows). */
export function isCefHost() {
    return typeof WINDOWS !== 'undefined' && WINDOWS === true;
}

/** Electron host (VRCX for Linux/macOS). False on Android even though LINUX is true there. */
export function isElectronHost() {
    return !isAndroid && typeof LINUX !== 'undefined' && LINUX === true;
}

/** macOS Electron host. */
export function isMacOS() {
    return !isAndroid && typeof navigator !== 'undefined' && navigator.platform.includes('Mac');
}

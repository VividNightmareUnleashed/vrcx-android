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

/**
 * Subscribes to a native → JS event (docs/ARCHITECTURE.md §4.2). Uses `target.__vrcxAndroid.on` when the shim
 * provides it, otherwise the `vrcx-android:<name>` CustomEvent the shim dispatches on `window`.
 * Exported separately from {@link onAndroidEvent} so tests can pass their own target.
 *
 * @param {any} target Object that owns `__vrcxAndroid` and the event listeners (normally `window`)
 * @param {string} name Event name without prefix, for example `companion-state`
 * @param {(payload: any) => void} handler Receives the event payload (`d`)
 * @returns {() => void} Unsubscribe function (always callable)
 */
export function subscribeNativeEvent(target, name, handler) {
    if (!target || typeof handler !== 'function') {
        return () => {};
    }
    const bridge = target.__vrcxAndroid;
    if (bridge && typeof bridge.on === 'function') {
        const off = bridge.on(name, handler);
        if (typeof off === 'function') {
            return off;
        }
        return () => {
            if (typeof bridge.off === 'function') {
                bridge.off(name, handler);
            }
        };
    }
    if (typeof target.addEventListener !== 'function') {
        return () => {};
    }
    const eventName = `vrcx-android:${name}`;
    const listener = (event) => handler(event?.detail);
    target.addEventListener(eventName, listener);
    return () => target.removeEventListener(eventName, listener);
}

/**
 * Subscribes to a native event on `window`. Harmless on desktop, where nothing ever emits these events; callers
 * still gate with `isAndroid` so desktop builds do no work.
 *
 * @param {string} name
 * @param {(payload: any) => void} handler
 * @returns {() => void}
 */
export function onAndroidEvent(name, handler) {
    if (typeof window === 'undefined') {
        return () => {};
    }
    return subscribeNativeEvent(window, name, handler);
}

/**
 * The Android-only bridge class (`window.AndroidHost`, bound by plugins/interopApi.js), or `null` when absent.
 * Note: it is a Proxy that answers every property with a bridge call, so never resolve a Promise with it.
 *
 * @returns {any}
 */
export function getAndroidHost() {
    if (typeof window === 'undefined') {
        return null;
    }
    return /** @type {any} */ (window).AndroidHost ?? null;
}

/**
 * Splits a bridge rejection (`"<ExceptionType>: <message>"`, docs/ARCHITECTURE.md §4.2) into its parts.
 *
 * @param {unknown} error
 * @returns {{ type: string; detail: string }}
 */
export function parseBridgeError(error) {
    const text = String(error instanceof Error ? error.message : (error ?? '')).trim();
    const match = text.match(/^([A-Za-z_][\w.]*(?:Exception|Error)):\s*([\s\S]*)$/);
    if (match) {
        return { type: match[1].split('.').pop(), detail: match[2].trim() };
    }
    return { type: '', detail: text };
}

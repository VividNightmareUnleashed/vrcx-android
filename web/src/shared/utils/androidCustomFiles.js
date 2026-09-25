import { refreshCustomCss } from './base/ui';

// Custom CSS and JS on Android (docs/ARCHITECTURE.md §9): the bridge reads `custom.css` and `custom.js` from
// `getExternalFilesDir(null)` (`Android/data/<package>/files/`). Desktop reloads them with Alt+Shift+R, which a phone
// without a hardware keyboard cannot press, so Settings → Advanced offers these actions instead.

/** Id of the `<link>` that `refreshCustomCss` adds for custom.css. */
export const CUSTOM_STYLE_ELEMENT_ID = 'app-custom-style';

/**
 * Reads custom.css again and applies it in place.
 *
 * @param {Document} [doc]
 * @returns {Promise<boolean>} Whether a custom stylesheet is applied now (false when custom.css is missing or empty)
 */
export async function reloadCustomCss(doc = document) {
    await refreshCustomCss();
    return Boolean(doc.getElementById(CUSTOM_STYLE_ELEMENT_ID));
}

/**
 * Runs custom.js again. A script cannot be unloaded, and running it a second time in the same page would repeat its
 * side effects (listeners, timers) or fail on its top-level declarations, so the page reloads instead, the same as
 * the desktop Ctrl+R hotkey. The shim is a document-start script, so it is injected again.
 *
 * @param {{ location: { reload: () => void } }} [win]
 */
export function reloadCustomScript(win = window) {
    win.location.reload();
}

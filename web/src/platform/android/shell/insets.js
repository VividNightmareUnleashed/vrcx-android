// Soft-keyboard awareness (docs/ARCHITECTURE.md §4.4 item 7, §6.7).
//
// The shim writes --safe-top/right/bottom/left and --ime-bottom on <html> when native sends the `insets` event, and
// dispatches window CustomEvent('vrcx-android:insets'). The WebView itself is not resized for the keyboard, so the
// page pads its own chrome (mobile.css) and, while the keyboard is up, keeps the focused field in view.

export const IME_OPEN_CLASS = 'vrcx-ime-open';

/**
 * @param {HTMLElement} root
 * @returns {number} Keyboard height in CSS px (0 when hidden or unknown)
 */
export function readImeBottom(root) {
    const view = root.ownerDocument?.defaultView ?? globalThis;
    const raw =
        root.style.getPropertyValue('--ime-bottom') || view.getComputedStyle(root).getPropertyValue('--ime-bottom');
    const value = parseFloat(raw);
    return Number.isFinite(value) ? value : 0;
}

/**
 * @param {Element | null} element
 */
function revealFocused(element) {
    if (!element || typeof element.scrollIntoView !== 'function') return;
    if (!element.matches?.('input, textarea, select, [contenteditable="true"], [contenteditable=""]')) return;
    element.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

/**
 * @param {object} [options]
 * @param {Window} [options.win]
 * @returns {() => void} Uninstall
 */
export function installImeTracking({ win = globalThis.window } = {}) {
    const doc = win.document;
    const root = doc.documentElement;
    let imeOpen = false;

    const sync = () => {
        const open = readImeBottom(root) > 0;
        if (open === imeOpen) return;
        imeOpen = open;
        root.classList.toggle(IME_OPEN_CLASS, open);
        if (open) {
            // Wait for the padding change to lay out before scrolling the field into view.
            win.requestAnimationFrame(() => revealFocused(doc.activeElement));
        }
    };

    const onFocusIn = (event) => {
        if (imeOpen) {
            win.requestAnimationFrame(() => revealFocused(event.target));
        }
    };

    win.addEventListener('vrcx-android:insets', sync);
    doc.addEventListener('focusin', onFocusIn);
    sync();

    return () => {
        win.removeEventListener('vrcx-android:insets', sync);
        doc.removeEventListener('focusin', onFocusIn);
    };
}

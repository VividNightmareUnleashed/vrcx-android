// Dialog auto-focus guard (docs/DESIGN.md §3.2).
//
// reka's FocusScope focuses the first tabbable element when a dialog opens. When that element is a text field the soft
// keyboard pops up and covers half of a phone screen. On touch layouts we cancel that auto-focus and focus the dialog
// container instead, unless the dialog exists to take text input (Quick Search, prompt, OTP) or opts in.

/** Reka-ui 2.x FocusScope mount event (non-bubbling, cancelable; a window capture listener still receives it). */
export const AUTOFOCUS_ON_MOUNT_EVENT = 'focusScope.autoFocusOnMount';

export const GUARDED_CONTAINER_SELECTOR = '[data-slot="dialog-content"], [data-slot="sheet-content"]';

/** Dialogs that keep their keyboard: an explicit opt-in, the OTP input, the command palette input, prompt forms. */
export const AUTOFOCUS_ALLOW_SELECTOR =
    '[autofocus], [data-mobile-autofocus], [data-slot="command-input"], form [data-slot="form-item"] input';

/**
 * @param {Event} event
 * @param {() => boolean} isActive
 * @returns {boolean} True when the default auto-focus was cancelled
 */
export function guardAutoFocus(event, isActive) {
    const container = event.target;
    if (!container || typeof container.matches !== 'function') return false;
    if (!container.matches(GUARDED_CONTAINER_SELECTOR)) return false;
    if (!isActive()) return false;
    if (container.matches('[data-mobile-autofocus]') || container.querySelector(AUTOFOCUS_ALLOW_SELECTOR)) {
        return false;
    }
    event.preventDefault();
    if (!container.hasAttribute('tabindex')) {
        container.setAttribute('tabindex', '-1');
    }
    container.focus({ preventScroll: true });
    return true;
}

/**
 * @param {object} options
 * @param {() => boolean} options.isActive Whether touch rules apply right now
 * @param {Window} [options.win]
 * @returns {() => void} Uninstall
 */
export function installFocusGuard({ isActive, win = globalThis.window }) {
    const listener = (event) => guardAutoFocus(event, isActive);
    win.addEventListener(AUTOFOCUS_ON_MOUNT_EVENT, listener, true);
    return () => win.removeEventListener(AUTOFOCUS_ON_MOUNT_EVENT, listener, true);
}

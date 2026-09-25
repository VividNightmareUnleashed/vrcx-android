// Android back button (docs/DESIGN.md §6).
//
// Native calls window.__vrcxAndroid.handleBack(); the shim forwards to the registered backHandler, which returns true
// when the press was handled. On false native moves the task to the back; the app never finishes itself.
//
// Modals are never closed by toggling their state: the helper modals (confirm/prompt/OTP) resolve their promises only
// from their escapeKeyDown handlers, so every layer is closed with a synthetic Escape keydown.
//
// Which layer is on top is reka's decision, not the DOM's: reka keeps its dismissable layers in mount order and hands
// Escape to the newest one only. Document order does not match it (dialogs live in the modal portal root inside
// #root, sheets and floating content are appended to <body>, and a hover card opened before a dialog stays mounted
// under it), so the handler does not guess. It sends one Escape marked as a back press, and the entity dialog host
// (components/dialogs/MainDialogContainer.vue) turns that Escape into a crumb step when it is the layer reka chose and
// holds more than one crumb (handleMainDialogEscape below).

const LAYER_SELECTOR = '[data-dismissable-layer]';

/** Reka wraps menus, popovers, selects, tooltips and hover cards in a positioned wrapper appended to <body>. */
const POPPER_WRAPPER_SELECTOR = '[data-reka-popper-content-wrapper]';

/** Marks the synthetic Escape of a back press (a non-enumerable property on the event). */
export const BACK_ESCAPE_FLAG = '__vrcxBackPress';

/** Longest wait for a closing menu, popover or tooltip before the next Escape is sent anyway. */
export const CLOSING_LAYER_WAIT_MS = 400;

/**
 * A layer the user can see: not playing its exit animation (reka keeps a closing layer mounted with
 * data-state="closed" until the animation ends) and not hidden.
 *
 * @param {Element} layer
 * @returns {boolean}
 */
export function isLiveLayer(layer) {
    if (layer.getAttribute('data-state') === 'closed') return false;
    return !layer.closest('[hidden]');
}

/**
 * Whether any reka dismissable layer (dialog, alert, sheet, menu, popover, select, tooltip, hover card) is open.
 *
 * @param {Document} doc
 * @returns {boolean}
 */
export function hasOpenLayer(doc) {
    for (const layer of doc.querySelectorAll(LAYER_SELECTOR)) {
        if (isLiveLayer(layer)) return true;
    }
    return false;
}

/**
 * A menu, popover, select, tooltip or hover card that is playing its exit animation. Unlike dialogs and sheets (which
 * leave reka's layer stack as soon as they start closing), these stay in the stack until they unmount, so an Escape
 * sent meanwhile would go to them and be lost.
 *
 * @param {Document} doc
 * @returns {Element | null}
 */
export function findClosingFloatingLayer(doc) {
    for (const layer of doc.querySelectorAll(`${LAYER_SELECTOR}[data-state="closed"]`)) {
        if (layer.closest(POPPER_WRAPPER_SELECTOR) && !layer.closest('[hidden]')) {
            return layer;
        }
    }
    return null;
}

/**
 * @param {Document} doc
 * @param {{ fromBack?: boolean }} [options]
 */
export function dispatchEscape(doc, { fromBack = false } = {}) {
    const view = doc.defaultView ?? globalThis;
    const event = new view.KeyboardEvent('keydown', {
        key: 'Escape',
        code: 'Escape',
        bubbles: true,
        cancelable: true
    });
    if (fromBack) {
        Object.defineProperty(event, BACK_ESCAPE_FLAG, { value: true });
    }
    doc.dispatchEvent(event);
}

/**
 * @param {Event | null | undefined} event
 * @returns {boolean}
 */
export function isBackEscape(event) {
    return Boolean(event && /** @type {any} */ (event)[BACK_ESCAPE_FLAG]);
}

/**
 * escapeKeyDown handler of the entity dialog host. Reka delivers an Escape to its top layer only, so when the back
 * press's Escape arrives here the main dialog is on top (DESIGN.md §6 step 1): with more than one crumb it steps back
 * one crumb instead of closing. A real Escape key keeps closing the dialog, as on PC.
 *
 * @param {Event} event
 * @param {{ dialogCrumbs?: unknown[]; jumpBackDialogCrumb: () => void } | null | undefined} ui
 * @returns {boolean} True when the Escape became a crumb step
 */
export function handleMainDialogEscape(event, ui) {
    if (!isBackEscape(event) || !ui || (ui.dialogCrumbs?.length ?? 0) <= 1) {
        return false;
    }
    event.preventDefault();
    ui.jumpBackDialogCrumb();
    return true;
}

/**
 * Sends the back presses' Escapes one at a time, each once the layer closed by the previous one has left reka's stack.
 *
 * @param {Document} doc
 * @returns {{ request: () => void }}
 */
export function createEscapeQueue(doc) {
    const view = doc.defaultView ?? globalThis;
    let pending = 0;
    let busy = false;
    // Waits spent on the Escape at the head of the queue; bounded so a layer that never finishes closing cannot
    // swallow presses.
    let waits = 0;

    const later = (fn, ms = 0) => view.setTimeout(fn, ms);

    function waitFor(layer) {
        busy = true;
        waits += 1;
        let done = false;
        let timer = 0;
        const next = () => {
            if (done) return;
            done = true;
            view.clearTimeout(timer);
            layer.removeEventListener('animationend', next);
            // reka's Presence unmounts the layer from its own animationend handler; look again on the next task.
            later(flush);
        };
        timer = later(next, CLOSING_LAYER_WAIT_MS);
        layer.addEventListener('animationend', next);
    }

    function flush() {
        busy = false;
        if (pending === 0) return;
        if (!hasOpenLayer(doc)) {
            // Everything closed meanwhile: the extra presses have nothing left to close.
            pending = 0;
            waits = 0;
            return;
        }
        const closing = waits < 2 ? findClosingFloatingLayer(doc) : null;
        if (closing) {
            waitFor(closing);
            return;
        }
        pending -= 1;
        waits = 0;
        dispatchEscape(doc, { fromBack: true });
        // Let the layer that took this Escape start closing (a Vue render) before another one is sent.
        busy = true;
        later(flush);
    }

    return {
        request() {
            pending += 1;
            if (!busy) {
                flush();
            }
        }
    };
}

/**
 * @param {object} deps
 * @param {() => any} deps.getRouter Returns the vue-router instance
 * @param {{ friendsPanelOpen: boolean; navSheetOpen: boolean }} deps.shell Shell panel state
 * @param {() => boolean} [deps.closeShellPanels] Closes the friends panel / nav sheet; true when one was open
 * @param {Document} [deps.doc]
 * @param {() => any} [deps.getHistoryState]
 * @returns {() => boolean}
 */
export function createBackHandler({ getRouter, shell, closeShellPanels, doc, getHistoryState }) {
    const documentRef = doc ?? globalThis.document;
    const readHistoryState = getHistoryState ?? (() => globalThis.history?.state ?? null);
    const escapes = createEscapeQueue(documentRef);

    return function handleBack() {
        // 1. An open reka layer: Escape goes to the one reka considers on top, and the main dialog turns it into a
        //    crumb step (handleMainDialogEscape). Layers that block Escape (for example the database upgrade dialog)
        //    stay open; the press is still handled.
        if (hasOpenLayer(documentRef)) {
            escapes.request();
            return true;
        }

        // 2. The friends panel or the nav sheet.
        if (shell?.friendsPanelOpen || shell?.navSheetOpen) {
            if (closeShellPanels) {
                closeShellPanels();
            } else {
                shell.friendsPanelOpen = false;
                shell.navSheetOpen = false;
            }
            return true;
        }

        // 3. Router history (hash history keeps `back` in history.state).
        const router = getRouter?.();
        const state = readHistoryState();
        if (router && state && state.back) {
            let target = null;
            try {
                target = router.resolve(state.back);
            } catch {
                target = null;
            }
            // Going back to the login route only bounces back to the start page (router guard); treat it as the start.
            if (target?.name !== 'login') {
                router.back();
                return true;
            }
        }

        // 4. Nothing to go back to: native moves the task to the back.
        return false;
    };
}

/**
 * Registers the handler on window.__vrcxAndroid (native → JS entry points, docs/ARCHITECTURE.md §4.4 item 6).
 * The shim's handleBack() calls backHandler(); a fallback handleBack is provided when the shim did not define one
 * (dev preview harness).
 *
 * @param {() => boolean} handler
 * @param {Window} [win]
 */
export function registerBackHandler(handler, win = globalThis.window) {
    if (!win) return;
    const api = win.__vrcxAndroid ?? (win.__vrcxAndroid = {});
    api.backHandler = handler;
    if (typeof api.handleBack !== 'function') {
        api.handleBack = () => {
            try {
                return api.backHandler?.() === true;
            } catch (error) {
                console.error('Android back handler failed', error);
                return false;
            }
        };
    }
}

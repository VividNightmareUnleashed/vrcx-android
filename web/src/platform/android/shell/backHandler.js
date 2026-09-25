// Android back button (docs/DESIGN.md §6).
//
// Native calls window.__vrcxAndroid.handleBack(); the shim forwards to the registered backHandler, which returns true
// when the press was handled. On false native moves the task to the back; the app never finishes itself.
//
// Modals are never closed by toggling their state: the helper modals (confirm/prompt/OTP) resolve their promises only
// from their escapeKeyDown handlers, so every layer is closed with a synthetic Escape keydown that reka routes to its
// highest layer.

const LAYER_SELECTOR = '[data-dismissable-layer]';

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
 * The top-most open reka dismissable layer: the layer holding focus (reka moves focus into the newest modal layer),
 * else the last one in document order (popovers, menus and sheets are appended to <body> as they open).
 *
 * @param {Document} doc
 * @returns {Element | null}
 */
export function findTopLayer(doc) {
    const layers = Array.from(doc.querySelectorAll(LAYER_SELECTOR)).filter(isLiveLayer);
    if (layers.length === 0) {
        return null;
    }
    const active = doc.activeElement;
    if (active && active !== doc.body && active !== doc.documentElement) {
        for (let i = layers.length - 1; i >= 0; i--) {
            if (layers[i].contains(active)) {
                return layers[i];
            }
        }
    }
    return layers[layers.length - 1];
}

/**
 * The entity dialog host (components/dialogs/MainDialogContainer.vue).
 *
 * @param {Element} layer
 * @returns {boolean}
 */
export function isMainDialogLayer(layer) {
    if (!layer) return false;
    if (layer.matches('[data-vrcx-main-dialog]')) return true;
    return Boolean(layer.querySelector(':scope > [data-slot="breadcrumb"]'));
}

/**
 * @param {Document} doc
 */
export function dispatchEscape(doc) {
    const view = doc.defaultView ?? globalThis;
    const event = new view.KeyboardEvent('keydown', {
        key: 'Escape',
        code: 'Escape',
        bubbles: true,
        cancelable: true
    });
    doc.dispatchEvent(event);
}

/**
 * @param {object} deps
 * @param {() => any} deps.getUiStore Returns the Pinia ui store (window.$pinia.ui)
 * @param {() => any} deps.getRouter Returns the vue-router instance
 * @param {{ friendsPanelOpen: boolean; navSheetOpen: boolean }} deps.shell Shell panel state
 * @param {() => boolean} [deps.closeShellPanels] Closes the friends panel / nav sheet; true when one was open
 * @param {Document} [deps.doc]
 * @param {() => any} [deps.getHistoryState]
 * @returns {() => boolean}
 */
export function createBackHandler({ getUiStore, getRouter, shell, closeShellPanels, doc, getHistoryState }) {
    const documentRef = doc ?? globalThis.document;
    const readHistoryState = getHistoryState ?? (() => globalThis.history?.state ?? null);

    return function handleBack() {
        // 1. An open reka layer (dialog, alert, sheet, menu, popover, select, tooltip).
        const top = findTopLayer(documentRef);
        if (top) {
            const ui = getUiStore?.();
            if (ui && isMainDialogLayer(top) && (ui.dialogCrumbs?.length ?? 0) > 1) {
                ui.jumpBackDialogCrumb();
                return true;
            }
            // Layers that block Escape (for example the database upgrade dialog) stay open; the press is still handled.
            dispatchEscape(documentRef);
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

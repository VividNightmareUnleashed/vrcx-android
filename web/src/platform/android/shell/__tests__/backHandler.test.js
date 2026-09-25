import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
    CLOSING_LAYER_WAIT_MS,
    createBackHandler,
    dispatchEscape,
    findClosingFloatingLayer,
    handleMainDialogEscape,
    hasOpenLayer,
    isBackEscape,
    registerBackHandler
} from '../backHandler';

function addLayer({ parent = document.body, attrs = {} } = {}) {
    const el = document.createElement('div');
    el.setAttribute('data-dismissable-layer', '');
    for (const [key, value] of Object.entries(attrs)) {
        el.setAttribute(key, value);
    }
    parent.appendChild(el);
    return el;
}

/** Floating content (menu, popover, tooltip) inside reka's popper wrapper. */
function addFloatingLayer(attrs = {}) {
    const wrapper = document.createElement('div');
    wrapper.setAttribute('data-reka-popper-content-wrapper', '');
    document.body.appendChild(wrapper);
    return addLayer({ parent: wrapper, attrs });
}

function createSetup({ historyState = null, routeName = 'feed' } = {}) {
    const router = {
        back: vi.fn(),
        resolve: vi.fn(() => ({ name: routeName }))
    };
    const shell = { friendsPanelOpen: false, navSheetOpen: false };
    const closeShellPanels = vi.fn(() => {
        const wasOpen = shell.friendsPanelOpen || shell.navSheetOpen;
        shell.friendsPanelOpen = false;
        shell.navSheetOpen = false;
        return wasOpen;
    });
    const escapes = [];
    const onKeydown = (event) => escapes.push(event);
    document.addEventListener('keydown', onKeydown);
    const handleBack = createBackHandler({
        getRouter: () => router,
        shell,
        closeShellPanels,
        doc: document,
        getHistoryState: () => historyState
    });
    return {
        router,
        shell,
        closeShellPanels,
        escapes,
        handleBack,
        cleanup: () => document.removeEventListener('keydown', onKeydown)
    };
}

describe('Android back handler (DESIGN.md §6)', () => {
    let setup;

    beforeEach(() => {
        document.body.innerHTML = '';
    });

    afterEach(() => {
        setup?.cleanup();
        setup = null;
        vi.useRealTimers();
        document.body.innerHTML = '';
    });

    it('sends a back-marked Escape to an open layer instead of toggling modal state, and reports it handled', () => {
        setup = createSetup();
        addLayer({ attrs: { 'data-slot': 'alert-dialog-content' } });

        expect(setup.handleBack()).toBe(true);
        expect(setup.escapes).toHaveLength(1);
        expect(setup.escapes[0].key).toBe('Escape');
        expect(setup.escapes[0].bubbles).toBe(true);
        expect(isBackEscape(setup.escapes[0])).toBe(true);
        expect(setup.router.back).not.toHaveBeenCalled();
    });

    it('waits for a menu that is still playing its exit animation, which reka would hand the Escape to', () => {
        vi.useFakeTimers();
        setup = createSetup();
        addLayer({ attrs: { 'data-vrcx-main-dialog': '', 'data-state': 'open' } });
        // A menu closed a moment ago: reka keeps it mounted (and in its layer stack) until the animation ends.
        const closingMenu = addFloatingLayer({ role: 'menu', 'data-state': 'closed' });
        expect(findClosingFloatingLayer(document)).toBe(closingMenu);

        expect(setup.handleBack()).toBe(true);
        expect(setup.escapes).toHaveLength(0);

        closingMenu.parentElement.remove();
        closingMenu.dispatchEvent(new Event('animationend'));
        vi.advanceTimersByTime(0);
        expect(setup.escapes).toHaveLength(1);
        expect(isBackEscape(setup.escapes[0])).toBe(true);
    });

    it('does not wait forever for a floating layer that never finishes closing', () => {
        vi.useFakeTimers();
        setup = createSetup();
        addLayer({ attrs: { 'data-slot': 'dialog-content' } });
        addFloatingLayer({ role: 'menu', 'data-state': 'closed' });

        setup.handleBack();
        vi.advanceTimersByTime(CLOSING_LAYER_WAIT_MS * 2 + 10);

        expect(setup.escapes).toHaveLength(1);
    });

    it('does not wait for a closing dialog (reka drops it from its stack as soon as it starts closing)', () => {
        setup = createSetup();
        addLayer({ attrs: { 'data-slot': 'sheet-content' } });
        addLayer({ attrs: { 'data-slot': 'dialog-content', 'data-state': 'closed' } });

        expect(setup.handleBack()).toBe(true);
        expect(setup.escapes).toHaveLength(1);
    });

    it('sends one Escape per press, each after the previous layer had a chance to close', () => {
        vi.useFakeTimers();
        setup = createSetup();
        addLayer({ attrs: { 'data-slot': 'dialog-content' } });
        addFloatingLayer({ role: 'menu' });

        setup.handleBack();
        setup.handleBack();
        expect(setup.escapes).toHaveLength(1);

        vi.advanceTimersByTime(0);
        expect(setup.escapes).toHaveLength(2);
    });

    it('skips hidden layers and falls through to the shell when nothing visible is open', () => {
        setup = createSetup();
        setup.shell.navSheetOpen = true;
        const tooltip = addLayer({ attrs: { 'data-slot': 'tooltip-content', hidden: '' } });

        expect(hasOpenLayer(document)).toBe(false);
        expect(setup.handleBack()).toBe(true);
        expect(setup.closeShellPanels).toHaveBeenCalledTimes(1);
        expect(setup.escapes).toHaveLength(0);
        expect(tooltip.isConnected).toBe(true);
    });

    it('counts the press as handled even when the layer refuses Escape', () => {
        setup = createSetup();
        const layer = addLayer({ attrs: { 'data-slot': 'alert-dialog-content' } });
        const blocker = (event) => event.preventDefault();
        document.addEventListener('keydown', blocker);

        expect(setup.handleBack()).toBe(true);
        expect(layer.isConnected).toBe(true);
        document.removeEventListener('keydown', blocker);
    });

    it('closes the friends panel or the nav sheet when no layer is open', () => {
        setup = createSetup({ historyState: { back: '/feed' } });
        setup.shell.friendsPanelOpen = true;

        expect(setup.handleBack()).toBe(true);
        expect(setup.closeShellPanels).toHaveBeenCalledTimes(1);
        expect(setup.shell.friendsPanelOpen).toBe(false);
        expect(setup.router.back).not.toHaveBeenCalled();

        setup.shell.navSheetOpen = true;
        expect(setup.handleBack()).toBe(true);
        expect(setup.shell.navSheetOpen).toBe(false);
    });

    it('goes back in router history when there is somewhere to go', () => {
        setup = createSetup({ historyState: { back: '/game-log', current: '/feed' }, routeName: 'game-log' });

        expect(setup.handleBack()).toBe(true);
        expect(setup.router.back).toHaveBeenCalledTimes(1);
    });

    it('does not go back to the login route', () => {
        setup = createSetup({ historyState: { back: '/login' }, routeName: 'login' });

        expect(setup.handleBack()).toBe(false);
        expect(setup.router.back).not.toHaveBeenCalled();
    });

    it('returns false at the start of history so native moves the task to the back', () => {
        setup = createSetup({ historyState: { back: null, current: '/feed' } });

        expect(setup.handleBack()).toBe(false);
        expect(setup.router.back).not.toHaveBeenCalled();
        expect(setup.escapes).toHaveLength(0);
    });

    it('dispatchEscape fires a bubbling, cancelable Escape keydown on the document', () => {
        const seen = [];
        const listener = (event) => seen.push(event);
        window.addEventListener('keydown', listener);
        dispatchEscape(document);
        dispatchEscape(document, { fromBack: true });
        window.removeEventListener('keydown', listener);

        expect(seen).toHaveLength(2);
        expect(seen[0].key).toBe('Escape');
        expect(seen[0].code).toBe('Escape');
        expect(seen[0].cancelable).toBe(true);
        expect(isBackEscape(seen[0])).toBe(false);
        expect(isBackEscape(seen[1])).toBe(true);
    });
});

describe('handleMainDialogEscape (the main dialog is reka top layer)', () => {
    function backEscape() {
        let event;
        const listener = (e) => (event = e);
        document.addEventListener('keydown', listener);
        dispatchEscape(document, { fromBack: true });
        document.removeEventListener('keydown', listener);
        return event;
    }

    function createUi(crumbs) {
        return { dialogCrumbs: crumbs, jumpBackDialogCrumb: vi.fn() };
    }

    it('steps back one crumb instead of closing when there is more than one crumb', () => {
        const ui = createUi([{ type: 'user' }, { type: 'world' }]);
        const event = backEscape();

        expect(handleMainDialogEscape(event, ui)).toBe(true);
        expect(ui.jumpBackDialogCrumb).toHaveBeenCalledTimes(1);
        expect(event.defaultPrevented).toBe(true);
    });

    it('lets the dialog close on its last crumb', () => {
        const ui = createUi([{ type: 'user' }]);
        const event = backEscape();

        expect(handleMainDialogEscape(event, ui)).toBe(false);
        expect(ui.jumpBackDialogCrumb).not.toHaveBeenCalled();
        expect(event.defaultPrevented).toBe(false);
    });

    it('leaves a real Escape key alone (it closes the dialog, as on PC)', () => {
        const ui = createUi([{ type: 'user' }, { type: 'world' }]);
        const event = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true });

        expect(handleMainDialogEscape(event, ui)).toBe(false);
        expect(ui.jumpBackDialogCrumb).not.toHaveBeenCalled();
    });
});

describe('registerBackHandler', () => {
    it('sets backHandler and a fallback handleBack when the shim did not provide one', () => {
        const win = {};
        const handler = vi.fn(() => true);
        registerBackHandler(handler, win);

        expect(win.__vrcxAndroid.backHandler).toBe(handler);
        expect(win.__vrcxAndroid.handleBack()).toBe(true);
        expect(handler).toHaveBeenCalledTimes(1);
    });

    it('keeps the shim handleBack and never throws into native code', () => {
        const shimHandleBack = vi.fn();
        const win = { __vrcxAndroid: { handleBack: shimHandleBack } };
        registerBackHandler(() => true, win);
        expect(win.__vrcxAndroid.handleBack).toBe(shimHandleBack);

        const failing = {};
        registerBackHandler(() => {
            throw new Error('boom');
        }, failing);
        const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
        expect(failing.__vrcxAndroid.handleBack()).toBe(false);
        errorSpy.mockRestore();
    });
});

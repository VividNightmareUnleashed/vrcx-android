import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
    createBackHandler,
    dispatchEscape,
    findTopLayer,
    isMainDialogLayer,
    registerBackHandler
} from '../backHandler';

function addLayer({ parent = document.body, attrs = {}, focusable = false } = {}) {
    const el = document.createElement('div');
    el.setAttribute('data-dismissable-layer', '');
    for (const [key, value] of Object.entries(attrs)) {
        el.setAttribute(key, value);
    }
    if (focusable) {
        const button = document.createElement('button');
        el.appendChild(button);
        el.focusTarget = button;
    }
    parent.appendChild(el);
    return el;
}

function createSetup({ crumbs = [], historyState = null, routeName = 'feed' } = {}) {
    const ui = {
        dialogCrumbs: crumbs,
        jumpBackDialogCrumb: vi.fn(),
        closeMainDialog: vi.fn()
    };
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
        getUiStore: () => ui,
        getRouter: () => router,
        shell,
        closeShellPanels,
        doc: document,
        getHistoryState: () => historyState
    });
    return {
        ui,
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
        document.body.innerHTML = '';
    });

    it('sends Escape to an open layer instead of toggling modal state, and reports it handled', () => {
        setup = createSetup();
        addLayer({ attrs: { 'data-slot': 'alert-dialog-content' } });

        expect(setup.handleBack()).toBe(true);
        expect(setup.escapes).toHaveLength(1);
        expect(setup.escapes[0].key).toBe('Escape');
        expect(setup.escapes[0].bubbles).toBe(true);
        expect(setup.ui.jumpBackDialogCrumb).not.toHaveBeenCalled();
        expect(setup.ui.closeMainDialog).not.toHaveBeenCalled();
        expect(setup.router.back).not.toHaveBeenCalled();
    });

    it('steps back one crumb when the main entity dialog is on top with more than one crumb', () => {
        setup = createSetup({ crumbs: [{ type: 'user' }, { type: 'world' }] });
        addLayer({ attrs: { 'data-vrcx-main-dialog': '', 'data-slot': 'dialog-content' } });

        expect(setup.handleBack()).toBe(true);
        expect(setup.ui.jumpBackDialogCrumb).toHaveBeenCalledTimes(1);
        expect(setup.escapes).toHaveLength(0);
    });

    it('recognises the main dialog by its breadcrumb too (PC frame on tablets)', () => {
        setup = createSetup({ crumbs: [{ type: 'user' }, { type: 'avatar' }] });
        const layer = addLayer({ attrs: { 'data-slot': 'dialog-content' } });
        const crumb = document.createElement('nav');
        crumb.setAttribute('data-slot', 'breadcrumb');
        layer.appendChild(crumb);

        expect(isMainDialogLayer(layer)).toBe(true);
        expect(setup.handleBack()).toBe(true);
        expect(setup.ui.jumpBackDialogCrumb).toHaveBeenCalledTimes(1);
    });

    it('closes the main dialog with Escape when it holds a single crumb', () => {
        setup = createSetup({ crumbs: [{ type: 'user' }] });
        addLayer({ attrs: { 'data-vrcx-main-dialog': '' } });

        expect(setup.handleBack()).toBe(true);
        expect(setup.ui.jumpBackDialogCrumb).not.toHaveBeenCalled();
        expect(setup.escapes).toHaveLength(1);
    });

    it('lets a menu over the main dialog close first (last layer in DOM order)', () => {
        setup = createSetup({ crumbs: [{ type: 'user' }, { type: 'world' }] });
        addLayer({ attrs: { 'data-vrcx-main-dialog': '' } });
        addLayer({ attrs: { role: 'menu' } });

        expect(setup.handleBack()).toBe(true);
        expect(setup.ui.jumpBackDialogCrumb).not.toHaveBeenCalled();
        expect(setup.escapes).toHaveLength(1);
    });

    it('treats the layer holding focus as the top one (a dialog opened over a sheet)', () => {
        setup = createSetup({ crumbs: [{ type: 'user' }, { type: 'world' }] });
        const portal = document.createElement('div');
        document.body.appendChild(portal);
        const dialog = addLayer({ parent: portal, attrs: { 'data-vrcx-main-dialog': '' }, focusable: true });
        // The notification sheet was opened first but portals to <body>, after the dialog portal root.
        addLayer({ attrs: { 'data-slot': 'sheet-content' } });
        dialog.focusTarget.focus();

        expect(findTopLayer(document)).toBe(dialog);
        expect(setup.handleBack()).toBe(true);
        expect(setup.ui.jumpBackDialogCrumb).toHaveBeenCalledTimes(1);
    });

    it('skips a layer that is playing its exit animation', () => {
        setup = createSetup({ crumbs: [{ type: 'user' }, { type: 'world' }] });
        const dialog = addLayer({ attrs: { 'data-vrcx-main-dialog': '', 'data-state': 'open' } });
        // A menu closed a moment ago: reka keeps it mounted with data-state="closed" until the animation ends.
        addLayer({ attrs: { role: 'menu', 'data-state': 'closed' } });

        expect(findTopLayer(document)).toBe(dialog);
        expect(setup.handleBack()).toBe(true);
        expect(setup.ui.jumpBackDialogCrumb).toHaveBeenCalledTimes(1);
        expect(setup.escapes).toHaveLength(0);
    });

    it('skips hidden layers and falls through to the shell when nothing visible is open', () => {
        setup = createSetup();
        setup.shell.navSheetOpen = true;
        const tooltip = addLayer({ attrs: { 'data-slot': 'tooltip-content', hidden: '' } });

        expect(findTopLayer(document)).toBeNull();
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
        window.removeEventListener('keydown', listener);

        expect(seen).toHaveLength(1);
        expect(seen[0].key).toBe('Escape');
        expect(seen[0].code).toBe('Escape');
        expect(seen[0].cancelable).toBe(true);
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

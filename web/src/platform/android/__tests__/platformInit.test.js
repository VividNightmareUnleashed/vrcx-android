import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { nextTick, reactive } from 'vue';

vi.mock('../../../services/config.js', () => ({
    default: {
        getString: vi.fn(),
        setString: vi.fn(),
        getBool: vi.fn(),
        setBool: vi.fn()
    }
}));

import {
    NOTIFICATION_PERMISSION_ASKED_KEY,
    applyAndroidDefaults,
    connectCompanionState,
    requestNotificationPermissionOnce,
    watchFirstLogin
} from '../platformInit.js';

/**
 * In-memory stand-in for services/config.js.
 *
 * @param {Record<string, string>} initial
 */
function createConfig(initial = {}) {
    const values = new Map(Object.entries(initial));
    return {
        values,
        getString: vi.fn(async (key, fallback = null) => (values.has(key) ? values.get(key) : fallback)),
        setString: vi.fn(async (key, value) => values.set(key, String(value))),
        getBool: vi.fn(async (key, fallback = null) => (values.has(key) ? values.get(key) === 'true' : fallback)),
        setBool: vi.fn(async (key, value) => values.set(key, String(Boolean(value))))
    };
}

/** Fake native event bus with the same shape as onAndroidEvent. */
function createEvents() {
    const handlers = new Map();
    return {
        on: vi.fn((name, handler) => {
            handlers.set(name, handler);
            return () => handlers.delete(name);
        }),
        emit(name, payload) {
            handlers.get(name)?.(payload);
        },
        has: (name) => handlers.has(name)
    };
}

describe('applyAndroidDefaults', () => {
    test('seeds desktopToast to Always when it was never set', async () => {
        const config = createConfig();
        await applyAndroidDefaults(config);
        expect(config.values.get('VRCX_desktopToast')).toBe('Always');
    });

    test('keeps an existing desktopToast value', async () => {
        const config = createConfig({ VRCX_desktopToast: 'Never' });
        await applyAndroidDefaults(config);
        expect(config.values.get('VRCX_desktopToast')).toBe('Never');
        expect(config.setString).not.toHaveBeenCalled();
    });

    test('clears launchAsDesktop', async () => {
        const config = createConfig({ VRCX_desktopToast: 'Always', launchAsDesktop: 'true' });
        await applyAndroidDefaults(config);
        expect(config.values.get('launchAsDesktop')).toBe('false');
    });

    test('never throws', async () => {
        const config = createConfig();
        config.getString.mockRejectedValue(new Error('SQLiteException: database is locked'));
        vi.spyOn(console, 'error').mockImplementation(() => {});
        await expect(applyAndroidDefaults(config)).resolves.toBeUndefined();
        vi.restoreAllMocks();
    });
});

describe('connectCompanionState', () => {
    let store;
    let events;

    beforeEach(() => {
        store = { applyState: vi.fn(), applyGameState: vi.fn() };
        events = createEvents();
    });

    test('loads the initial state and follows companion-state and game-state events', async () => {
        const host = { CompanionGetState: vi.fn().mockResolvedValue({ status: 'idle' }) };
        const { ready, dispose } = connectCompanionState(store, { on: events.on, getHost: () => host });
        await ready;
        expect(store.applyState).toHaveBeenCalledWith({ status: 'idle' });

        events.emit('companion-state', { status: 'connected' });
        events.emit('game-state', { isGameRunning: true, isSteamVRRunning: false });
        expect(store.applyState).toHaveBeenLastCalledWith({ status: 'connected' });
        expect(store.applyGameState).toHaveBeenCalledWith({ isGameRunning: true, isSteamVRRunning: false });

        dispose();
        expect(events.has('companion-state')).toBe(false);
        expect(events.has('game-state')).toBe(false);
    });

    test('drops the initial state when an event arrived first', async () => {
        let resolveState;
        const host = { CompanionGetState: vi.fn(() => new Promise((resolve) => (resolveState = resolve))) };
        const { ready } = connectCompanionState(store, { on: events.on, getHost: () => host });

        events.emit('companion-state', { status: 'connected' });
        resolveState({ status: 'idle' });
        await ready;

        expect(store.applyState).toHaveBeenCalledTimes(1);
        expect(store.applyState).toHaveBeenCalledWith({ status: 'connected' });
    });

    test('works without AndroidHost and survives a failing initial load', async () => {
        vi.spyOn(console, 'error').mockImplementation(() => {});
        await connectCompanionState(store, { on: events.on, getHost: () => null }).ready;
        const host = { CompanionGetState: vi.fn().mockRejectedValue(new Error('boom')) };
        await connectCompanionState(store, { on: events.on, getHost: () => host }).ready;
        expect(store.applyState).not.toHaveBeenCalled();
        vi.restoreAllMocks();
    });
});

describe('requestNotificationPermissionOnce', () => {
    test('asks once when the permission was never requested', async () => {
        const config = createConfig();
        const host = {
            GetNotificationPermission: vi.fn().mockResolvedValue('default'),
            RequestNotificationPermission: vi.fn().mockResolvedValue('granted')
        };
        await expect(requestNotificationPermissionOnce({ config, getHost: () => host })).resolves.toBe('granted');
        expect(config.values.get(NOTIFICATION_PERMISSION_ASKED_KEY)).toBe('true');

        await expect(requestNotificationPermissionOnce({ config, getHost: () => host })).resolves.toBeNull();
        expect(host.RequestNotificationPermission).toHaveBeenCalledTimes(1);
    });

    test('does not prompt when the permission is already decided', async () => {
        const config = createConfig();
        const host = {
            GetNotificationPermission: vi.fn().mockResolvedValue('granted'),
            RequestNotificationPermission: vi.fn()
        };
        await expect(requestNotificationPermissionOnce({ config, getHost: () => host })).resolves.toBeNull();
        expect(host.RequestNotificationPermission).not.toHaveBeenCalled();
    });

    test('does nothing without AndroidHost', async () => {
        const config = createConfig();
        await expect(requestNotificationPermissionOnce({ config, getHost: () => null })).resolves.toBeNull();
        expect(config.setBool).not.toHaveBeenCalled();
    });
});

describe('watchFirstLogin', () => {
    afterEach(() => {
        vi.restoreAllMocks();
    });

    test('requests once on the first login, not on later ones', async () => {
        const state = reactive({ isLoggedIn: false });
        const request = vi.fn();
        watchFirstLogin({ state, request });

        await nextTick();
        expect(request).not.toHaveBeenCalled();

        state.isLoggedIn = true;
        await nextTick();
        expect(request).toHaveBeenCalledTimes(1);

        state.isLoggedIn = false;
        await nextTick();
        state.isLoggedIn = true;
        await nextTick();
        expect(request).toHaveBeenCalledTimes(1);
    });

    test('requests right away when already logged in', async () => {
        const state = reactive({ isLoggedIn: true });
        const request = vi.fn();
        watchFirstLogin({ state, request });
        await Promise.resolve();
        expect(request).toHaveBeenCalledTimes(1);
    });
});

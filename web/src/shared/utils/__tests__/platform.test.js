import { afterEach, describe, expect, test, vi } from 'vitest';

import {
    getAndroidHost,
    hasDesktopShell,
    hasDiscordPresence,
    hasLocalGame,
    hasLocalVrchatFiles,
    hasVrOverlay,
    isAndroid,
    onAndroidEvent,
    parseBridgeError,
    subscribeNativeEvent
} from '../platform';

describe('platform flags', () => {
    test('are desktop defaults in tests (ANDROID define is false)', () => {
        expect(isAndroid).toBe(false);
        expect(hasLocalGame).toBe(true);
        expect(hasVrOverlay).toBe(true);
        expect(hasDesktopShell).toBe(true);
        expect(hasLocalVrchatFiles).toBe(true);
        expect(hasDiscordPresence).toBe(true);
    });
});

describe('subscribeNativeEvent', () => {
    test('uses __vrcxAndroid.on when the shim provides it', () => {
        const off = vi.fn();
        const target = { __vrcxAndroid: { on: vi.fn(() => off) }, addEventListener: vi.fn() };
        const handler = vi.fn();

        const unsubscribe = subscribeNativeEvent(target, 'companion-state', handler);

        expect(target.__vrcxAndroid.on).toHaveBeenCalledWith('companion-state', handler);
        expect(target.addEventListener).not.toHaveBeenCalled();
        unsubscribe();
        expect(off).toHaveBeenCalled();
    });

    test('falls back to bridge.off when on() returns nothing', () => {
        const bridge = { on: vi.fn(), off: vi.fn() };
        const handler = vi.fn();

        subscribeNativeEvent({ __vrcxAndroid: bridge }, 'focus', handler)();

        expect(bridge.off).toHaveBeenCalledWith('focus', handler);
    });

    test('listens to vrcx-android:<name> window events otherwise and passes the detail', () => {
        const target = new EventTarget();
        const handler = vi.fn();

        const unsubscribe = subscribeNativeEvent(target, 'game-state', handler);
        target.dispatchEvent(new CustomEvent('vrcx-android:game-state', { detail: { isGameRunning: true } }));
        unsubscribe();
        target.dispatchEvent(new CustomEvent('vrcx-android:game-state', { detail: { isGameRunning: false } }));

        expect(handler).toHaveBeenCalledTimes(1);
        expect(handler).toHaveBeenCalledWith({ isGameRunning: true });
    });

    test('returns a callable no-op without a target or handler', () => {
        expect(() => subscribeNativeEvent(null, 'focus', vi.fn())()).not.toThrow();
        expect(() => subscribeNativeEvent(new EventTarget(), 'focus', null)()).not.toThrow();
    });
});

describe('onAndroidEvent / getAndroidHost', () => {
    afterEach(() => {
        delete window.AndroidHost;
        delete window.__vrcxAndroid;
    });

    test('onAndroidEvent subscribes on window', () => {
        const handler = vi.fn();
        const off = onAndroidEvent('visibility', handler);
        window.dispatchEvent(new CustomEvent('vrcx-android:visibility', { detail: { visible: true } }));
        off();
        expect(handler).toHaveBeenCalledWith({ visible: true });
    });

    test('getAndroidHost returns window.AndroidHost or null', () => {
        expect(getAndroidHost()).toBeNull();
        const host = { CompanionGetState: vi.fn() };
        window.AndroidHost = host;
        expect(getAndroidHost()).toBe(host);
    });
});

describe('parseBridgeError', () => {
    test('splits the .NET-like exception type from the message', () => {
        expect(parseBridgeError(new Error('PairingException: expired'))).toEqual({
            type: 'PairingException',
            detail: 'expired'
        });
        expect(parseBridgeError('System.OperationCanceledException: cancelled')).toEqual({
            type: 'OperationCanceledException',
            detail: 'cancelled'
        });
    });

    test('keeps plain messages as detail', () => {
        expect(parseBridgeError(new Error('socket closed'))).toEqual({ type: '', detail: 'socket closed' });
        expect(parseBridgeError(undefined)).toEqual({ type: '', detail: '' });
    });
});

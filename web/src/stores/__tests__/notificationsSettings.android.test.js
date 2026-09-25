import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const mocks = vi.hoisted(() => ({
    values: new Map(),
    config: null
}));

vi.mock('../../shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true,
    hasVrOverlay: false
}));
vi.mock('vue-i18n', async (importOriginal) => ({
    ...(await importOriginal()),
    useI18n: () => ({ t: (key) => key })
}));
vi.mock('../vr', () => ({ useVrStore: () => ({ updateVRConfigVars: vi.fn() }) }));
vi.mock('../modal', () => ({ useModalStore: () => ({}) }));
vi.mock('../../services/config', () => {
    const config = {
        getString: vi.fn(async (key, fallback = null) => (mocks.values.has(key) ? mocks.values.get(key) : fallback)),
        setString: vi.fn(async (key, value) => mocks.values.set(key, value)),
        getBool: vi.fn(async (key, fallback = null) =>
            mocks.values.has(key) ? mocks.values.get(key) === 'true' : fallback
        ),
        setBool: vi.fn(async (key, value) => mocks.values.set(key, String(value)))
    };
    mocks.config = config;
    return { default: config };
});

import { coerceNotificationCondition, useNotificationsSettingsStore } from '../settings/notifications';

async function flush() {
    for (let i = 0; i < 5; i++) await Promise.resolve();
}

describe('coerceNotificationCondition', () => {
    test('moves VR-only conditions to Always when there is no VR overlay', () => {
        expect(coerceNotificationCondition('Inside VR', false)).toBe('Always');
        expect(coerceNotificationCondition('Outside VR', false)).toBe('Always');
        expect(coerceNotificationCondition('Game Running', false)).toBe('Game Running');
        expect(coerceNotificationCondition('Desktop Mode', false)).toBe('Desktop Mode');
    });

    test('keeps every condition when VR is available', () => {
        expect(coerceNotificationCondition('Inside VR', true)).toBe('Inside VR');
    });
});

describe('useNotificationsSettingsStore on Android', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        setActivePinia(createPinia());
        mocks.values.clear();
        vi.clearAllMocks();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    test('coerces stored VR-only conditions and persists the new value', async () => {
        mocks.values.set('VRCX_desktopToast', 'Inside VR');
        mocks.values.set('VRCX_notificationTTS', 'Inside VR');
        const store = useNotificationsSettingsStore();
        await flush();

        expect(store.desktopToast).toBe('Always');
        expect(store.notificationTTS).toBe('Always');
        expect(mocks.config.setString).toHaveBeenCalledWith('VRCX_desktopToast', 'Always');
        expect(mocks.config.setString).toHaveBeenCalledWith('VRCX_notificationTTS', 'Always');
    });

    test('keeps conditions that work on Android', async () => {
        mocks.values.set('VRCX_desktopToast', 'Game Closed');
        const store = useNotificationsSettingsStore();
        await flush();

        expect(store.desktopToast).toBe('Game Closed');
        expect(mocks.config.setString).not.toHaveBeenCalledWith('VRCX_desktopToast', expect.anything());
    });

    test('turns overlay notifications and the AFK toast off without writing them', async () => {
        mocks.values.set('VRCX_overlayNotifications', 'true');
        mocks.values.set('VRCX_xsNotifications', 'true');
        mocks.values.set('VRCX_ovrtHudNotifications', 'true');
        mocks.values.set('VRCX_afkDesktopToast', 'true');
        mocks.values.set('openVR', 'true');
        const store = useNotificationsSettingsStore();
        await flush();

        expect(store.overlayNotifications).toBe(false);
        expect(store.xsNotifications).toBe(false);
        expect(store.ovrtHudNotifications).toBe(false);
        expect(store.ovrtWristNotifications).toBe(false);
        expect(store.afkDesktopToast).toBe(false);
        expect(store.openVR).toBe(false);
        expect(mocks.config.setBool).not.toHaveBeenCalled();
        expect(mocks.values.get('VRCX_overlayNotifications')).toBe('true');
    });
});

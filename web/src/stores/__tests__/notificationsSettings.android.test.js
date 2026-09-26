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

import { useNotificationsSettingsStore } from '../settings/notifications';

async function flush() {
    for (let i = 0; i < 5; i++) await Promise.resolve();
}

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

    test('keeps the Inside VR and Outside VR conditions (the PC companion reports SteamVR)', async () => {
        mocks.values.set('VRCX_desktopToast', 'Outside VR');
        mocks.values.set('VRCX_notificationTTS', 'Inside VR');
        const store = useNotificationsSettingsStore();
        await flush();

        expect(store.desktopToast).toBe('Outside VR');
        expect(store.notificationTTS).toBe('Inside VR');
        expect(mocks.config.setString).not.toHaveBeenCalledWith('VRCX_desktopToast', expect.anything());
        expect(mocks.config.setString).not.toHaveBeenCalledWith('VRCX_notificationTTS', expect.anything());
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

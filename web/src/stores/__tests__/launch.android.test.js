import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const mocks = vi.hoisted(() => ({
    host: null,
    handlers: new Map(),
    toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
}));

vi.mock('../../shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true,
    hasLocalGame: false,
    hasLocalVrchatFiles: false,
    hasVrOverlay: false,
    getAndroidHost: () => mocks.host,
    onAndroidEvent: (name, handler) => {
        mocks.handlers.set(name, handler);
        return () => mocks.handlers.delete(name);
    }
}));
// Same module isolation as launch.test.js (the store pulls in shared/utils, which reaches the router and views).
vi.mock('../../views/Feed/Feed.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../../views/Feed/columns.jsx', () => ({ columns: [] }));
vi.mock('../../plugins/router', () => ({
    router: { beforeEach: vi.fn(), push: vi.fn(), replace: vi.fn(), isReady: vi.fn().mockResolvedValue(true) },
    initRouter: vi.fn()
}));
vi.mock('../../plugins/interopApi', () => ({ initInteropApi: vi.fn() }));
vi.mock('../../services/database', () => ({ database: {} }));
vi.mock('../../services/jsonStorage', () => ({ default: vi.fn() }));
vi.mock('../../services/config', () => ({
    default: {
        getString: vi.fn(async (key) => (key === 'launchArguments' ? '--fps=144' : null))
    }
}));
vi.mock('../../services/watchState', () => ({ watchState: { isLoggedIn: false } }));
vi.mock('../../api', () => ({
    instanceRequest: { getInstanceShortName: vi.fn(), selfInvite: vi.fn() }
}));
vi.mock('vue-sonner', () => ({ toast: mocks.toast }));
vi.mock('vue-i18n', async (importOriginal) => ({
    ...(await importOriginal()),
    useI18n: () => ({ t: (key) => key })
}));

import { buildLaunchArgs, useLaunchStore } from '../launch';

async function flush() {
    await Promise.resolve();
    await Promise.resolve();
}

describe('buildLaunchArgs', () => {
    test('desktop keeps launch options and --no-vr', () => {
        expect(
            buildLaunchArgs('vrchat://launch?id=x', { launchArguments: '--fps=144', desktopMode: true, android: false })
        ).toEqual(['vrchat://launch?id=x', '--fps=144', '--no-vr']);
    });

    test('Android passes only the launch URL', () => {
        expect(
            buildLaunchArgs('vrchat://launch?id=x', { launchArguments: '--fps=144', desktopMode: true, android: true })
        ).toEqual(['vrchat://launch?id=x']);
    });
});

describe('useLaunchStore on Android', () => {
    let startGame;

    beforeEach(() => {
        setActivePinia(createPinia());
        mocks.handlers.clear();
        mocks.host = { CanLaunchVRChat: vi.fn().mockResolvedValue(true) };
        startGame = vi.fn().mockResolvedValue(true);
        globalThis.AppApi = { StartGame: startGame, StartGameFromPath: vi.fn() };
        vi.clearAllMocks();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    test('shows Launch only after AndroidHost.CanLaunchVRChat resolves true', async () => {
        const store = useLaunchStore();
        expect(store.canLaunchGame).toBe(false);
        await flush();
        expect(mocks.host.CanLaunchVRChat).toHaveBeenCalledTimes(1);
        expect(store.canLaunchGame).toBe(true);
    });

    test('keeps Launch hidden when no app handles vrchat://launch', async () => {
        mocks.host.CanLaunchVRChat.mockResolvedValue(false);
        const store = useLaunchStore();
        await flush();
        expect(store.canLaunchGame).toBe(false);
    });

    test('keeps Launch hidden when the check fails or AndroidHost is missing', async () => {
        vi.spyOn(console, 'error').mockImplementation(() => {});
        mocks.host.CanLaunchVRChat.mockRejectedValue(new Error('SecurityException: denied'));
        const store = useLaunchStore();
        await flush();
        expect(store.canLaunchGame).toBe(false);

        mocks.host = null;
        await expect(store.refreshCanLaunchGame(true)).resolves.toBe(false);
        vi.restoreAllMocks();
    });

    test('re-checks when the app returns to the foreground, at most every 30 s', async () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
        const store = useLaunchStore();
        await flush();
        expect(mocks.host.CanLaunchVRChat).toHaveBeenCalledTimes(1);

        mocks.handlers.get('focus')();
        await flush();
        expect(mocks.host.CanLaunchVRChat).toHaveBeenCalledTimes(1);

        vi.setSystemTime(new Date('2026-01-01T00:01:00Z'));
        mocks.host.CanLaunchVRChat.mockResolvedValue(false);
        mocks.handlers.get('visibility')({ visible: true });
        await flush();
        expect(mocks.host.CanLaunchVRChat).toHaveBeenCalledTimes(2);
        expect(store.canLaunchGame).toBe(false);

        vi.setSystemTime(new Date('2026-01-01T00:02:00Z'));
        mocks.handlers.get('visibility')({ visible: false });
        await flush();
        expect(mocks.host.CanLaunchVRChat).toHaveBeenCalledTimes(2);
    });

    test('launchGame opens only the vrchat:// URL and uses the Android wording', async () => {
        const store = useLaunchStore();
        await store.launchGame('wrld_1:123~private(usr_1)', 'short', true);
        expect(startGame).toHaveBeenCalledWith(
            'vrchat://launch?ref=vrcx.app&id=wrld_1:123~private(usr_1)&shortName=short'
        );
        expect(mocks.toast.success).toHaveBeenCalledWith('android.launch.launched');

        startGame.mockResolvedValue(false);
        await store.launchGame('wrld_1:123~private(usr_1)', 'short', false);
        expect(mocks.toast.error).toHaveBeenCalledWith('android.launch.failed');
        expect(globalThis.AppApi.StartGameFromPath).not.toHaveBeenCalled();
    });

    test('launch options are not available', () => {
        const store = useLaunchStore();
        store.showLaunchOptions();
        expect(store.isLaunchOptionsDialogVisible).toBe(false);
    });
});

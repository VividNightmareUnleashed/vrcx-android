// Android: the updateLoop's game log step goes through runAndroidGameLogFlow, which reads the game state once per tick
// together with the log lines (see coordinators/__tests__/gameLogCoordinator.android.test.js); desktop is unchanged.
// While the app is hidden the loop ticks every 15 s and the host's log-available signal reads the log
// (platform/android/hiddenLoop.js).
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const mocks = vi.hoisted(() => ({
    android: true,
    hidden: false,
    calls: [],
    events: new Map(),
    watchState: { isLoggedIn: true, isFriendsLoaded: true }
}));

vi.mock('../../shared/utils/platform', () => ({
    get isAndroid() {
        return mocks.android;
    },
    hasVrOverlay: false,
    onAndroidEvent: (name, handler) => {
        mocks.events.set(name, handler);
        return () => mocks.events.delete(name);
    }
}));
vi.mock('../../services/database', () => ({ database: { optimize: vi.fn().mockResolvedValue(undefined) } }));
vi.mock('../../api', () => ({ groupRequest: { getUsersGroupInstances: vi.fn().mockResolvedValue({}) } }));
vi.mock('../../coordinators/friendSyncCoordinator', () => ({ runRefreshFriendsListFlow: vi.fn() }));
vi.mock('../../coordinators/gameCoordinator', () => ({
    runUpdateIsGameRunningFlow: vi.fn(async (...args) => mocks.calls.push(['runUpdateIsGameRunningFlow', ...args]))
}));
vi.mock('../../coordinators/gameLogCoordinator', () => ({
    addGameLogEvent: vi.fn((line) => mocks.calls.push(['addGameLogEvent', line])),
    runAndroidGameLogFlow: vi.fn(async () => mocks.calls.push(['runAndroidGameLogFlow']))
}));
vi.mock('../../coordinators/moderationCoordinator', () => ({ runRefreshPlayerModerationsFlow: vi.fn() }));
vi.mock('../../coordinators/vrcxCoordinator', () => ({ clearVRCXCache: vi.fn() }));
vi.mock('../../coordinators/groupCoordinator', () => ({ handleGroupUserInstances: vi.fn() }));
vi.mock('../../coordinators/userCoordinator', () => ({ getCurrentUser: vi.fn(), updateAutoStateChange: vi.fn() }));
vi.mock('../auth', () => ({ useAuthStore: () => ({ updateStoredUser: vi.fn() }) }));
vi.mock('../settings/discordPresence', () => ({ useDiscordPresenceSettingsStore: () => ({ discordActive: false }) }));
vi.mock('../friend', () => ({ useFriendStore: () => ({ setIsRefreshFriendsLoading: vi.fn() }) }));
vi.mock('../user', () => ({ useUserStore: () => ({ currentUser: {} }) }));
vi.mock('../vrcxUpdater', () => ({ useVRCXUpdaterStore: () => ({ autoUpdateVRCX: 'Off' }) }));
vi.mock('../vr', () => ({ useVrStore: () => ({ vrInit: vi.fn() }) }));
vi.mock('../vrcx', () => ({
    useVrcxStore: () => ({ setIpcEnabled: vi.fn(), tryAutoBackupVrcRegistry: vi.fn(), clearVRCXCacheFrequency: 0 })
}));
vi.mock('../../services/watchState', () => ({ watchState: mocks.watchState }));
vi.mock('worker-timers', () => ({ setTimeout: vi.fn(), clearTimeout: vi.fn() }));

import * as workerTimers from 'worker-timers';
import { getCurrentUser } from '../../coordinators/userCoordinator';
import { useUpdateLoopStore } from '../updateLoop';

Object.defineProperty(document, 'hidden', { configurable: true, get: () => mocks.hidden });

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

/** Fires the tick the loop scheduled last, as the timer would after `delay`. */
async function fireScheduledTick() {
    const [callback, delay] = workerTimers.setTimeout.mock.calls.at(-1);
    vi.setSystemTime(Date.now() + delay);
    callback();
    await flush();
    return delay;
}

describe('updateLoop on Android', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        vi.clearAllMocks();
        mocks.calls.length = 0;
        mocks.events.clear();
        mocks.android = true;
        mocks.hidden = false;
        globalThis.LINUX = true;
        globalThis.LogWatcher = {
            GetLogLines: vi.fn(async () => {
                mocks.calls.push(['GetLogLines']);
                return ['line'];
            })
        };
        globalThis.AppApi = {
            CheckGameRunning: vi.fn(),
            IsGameRunning: vi.fn(async () => {
                mocks.calls.push(['IsGameRunning']);
                return true;
            }),
            IsSteamVRRunning: vi.fn().mockResolvedValue(false)
        };
    });

    test('runs the Android game log step once per tick, with no separate game check', async () => {
        const store = useUpdateLoopStore();
        await store.updateLoop();
        await store.updateLoop();
        expect(mocks.calls).toEqual([['runAndroidGameLogFlow'], ['runAndroidGameLogFlow']]);
    });

    afterEach(() => {
        useUpdateLoopStore().$dispose();
        vi.useRealTimers();
    });

    test('ticks every second while visible', async () => {
        await useUpdateLoopStore().updateLoop();
        expect(workerTimers.setTimeout).toHaveBeenLastCalledWith(expect.any(Function), 1000);
    });

    test('while hidden, reads the game log on the host signal instead of every tick', async () => {
        mocks.hidden = true;
        const store = useUpdateLoopStore();
        await store.updateLoop();
        expect(mocks.calls).toEqual([]);
        expect(workerTimers.setTimeout).toHaveBeenLastCalledWith(expect.any(Function), 15000);

        mocks.events.get('log-available')();
        await flush();
        expect(mocks.calls).toEqual([['runAndroidGameLogFlow']]);

        mocks.events.get('game-state')({ isGameRunning: false });
        await flush();
        expect(mocks.calls).toHaveLength(2);
    });

    test('while hidden, the countdowns still count seconds', async () => {
        vi.useFakeTimers({ toFake: ['Date'] });
        mocks.hidden = true;
        const store = useUpdateLoopStore();
        // The current user is refreshed every 300 s: one 1 s tick, then 15 s ticks.
        await store.updateLoop();
        for (let tick = 0; tick < 19; tick++) {
            expect(await fireScheduledTick()).toBe(15000);
        }
        expect(getCurrentUser).not.toHaveBeenCalled();
        await fireScheduledTick();
        expect(getCurrentUser).toHaveBeenCalledTimes(1);
    });

    test('runs a sleeping tick at once when the app is shown again', async () => {
        vi.useFakeTimers({ toFake: ['Date'] });
        mocks.hidden = true;
        const store = useUpdateLoopStore();
        await store.updateLoop();
        mocks.calls.length = 0;

        vi.setSystemTime(Date.now() + 4000);
        mocks.hidden = false;
        document.dispatchEvent(new Event('visibilitychange'));
        await flush();

        expect(workerTimers.clearTimeout).toHaveBeenCalled();
        expect(mocks.calls).toEqual([['runAndroidGameLogFlow']]);
        expect(workerTimers.setTimeout).toHaveBeenLastCalledWith(expect.any(Function), 1000);
    });

    test('desktop keeps the upstream order and flow', async () => {
        mocks.android = false;
        await useUpdateLoopStore().updateLoop();
        expect(mocks.calls).toEqual([
            ['GetLogLines'],
            ['addGameLogEvent', 'line'],
            ['IsGameRunning'],
            ['runUpdateIsGameRunningFlow', true, false]
        ]);
    });
});

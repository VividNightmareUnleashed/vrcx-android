// Android: the updateLoop applies a game start reported by the PC companion before the tick's log lines, and runs the
// game-running check through runAndroidGameRunningCheckFlow (player list rebuild after a late start).
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const mocks = vi.hoisted(() => ({
    android: true,
    calls: [],
    watchState: { isLoggedIn: true, isFriendsLoaded: true }
}));

vi.mock('../../shared/utils/platform', () => ({
    get isAndroid() {
        return mocks.android;
    },
    hasVrOverlay: false
}));
vi.mock('../../services/database', () => ({ database: { optimize: vi.fn().mockResolvedValue(undefined) } }));
vi.mock('../../api', () => ({ groupRequest: { getUsersGroupInstances: vi.fn().mockResolvedValue({}) } }));
vi.mock('../../coordinators/friendSyncCoordinator', () => ({ runRefreshFriendsListFlow: vi.fn() }));
vi.mock('../../coordinators/gameCoordinator', () => ({
    runUpdateIsGameRunningFlow: vi.fn(async (...args) => mocks.calls.push(['runUpdateIsGameRunningFlow', ...args]))
}));
vi.mock('../../coordinators/gameLogCoordinator', () => ({
    addGameLogEvent: vi.fn((line) => mocks.calls.push(['addGameLogEvent', line])),
    runAndroidGameRunningCheckFlow: vi.fn(async (...args) =>
        mocks.calls.push(['runAndroidGameRunningCheckFlow', ...args])
    )
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
vi.mock('worker-timers', () => ({ setTimeout: vi.fn() }));

import { useUpdateLoopStore } from '../updateLoop';

describe('updateLoop on Android', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        mocks.calls.length = 0;
        mocks.android = true;
        globalThis.LINUX = true;
        globalThis.LogWatcher = {
            GetLogLines: vi.fn(async () => {
                mocks.calls.push(['GetLogLines']);
                return ['line'];
            })
        };
        globalThis.AppApi = {
            CheckGameRunning: vi.fn(),
            IsGameRunning: vi.fn().mockResolvedValue(true),
            IsSteamVRRunning: vi.fn().mockResolvedValue(false)
        };
    });

    test('applies a start before the log lines, and checks the game through the Android flow', async () => {
        await useUpdateLoopStore().updateLoop();
        expect(mocks.calls).toEqual([
            ['runAndroidGameRunningCheckFlow', true],
            ['GetLogLines'],
            ['addGameLogEvent', 'line'],
            ['runAndroidGameRunningCheckFlow']
        ]);
    });

    test('desktop keeps the upstream order and flow', async () => {
        mocks.android = false;
        await useUpdateLoopStore().updateLoop();
        expect(mocks.calls).toEqual([
            ['GetLogLines'],
            ['addGameLogEvent', 'line'],
            ['runUpdateIsGameRunningFlow', true, false]
        ]);
    });
});

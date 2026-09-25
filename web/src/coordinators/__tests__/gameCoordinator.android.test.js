import { beforeEach, describe, expect, test, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
    userStore: {
        currentUser: { $online_for: 1000, currentAvatar: 'avtr_test' },
        markCurrentUserGameStarted: vi.fn(),
        markCurrentUserGameStopped: vi.fn()
    },
    gameStore: {
        isGameNoVR: false,
        isGameRunning: false,
        isSteamVRRunning: true,
        state: { lastCrashedTime: null },
        setLastSession: vi.fn(),
        setIsGameRunning: vi.fn(),
        setIsSteamVRRunning: vi.fn(),
        setLastCrashedTime: vi.fn(),
        getVRChatRegistryKey: vi.fn().mockResolvedValue('0')
    },
    advancedSettingsStore: {
        // Values an imported PC config may carry.
        autoSweepVRChatCache: true,
        relaunchVRChatAfterCrash: true,
        gameLogDisabled: false
    },
    launchStore: { launchGame: vi.fn() },
    vrStore: { updateVRLastLocation: vi.fn(), updateOpenVR: vi.fn() },
    workerTimers: { setTimeout: vi.fn() }
}));

vi.mock('../../shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true,
    hasLocalGame: false,
    hasLocalVrchatFiles: false
}));
vi.mock('vue-sonner', () => ({ toast: vi.fn() }));
vi.mock('../../shared/utils', () => ({
    deleteVRChatCache: vi.fn(),
    isRealInstance: vi.fn(() => true)
}));
vi.mock('../../services/database', () => ({ database: { addGamelogEventToDatabase: vi.fn() } }));
vi.mock('../../stores/settings/advanced', () => ({ useAdvancedSettingsStore: () => mocks.advancedSettingsStore }));
vi.mock('../../stores/avatar', () => ({ useAvatarStore: () => ({}) }));
vi.mock('../avatarCoordinator', () => ({ addAvatarWearTime: vi.fn() }));
vi.mock('../../stores/gameLog', () => ({ useGameLogStore: () => ({ clearNowPlaying: vi.fn(), addGameLog: vi.fn() }) }));
vi.mock('../../stores/game', () => ({ useGameStore: () => mocks.gameStore }));
vi.mock('../../stores/instance', () => ({ useInstanceStore: () => ({ removeAllQueuedInstances: vi.fn() }) }));
vi.mock('../../stores/launch', () => ({ useLaunchStore: () => mocks.launchStore }));
vi.mock('../../stores/location', () => ({
    useLocationStore: () => ({ lastLocation: { location: 'wrld_1:1', playerList: { size: 0 } } })
}));
vi.mock('../locationCoordinator', () => ({ runLastLocationResetFlow: vi.fn() }));
vi.mock('../../stores/modal', () => ({ useModalStore: () => ({ alert: vi.fn() }) }));
vi.mock('../../stores/notification', () => ({ useNotificationStore: () => ({ queueGameLogNoty: vi.fn() }) }));
vi.mock('../../stores/updateLoop', () => ({
    useUpdateLoopStore: () => ({ setIpcTimeout: vi.fn(), setNextDiscordUpdate: vi.fn() })
}));
vi.mock('../../stores/user', () => ({ useUserStore: () => mocks.userStore }));
vi.mock('../../stores/vr', () => ({ useVrStore: () => mocks.vrStore }));
vi.mock('../../stores/world', () => ({ useWorldStore: () => ({ updateVRChatWorldCache: vi.fn() }) }));
vi.mock('../../services/config', () => ({
    default: { setBool: vi.fn().mockResolvedValue(undefined), setString: vi.fn().mockResolvedValue(undefined) }
}));
vi.mock('worker-timers', () => mocks.workerTimers);

import {
    runCheckIfGameCrashedFlow,
    runCheckVRChatDebugLoggingFlow,
    runGameRunningChangedFlow
} from '../gameCoordinator';

describe('gameCoordinator on Android', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        globalThis.AppApi = {
            VrcClosedGracefully: vi.fn().mockResolvedValue(false),
            SetVRChatRegistryKey: vi.fn().mockResolvedValue(true),
            FocusWindow: vi.fn()
        };
        globalThis.AssetBundleManager = { SweepCache: vi.fn().mockResolvedValue([]) };
    });

    test('a PC crash never relaunches VRChat on the phone', async () => {
        runCheckIfGameCrashedFlow();
        await Promise.resolve();
        expect(globalThis.AppApi.VrcClosedGracefully).not.toHaveBeenCalled();
        expect(mocks.workerTimers.setTimeout).not.toHaveBeenCalled();
        expect(mocks.launchStore.launchGame).not.toHaveBeenCalled();
    });

    test('the debug-logging check never touches the registry', async () => {
        await runCheckVRChatDebugLoggingFlow();
        expect(mocks.gameStore.getVRChatRegistryKey).not.toHaveBeenCalled();
        expect(globalThis.AppApi.SetVRChatRegistryKey).not.toHaveBeenCalled();
    });

    test('game exit skips the cache sweep, crash check and the delayed debug-logging timer', async () => {
        await runGameRunningChangedFlow(false);
        expect(globalThis.AssetBundleManager.SweepCache).not.toHaveBeenCalled();
        expect(globalThis.AppApi.VrcClosedGracefully).not.toHaveBeenCalled();
        expect(mocks.workerTimers.setTimeout).not.toHaveBeenCalled();
        expect(mocks.userStore.markCurrentUserGameStopped).toHaveBeenCalled();
    });
});

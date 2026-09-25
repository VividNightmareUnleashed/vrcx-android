import { beforeEach, describe, expect, test, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

// Android has no VR overlay: the VR store, the shared (wrist) feed and "open in-game" must do no work.
const mocks = vi.hoisted(() => ({
    gameStore: { isGameRunning: true, isGameNoVR: false, isSteamVRRunning: true, setIsHmdAfk: vi.fn() },
    advancedSettingsStore: { selfInviteOverride: false, progressPie: true },
    notificationsSettingsStore: {
        openVR: true,
        overlayNotifications: true,
        sharedFeedFilters: { wrist: { OnPlayerJoining: 'Everyone' }, noty: {} }
    },
    wristOverlaySettingsStore: { openVRAlways: true, overlayWrist: true, overlaybutton: true, overlayHand: '0' }
}));

vi.mock('../../shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true,
    hasVrOverlay: false,
    hasLocalGame: false
}));
vi.mock('../../shared/utils', () => ({
    isRpcWorld: vi.fn(() => false),
    compareByCreatedAt: vi.fn(),
    getGroupName: vi.fn(),
    getWorldName: vi.fn()
}));
vi.mock('../../services/database', () => ({ database: { lookupFeedDatabase: vi.fn().mockResolvedValue([]) } }));
vi.mock('../../api', () => ({ inviteMessagesRequest: {} }));
vi.mock('../settings/advanced', () => ({ useAdvancedSettingsStore: () => mocks.advancedSettingsStore }));
vi.mock('../settings/appearance', () => ({ useAppearanceSettingsStore: () => ({ isDarkMode: true }) }));
vi.mock('../settings/notifications', () => ({
    useNotificationsSettingsStore: () => mocks.notificationsSettingsStore
}));
vi.mock('../settings/wristOverlay', () => ({
    useWristOverlaySettingsStore: () => mocks.wristOverlaySettingsStore
}));
vi.mock('../friend', () => ({
    useFriendStore: () => ({
        friends: new Map(),
        localFavoriteFriends: new Map(),
        updateOnlineFriendCounter: vi.fn()
    })
}));
vi.mock('../gameLog', () => ({ useGameLogStore: () => ({ nowPlaying: {} }) }));
vi.mock('../game', () => ({ useGameStore: () => mocks.gameStore }));
vi.mock('../location', () => ({
    useLocationStore: () => ({
        lastLocation: { location: 'wrld_1:1', playerList: new Map(), friendList: new Map() }
    })
}));
vi.mock('../photon', () => ({ usePhotonStore: () => ({}) }));
vi.mock('../user', () => ({
    useUserStore: () => ({ currentUser: { id: 'usr_me' }, currentTravelers: new Map(), customUserTags: new Map() })
}));
vi.mock('../instance', () => ({ useInstanceStore: () => ({ getInstanceName: vi.fn() }) }));
vi.mock('../moderation', () => ({ useModerationStore: () => ({}) }));
vi.mock('../notification', () => ({ useNotificationStore: () => ({}) }));

import { useInviteStore } from '../invite';
import { useSharedFeedStore } from '../sharedFeed';
import { useVrStore } from '../vr';

describe('VR overlay gates on Android', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        vi.clearAllMocks();
        globalThis.AppApi = { ExecuteVrOverlayFunction: vi.fn(), SetVR: vi.fn() };
        globalThis.window.electron = { updateVr: vi.fn() };
    });

    test('VR store functions never reach the overlay bridge', async () => {
        const vr = useVrStore();
        vr.vrInit();
        await vr.saveOpenVROption();
        vr.updateVrNowPlaying();
        vr.updateVRLastLocation();
        vr.updateVRConfigVars();
        vr.updateOpenVR();

        expect(globalThis.AppApi.ExecuteVrOverlayFunction).not.toHaveBeenCalled();
        expect(globalThis.AppApi.SetVR).not.toHaveBeenCalled();
        expect(window.electron.updateVr).not.toHaveBeenCalled();
        expect(mocks.gameStore.setIsHmdAfk).toHaveBeenCalledWith(false);
    });

    test('the shared wrist feed is neither built nor sent', async () => {
        const sharedFeed = useSharedFeedStore();
        await sharedFeed.addEntry({ type: 'GPS', userId: 'usr_1', location: 'wrld_1:1', created_at: '' });
        sharedFeed.addTag('usr_1', '#fff');
        await sharedFeed.loadSharedFeed();
        await sharedFeed.sendSharedFeed();

        expect(sharedFeed.sharedFeedData).toEqual([]);
        expect(globalThis.AppApi.ExecuteVrOverlayFunction).not.toHaveBeenCalled();
    });

    test('"open in-game" is never offered, so every path self-invites', () => {
        const invite = useInviteStore();
        expect(mocks.gameStore.isGameRunning).toBe(true);
        expect(invite.canOpenInstanceInGame).toBe(false);
    });
});

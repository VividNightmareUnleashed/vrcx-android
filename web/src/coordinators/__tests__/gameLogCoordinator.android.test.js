// Android: the player list after the PC companion reports VRChat running late (runAndroidGameRunningCheckFlow), and
// the guards that keep a reload from overwriting or double-counting what live log lines did meanwhile.
import { beforeEach, describe, expect, test, vi } from 'vitest';

const LOCATION_A = 'wrld_00000000-0000-4000-8000-00000000a001:12345~region(eu)';
const LOCATION_B = 'wrld_00000000-0000-4000-8000-00000000a002:67890~region(us)';
const ME = 'usr_00000000-0000-4000-8000-000000000001';
const P1 = 'usr_00000000-0000-4000-8000-000000000101';
const P2 = 'usr_00000000-0000-4000-8000-000000000102';
const P3 = 'usr_00000000-0000-4000-8000-000000000103';

/** A store whose unknown members are spies, so flows can call whatever they need. */
function spyStore(state) {
    return new Proxy(state, {
        get(target, prop) {
            if (!(prop in target) && typeof prop === 'string') {
                target[prop] = vi.fn();
            }
            return target[prop];
        }
    });
}

function emptyLocation() {
    return { date: 0, location: '', name: '', playerList: new Map(), friendList: new Map() };
}

const mocks = vi.hoisted(() => ({
    android: true,
    watchState: { isFriendsLoaded: true },
    getGamelogDatabase: vi.fn(),
    stores: {}
}));

vi.mock('../../shared/utils/platform', () => ({
    get isAndroid() {
        return mocks.android;
    },
    hasLocalGame: false,
    hasLocalVrchatFiles: false
}));
vi.mock('../../shared/utils', () => ({
    createJoinLeaveEntry: (type, created_at, displayName, location, userId) => ({
        type,
        created_at,
        displayName,
        location,
        userId
    }),
    createLocationEntry: (created_at, location, worldId, worldName) => ({
        type: 'Location',
        created_at,
        location,
        worldId,
        worldName
    }),
    createPortalSpawnEntry: vi.fn(),
    createResourceLoadEntry: vi.fn(),
    deleteVRChatCache: vi.fn(),
    findUserByDisplayName: () => undefined,
    getGroupName: () => Promise.resolve(''),
    isRealInstance: () => true,
    parseInventoryFromUrl: vi.fn(),
    parseLocation: (tag) => ({ tag, worldId: tag.split(':')[0] }),
    parsePrintFromUrl: vi.fn(),
    replaceBioSymbols: (text) => text
}));
vi.mock('../../plugins/i18n', () => ({ i18n: { global: { t: (key) => key } } }));
vi.mock('../../services/appConfig', () => ({ AppDebug: {}, logWebRequest: vi.fn() }));
vi.mock('../../services/database', () => ({
    database: new Proxy(
        {},
        {
            get: (_target, prop) => (prop === 'getGamelogDatabase' ? mocks.getGamelogDatabase : vi.fn())
        }
    )
}));
vi.mock('../../services/watchState', () => ({ watchState: mocks.watchState }));
vi.mock('../../services/gameLog.js', () => ({ default: {} }));
vi.mock('../../services/config', () => ({
    default: { setBool: vi.fn().mockResolvedValue(undefined), setString: vi.fn().mockResolvedValue(undefined) }
}));
vi.mock('../../api', () => ({ userRequest: { getUser: vi.fn(), getPublicProfile: vi.fn() } }));
vi.mock('vue-sonner', () => ({ toast: vi.fn() }));
vi.mock('worker-timers', () => ({ setTimeout: vi.fn() }));
vi.mock('../avatarCoordinator', () => ({ addAvatarWearTime: vi.fn() }));
// The upstream reset: leave rows for everyone in the list, then an empty lastLocation (a new object).
vi.mock('../locationCoordinator', () => ({
    runLastLocationResetFlow: vi.fn(() => mocks.stores.location.setLastLocation(emptyLocation())),
    runUpdateCurrentUserLocationFlow: vi.fn()
}));

vi.mock('../../stores/settings/advanced', () => ({ useAdvancedSettingsStore: () => mocks.stores.advanced }));
vi.mock('../../stores/settings/general', () => ({ useGeneralSettingsStore: () => mocks.stores.general }));
vi.mock('../../stores/avatar', () => ({ useAvatarStore: () => mocks.stores.avatar }));
vi.mock('../../stores/friend', () => ({ useFriendStore: () => mocks.stores.friend }));
vi.mock('../../stores/gallery', () => ({ useGalleryStore: () => mocks.stores.gallery }));
vi.mock('../../stores/game', () => ({ useGameStore: () => mocks.stores.game }));
vi.mock('../../stores/gameLog', () => ({ useGameLogStore: () => mocks.stores.gameLog }));
vi.mock('../../stores/instance', () => ({ useInstanceStore: () => mocks.stores.instance }));
vi.mock('../../stores/launch', () => ({ useLaunchStore: () => mocks.stores.launch }));
vi.mock('../../stores/location', () => ({ useLocationStore: () => mocks.stores.location }));
vi.mock('../../stores/modal', () => ({ useModalStore: () => mocks.stores.modal }));
vi.mock('../../stores/notification', () => ({ useNotificationStore: () => mocks.stores.notification }));
vi.mock('../../stores/photon', () => ({ usePhotonStore: () => mocks.stores.photon }));
vi.mock('../../stores/sharedFeed', () => ({ useSharedFeedStore: () => mocks.stores.sharedFeed }));
vi.mock('../../stores/updateLoop', () => ({ useUpdateLoopStore: () => mocks.stores.updateLoop }));
vi.mock('../../stores/user', () => ({ useUserStore: () => mocks.stores.user }));
vi.mock('../../stores/vr', () => ({ useVrStore: () => mocks.stores.vr }));
vi.mock('../../stores/vrcx', () => ({ useVrcxStore: () => mocks.stores.vrcx }));
vi.mock('../../stores/world', () => ({ useWorldStore: () => mocks.stores.world }));

import { runLastLocationResetFlow } from '../locationCoordinator';
import { addGameLogEntry, runAndroidGameRunningCheckFlow, tryLoadPlayerList } from '../gameLogCoordinator';

function setUpStores() {
    const location = spyStore({ lastLocation: emptyLocation(), lastLocationDestination: '' });
    location.setLastLocation = vi.fn((value) => {
        location.lastLocation = value;
    });
    const game = spyStore({ isGameRunning: false, isSteamVRRunning: false, isGameNoVR: true, state: {} });
    game.setIsGameRunning = vi.fn((value) => {
        game.isGameRunning = value;
    });
    game.setIsSteamVRRunning = vi.fn((value) => {
        game.isSteamVRRunning = value;
    });
    mocks.stores = {
        advanced: spyStore({ gameLogDisabled: false }),
        general: spyStore({}),
        avatar: spyStore({}),
        friend: spyStore({ friends: new Map([[P1, { id: P1 }]]) }),
        gallery: spyStore({}),
        game,
        gameLog: spyStore({ state: { lastLocationAvatarList: new Map() }, addGamelogLocationToDatabase: vi.fn() }),
        instance: spyStore({}),
        launch: spyStore({}),
        location,
        modal: spyStore({}),
        notification: spyStore({}),
        photon: spyStore({ photonLobbyAvatars: new Map() }),
        sharedFeed: spyStore({}),
        updateLoop: spyStore({}),
        user: spyStore({
            currentUser: { id: ME, $online_for: 0 },
            cachedUsers: new Map([
                [P1, {}],
                [P2, {}],
                [P3, {}]
            ]),
            cachedProfiles: new Map([
                [P1, {}],
                [P2, {}],
                [P3, {}]
            ]),
            cachedUserIdsByDisplayName: new Map()
        }),
        vr: spyStore({}),
        vrcx: spyStore({}),
        world: spyStore({})
    };
}

/**
 * Game log rows ending in instance A: P1 and P2 joined, then P1 left and came back. (tryLoadPlayerList ignores a
 * Location in the first row, so an older row comes first.)
 */
function rowsOfInstanceA() {
    return [
        { type: 'OnPlayerLeft', created_at: '2026-01-01T09:59:00.000Z', userId: P3, displayName: 'Three' },
        { type: 'Location', created_at: '2026-01-01T10:00:00.000Z', location: LOCATION_A, worldName: 'Harbor' },
        { type: 'OnPlayerJoined', created_at: '2026-01-01T10:00:01.000Z', userId: ME, displayName: 'Me' },
        { type: 'OnPlayerJoined', created_at: '2026-01-01T10:01:00.000Z', userId: P1, displayName: 'One' },
        { type: 'OnPlayerJoined', created_at: '2026-01-01T10:02:00.000Z', userId: P2, displayName: 'Two' },
        { type: 'OnPlayerLeft', created_at: '2026-01-01T10:03:00.000Z', userId: P1, displayName: 'One' },
        { type: 'OnPlayerJoined', created_at: '2026-01-01T10:04:00.000Z', userId: P1, displayName: 'One' }
    ];
}

/** Makes the next getGamelogDatabase() wait until the returned function resolves it. */
function deferRows() {
    let resolve;
    mocks.getGamelogDatabase.mockImplementationOnce(
        () =>
            new Promise((done) => {
                resolve = done;
            })
    );
    return (rows) => resolve(rows);
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function joinLine(userId, displayName) {
    return { type: 'player-joined', dt: '2026-01-01T11:00:00.000Z', userId, displayName };
}

describe('player list after a late companion start (Android)', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mocks.android = true;
        mocks.watchState.isFriendsLoaded = true;
        mocks.getGamelogDatabase.mockReset();
        mocks.getGamelogDatabase.mockResolvedValue(rowsOfInstanceA());
        setUpStores();
        globalThis.AppApi = {
            IsGameRunning: vi.fn().mockResolvedValue(true),
            IsSteamVRRunning: vi.fn().mockResolvedValue(false)
        };
    });

    test('rebuilds the list when VRChat is reported running after the friends list loaded', async () => {
        await runAndroidGameRunningCheckFlow(true);

        const { lastLocation } = mocks.stores.location;
        expect(mocks.stores.game.isGameRunning).toBe(true);
        expect(runLastLocationResetFlow).toHaveBeenCalledTimes(1);
        expect(lastLocation.location).toBe(LOCATION_A);
        expect([...lastLocation.playerList.keys()]).toEqual([ME, P2, P1]);
        expect([...lastLocation.friendList.keys()]).toEqual([P1]);
        expect(mocks.stores.instance.getCurrentInstanceUserList).toHaveBeenCalled();
    });

    test('the regular check rebuilds it too when the start arrives after the log lines', async () => {
        await runAndroidGameRunningCheckFlow();
        expect(mocks.stores.location.lastLocation.playerList.size).toBe(3);
    });

    test('leaves the rebuild to the friends-list watcher until the friends list loaded', async () => {
        mocks.watchState.isFriendsLoaded = false;
        await runAndroidGameRunningCheckFlow(true);
        expect(mocks.stores.game.isGameRunning).toBe(true);
        expect(mocks.getGamelogDatabase).not.toHaveBeenCalled();
    });

    test('before the log lines only a start is applied', async () => {
        globalThis.AppApi.IsGameRunning.mockResolvedValue(false);
        await runAndroidGameRunningCheckFlow(true);
        expect(mocks.stores.game.setIsGameRunning).not.toHaveBeenCalled();
        expect(mocks.stores.vr.updateOpenVR).not.toHaveBeenCalled();

        // A stop waits for the regular check after the lines, which precede it.
        mocks.stores.game.isGameRunning = true;
        await runAndroidGameRunningCheckFlow(true);
        expect(mocks.stores.game.setIsGameRunning).not.toHaveBeenCalled();
        await runAndroidGameRunningCheckFlow();
        expect(mocks.stores.game.setIsGameRunning).toHaveBeenCalledWith(false);
        expect(mocks.getGamelogDatabase).not.toHaveBeenCalled();
    });

    test('a companion disconnect within the grace period keeps the list', async () => {
        await runAndroidGameRunningCheckFlow(true);
        const before = mocks.stores.location.lastLocation;
        runLastLocationResetFlow.mockClear();
        mocks.getGamelogDatabase.mockClear();

        // Native keeps reporting VRChat running for 120 s after the connection dropped.
        await runAndroidGameRunningCheckFlow(true);
        await runAndroidGameRunningCheckFlow();

        expect(mocks.stores.location.lastLocation).toBe(before);
        expect(before.playerList.size).toBe(3);
        expect(runLastLocationResetFlow).not.toHaveBeenCalled();
        expect(mocks.getGamelogDatabase).not.toHaveBeenCalled();
    });

    test('a join logged while the rows are read is kept, and nobody is counted twice', async () => {
        const resolveRows = deferRows();
        const flow = runAndroidGameRunningCheckFlow(true);
        await flush();

        // Live lines on the post-start (empty) lastLocation: a new player, and one the rows already hold.
        addGameLogEntry(joinLine(P3, 'Three'), '');
        addGameLogEntry(joinLine(P2, 'Two'), '');
        resolveRows(rowsOfInstanceA());
        await flow;

        const { lastLocation } = mocks.stores.location;
        expect(lastLocation.location).toBe(LOCATION_A);
        expect([...lastLocation.playerList.keys()].sort()).toEqual([ME, P1, P2, P3].sort());
        expect(lastLocation.playerList.size).toBe(4);
    });

    test('a location change logged while the rows are read wins over the rows', async () => {
        const resolveRows = deferRows();
        const flow = runAndroidGameRunningCheckFlow(true);
        await flush();

        addGameLogEntry({ type: 'location', dt: '2026-01-01T11:00:00.000Z', location: LOCATION_B, worldName: 'B' }, '');
        addGameLogEntry(joinLine(P3, 'Three'), LOCATION_B);
        resolveRows(rowsOfInstanceA());
        await flow;

        const { lastLocation } = mocks.stores.location;
        expect(lastLocation.location).toBe(LOCATION_B);
        expect([...lastLocation.playerList.keys()]).toEqual([P3]);
    });

    test('a newer live location whose row is not written yet wins over the rows', async () => {
        mocks.stores.game.isGameRunning = true;
        mocks.stores.location.setLastLocation({ ...emptyLocation(), location: LOCATION_B });
        await tryLoadPlayerList();
        expect(mocks.stores.location.lastLocation.location).toBe(LOCATION_B);
    });

    test('a stop while the rows are read cancels the rebuild', async () => {
        const resolveRows = deferRows();
        const flow = runAndroidGameRunningCheckFlow(true);
        await flush();
        mocks.stores.game.isGameRunning = false;
        resolveRows(rowsOfInstanceA());
        await flow;
        expect(mocks.stores.location.lastLocation.location).toBe('');
    });

    test('desktop keeps the upstream reload: the rows win', async () => {
        mocks.android = false;
        mocks.stores.game.isGameRunning = true;
        const resolveRows = deferRows();
        const reload = tryLoadPlayerList();
        await flush();
        addGameLogEntry({ type: 'location', dt: '2026-01-01T11:00:00.000Z', location: LOCATION_B, worldName: 'B' }, '');
        resolveRows(rowsOfInstanceA());
        await reload;
        expect(mocks.stores.location.lastLocation.location).toBe(LOCATION_A);
    });
});

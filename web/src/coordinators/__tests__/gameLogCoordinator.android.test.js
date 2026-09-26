// Android: the updateLoop's game log step (runAndroidGameLogFlow), the player list after the PC companion reports
// VRChat running, and the guards that keep a reload from restoring a previous session or overwriting what live log
// lines did meanwhile. The start/stop flows (gameCoordinator) and the reset (locationCoordinator) are the real ones,
// over an in-memory game log database, so the rows they write are checked too.
import { beforeEach, describe, expect, test, vi } from 'vitest';

const LOCATION_A = 'wrld_00000000-0000-4000-8000-00000000a001:12345~region(eu)';
const LOCATION_B = 'wrld_00000000-0000-4000-8000-00000000a002:67890~region(us)';
const LOCATION_X = 'wrld_00000000-0000-4000-8000-00000000a003:24680~region(jp)';
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

const mocks = vi.hoisted(() => {
    /** In-memory game log tables: rows as getGamelogDatabase() returns them, in insertion order. */
    const rows = [];
    const byCreatedAt = (a, b) => (a.created_at < b.created_at ? -1 : a.created_at > b.created_at ? 1 : 0);
    const database = {
        rows,
        getGamelogDatabase: vi.fn(),
        readRows: async () => rows.map((row) => ({ ...row })).sort(byCreatedAt),
        addGamelogLocationToDatabase: vi.fn((entry) => rows.push({ time: 0, ...entry })),
        addGamelogJoinLeaveToDatabase: vi.fn((entry) => rows.push({ ...entry })),
        addGamelogJoinLeaveBulk: vi.fn((entries) => rows.push(...entries.map((entry) => ({ ...entry })))),
        updateGamelogLocationTimeToDatabase: vi.fn(({ created_at, time }) => {
            for (const row of rows) {
                if (row.type === 'Location' && row.created_at === created_at) {
                    row.time = time;
                }
            }
        })
    };
    const other = new Map();
    return {
        android: true,
        watchState: { isFriendsLoaded: true },
        database,
        databaseProxy: new Proxy(database, {
            get(target, prop) {
                if (prop in target) {
                    return target[prop];
                }
                if (!other.has(prop)) {
                    other.set(prop, vi.fn());
                }
                return other.get(prop);
            }
        }),
        stores: {}
    };
});

vi.mock('../../shared/utils/platform', () => ({
    get isAndroid() {
        return mocks.android;
    },
    hasLocalGame: false,
    hasLocalVrchatFiles: false
}));
vi.mock('../../shared/utils', () => ({
    createJoinLeaveEntry: (type, created_at, displayName, location, userId, time = 0) => ({
        type,
        created_at,
        displayName,
        location,
        userId,
        time
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
    getWorldName: () => Promise.resolve(''),
    isRealInstance: (tag) => String(tag).startsWith('wrld_'),
    parseInventoryFromUrl: vi.fn(),
    parseLocation: (tag) => ({ tag, worldId: String(tag).split(':')[0], isTraveling: tag === 'traveling' }),
    parsePrintFromUrl: vi.fn(),
    replaceBioSymbols: (text) => text
}));
vi.mock('../../plugins/i18n', () => ({ i18n: { global: { t: (key) => key } } }));
vi.mock('../../services/appConfig', () => ({ AppDebug: {}, logWebRequest: vi.fn() }));
vi.mock('../../services/database', () => ({ database: mocks.databaseProxy }));
vi.mock('../../services/watchState', () => ({ watchState: mocks.watchState }));
vi.mock('../../services/config', () => ({
    default: { setBool: vi.fn().mockResolvedValue(undefined), setString: vi.fn().mockResolvedValue(undefined) }
}));
vi.mock('../../api', () => ({ userRequest: { getUser: vi.fn(), getPublicProfile: vi.fn() } }));
vi.mock('vue-sonner', () => ({ toast: vi.fn() }));
vi.mock('worker-timers', () => ({ setTimeout: vi.fn() }));
vi.mock('../avatarCoordinator', () => ({ addAvatarWearTime: vi.fn() }));

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

import { addGameLogEntry, runAndroidGameLogFlow, tryLoadPlayerList } from '../gameLogCoordinator';

const { database } = mocks;

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
        gameLog: spyStore({
            state: { lastLocationAvatarList: new Map() },
            nowPlaying: {},
            addGamelogLocationToDatabase: vi.fn((entry) => database.addGamelogLocationToDatabase(entry))
        }),
        instance: spyStore({}),
        launch: spyStore({}),
        location,
        modal: spyStore({}),
        notification: spyStore({}),
        photon: spyStore({ photonLobbyAvatars: new Map() }),
        sharedFeed: spyStore({}),
        updateLoop: spyStore({}),
        user: spyStore({
            // The VRChat API reports the current user in instance A.
            currentUser: { id: ME, $online_for: 0, $locationTag: LOCATION_A },
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

function seedRows(rows) {
    database.rows.length = 0;
    database.rows.push(...rows.map((row) => ({ ...row })));
}

/**
 * Rows of the instance VRChat is in (A, still open): P1 and P2 joined, then P1 left and came back. An older row comes
 * first (tryLoadPlayerList ignores a Location in the first row).
 */
function rowsOfInstanceA() {
    return [
        { type: 'OnPlayerLeft', created_at: '2026-01-01T09:59:00.000Z', userId: P3, displayName: 'Three', time: 1 },
        { type: 'Location', created_at: '2026-01-01T10:00:00.000Z', location: LOCATION_A, worldName: 'A', time: 0 },
        { type: 'OnPlayerJoined', created_at: '2026-01-01T10:00:01.000Z', userId: ME, displayName: 'Me' },
        { type: 'OnPlayerJoined', created_at: '2026-01-01T10:01:00.000Z', userId: P1, displayName: 'One' },
        { type: 'OnPlayerJoined', created_at: '2026-01-01T10:02:00.000Z', userId: P2, displayName: 'Two' },
        { type: 'OnPlayerLeft', created_at: '2026-01-01T10:03:00.000Z', userId: P1, displayName: 'One', time: 1 },
        { type: 'OnPlayerJoined', created_at: '2026-01-01T10:04:00.000Z', userId: P1, displayName: 'One' }
    ];
}

/** Yesterday's session in instance A, closed cleanly: the phone saw VRChat stop at 12:00. */
function rowsOfClosedSession() {
    const TWO_HOURS = 2 * 60 * 60 * 1000;
    return [
        { type: 'OnPlayerLeft', created_at: '2026-01-01T09:59:00.000Z', userId: P3, displayName: 'Three', time: 1 },
        {
            type: 'Location',
            created_at: '2026-01-01T10:00:00.000Z',
            location: LOCATION_A,
            worldName: 'A',
            time: TWO_HOURS
        },
        { type: 'OnPlayerJoined', created_at: '2026-01-01T10:00:01.000Z', userId: ME, displayName: 'Me' },
        { type: 'OnPlayerJoined', created_at: '2026-01-01T10:01:00.000Z', userId: P1, displayName: 'One' },
        { type: 'OnPlayerLeft', created_at: '2026-01-01T12:00:00.000Z', userId: P1, displayName: 'One', time: 1 },
        { type: 'OnPlayerLeft', created_at: '2026-01-01T12:00:00.000Z', userId: ME, displayName: 'Me', time: 1 }
    ];
}

/** A raw LogWatcher record as GetLogLines() returns it. */
function line(dt, type, ...args) {
    return JSON.stringify(['output_log.txt', dt, type, ...args]);
}

const locationLine = (dt, location) => line(dt, 'location', location, 'World');
const joinedLine = (dt, userId, name) => line(dt, 'player-joined', name, userId);
const leftLine = (dt, userId, name) => line(dt, 'player-left', name, userId);

/** One updateLoop tick: native reports `running`, and `lines` are queued. */
async function tick(running, lines = []) {
    AppApi.IsGameRunning.mockResolvedValue(running);
    LogWatcher.GetLogLines.mockImplementationOnce(async () => {
        mocks.gameRunningAtLines.push(mocks.stores.game.isGameRunning);
        return lines;
    });
    await runAndroidGameLogFlow();
}

/**
 * Makes the next getGamelogDatabase() answer only when the returned function is called. Its rows are those at the
 * call (the SQLite bridge runs the read before any write queued after it).
 */
function deferRows() {
    let release;
    database.getGamelogDatabase.mockImplementationOnce(() => {
        const rows = database.readRows();
        return new Promise((done) => {
            release = () => done(rows);
        });
    });
    return () => release();
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function joinEntry(userId, displayName) {
    return { type: 'player-joined', dt: '2026-01-01T11:00:00.000Z', userId, displayName };
}

const playerIds = () => [...mocks.stores.location.lastLocation.playerList.keys()];
const rowsOfType = (type) => database.rows.filter((row) => row.type === type);

beforeEach(() => {
    vi.clearAllMocks();
    mocks.android = true;
    mocks.watchState.isFriendsLoaded = true;
    mocks.gameRunningAtLines = [];
    database.getGamelogDatabase.mockReset();
    database.getGamelogDatabase.mockImplementation(database.readRows);
    seedRows(rowsOfInstanceA());
    setUpStores();
    globalThis.AppApi = {
        IsGameRunning: vi.fn().mockResolvedValue(false),
        IsSteamVRRunning: vi.fn().mockResolvedValue(false)
    };
    globalThis.LogWatcher = { GetLogLines: vi.fn().mockResolvedValue([]) };
});

describe('Android game log step: one state read per tick', () => {
    test('reads IsGameRunning once per tick and checks the game once', async () => {
        await tick(false);
        await tick(false);
        expect(AppApi.IsGameRunning).toHaveBeenCalledTimes(2);
        expect(AppApi.IsSteamVRRunning).toHaveBeenCalledTimes(2);
        expect(mocks.stores.vr.updateOpenVR).toHaveBeenCalledTimes(2);
        expect(LogWatcher.GetLogLines).toHaveBeenCalledTimes(2);
    });

    test("a start applies before the tick's lines, which then see a running game", async () => {
        seedRows([]);
        await tick(true, [locationLine('2026-01-02T09:00:30.000Z', LOCATION_B)]);

        expect(mocks.gameRunningAtLines).toEqual([true]);
        expect(mocks.stores.game.isGameRunning).toBe(true);
        expect(mocks.stores.location.lastLocation.location).toBe(LOCATION_B);
        expect(AppApi.IsGameRunning).toHaveBeenCalledTimes(1);
        expect(mocks.stores.vr.updateOpenVR).toHaveBeenCalledTimes(1);
    });

    test('a stop read before the lines applies after them', async () => {
        await tick(true);
        expect(playerIds()).toEqual([ME, P2, P1]);

        // The quit is reported after the lines that precede it: P1 left, then VRChat closed.
        await tick(false, [leftLine('2026-01-01T10:30:00.000Z', P1, 'One')]);

        expect(mocks.gameRunningAtLines).toEqual([true, true]);
        expect(mocks.stores.game.isGameRunning).toBe(false);
        const leaves = rowsOfType('OnPlayerLeft').slice(2);
        expect(leaves.map((row) => [row.userId, row.location])).toEqual([
            [P1, LOCATION_A],
            [P2, LOCATION_A],
            [ME, LOCATION_A]
        ]);
        expect(leaves[0].created_at).toBe('2026-01-01T10:30:00.000Z');
        expect(playerIds()).toEqual([]);
    });
});

describe('player list after VRChat is reported running (Android)', () => {
    test('a late companion connection rebuilds the current instance from the rows', async () => {
        await tick(true);

        const { lastLocation } = mocks.stores.location;
        expect(mocks.stores.game.isGameRunning).toBe(true);
        expect(lastLocation.location).toBe(LOCATION_A);
        expect(lastLocation.date).toBe(Date.parse('2026-01-01T10:00:00.000Z'));
        expect(playerIds()).toEqual([ME, P2, P1]);
        expect([...lastLocation.friendList.keys()]).toEqual([P1]);
        expect(mocks.stores.instance.getCurrentInstanceUserList).toHaveBeenCalled();
        // The start's reset had nothing to close.
        expect(database.updateGamelogLocationTimeToDatabase).not.toHaveBeenCalled();
        expect(database.addGamelogJoinLeaveBulk).toHaveBeenCalledWith([]);
    });

    test('the backlog lines then continue the rebuilt list, and nobody is counted twice', async () => {
        await tick(true);
        await tick(true, [
            joinedLine('2026-01-01T10:10:00.000Z', P3, 'Three'),
            leftLine('2026-01-01T10:11:00.000Z', P2, 'Two'),
            joinedLine('2026-01-01T10:12:00.000Z', P2, 'Two')
        ]);

        expect(playerIds()).toEqual([ME, P1, P3, P2]);
        const written = database.rows.slice(rowsOfInstanceA().length);
        expect(written.map((row) => [row.type, row.userId, row.location])).toEqual([
            ['OnPlayerJoined', P3, LOCATION_A],
            ['OnPlayerLeft', P2, LOCATION_A],
            ['OnPlayerJoined', P2, LOCATION_A]
        ]);
        expect(written[1].time).toBe(Date.parse('2026-01-01T10:11:00.000Z') - Date.parse('2026-01-01T10:02:00.000Z'));
    });

    test.each([
        ['offline', 'offline'],
        ['travelling', 'traveling'],
        ['in another instance', LOCATION_B]
    ])('a fresh VRChat launch (API: %s) does not restore the previous session', async (_label, apiLocation) => {
        seedRows(rowsOfClosedSession());
        mocks.stores.user.currentUser.$locationTag = apiLocation;

        await tick(true);
        expect(mocks.stores.game.isGameRunning).toBe(true);
        expect(mocks.stores.location.lastLocation.location).toBe('');
        expect(playerIds()).toEqual([]);

        // The game joins its first world.
        await tick(true, [locationLine('2026-01-02T09:00:30.000Z', LOCATION_B)]);

        expect(mocks.stores.location.lastLocation.location).toBe(LOCATION_B);
        expect(database.updateGamelogLocationTimeToDatabase).not.toHaveBeenCalled();
        expect(rowsOfType('Location')[0].time).toBe(2 * 60 * 60 * 1000);
        expect(mocks.stores.instance.addInstanceJoinHistory.mock.calls).toEqual([
            ['', '2026-01-02T09:00:30.000Z'],
            [LOCATION_B, '2026-01-02T09:00:30.000Z']
        ]);
        expect(database.addGamelogJoinLeaveBulk.mock.calls.flat(2)).toEqual([]);
    });

    test('a fresh launch after a quit the phone missed does not restore the open row either', async () => {
        seedRows(rowsOfInstanceA());
        mocks.stores.user.currentUser.$locationTag = 'offline';

        await tick(true);
        await tick(true, [locationLine('2026-01-02T09:00:30.000Z', LOCATION_B)]);

        expect(database.updateGamelogLocationTimeToDatabase).not.toHaveBeenCalled();
        expect(rowsOfType('Location')[0].time).toBe(0);
        expect(database.addGamelogJoinLeaveBulk.mock.calls.flat(2)).toEqual([]);
    });

    test('an instance the phone had from the VRChat API is closed by the start and not restored', async () => {
        // While the game was not running, runSetCurrentUserLocationFlow logged instance X from the API (Quest, or
        // the PC while the companion was away) and made it the last location.
        const date = Date.parse('2026-01-02T08:00:00.000Z');
        database.rows.push({ type: 'Location', created_at: new Date(date).toJSON(), location: LOCATION_X, time: 0 });
        mocks.stores.location.lastLocation = { ...emptyLocation(), location: LOCATION_X, date };
        mocks.stores.user.currentUser.$locationTag = LOCATION_X;

        await tick(true);

        expect(database.updateGamelogLocationTimeToDatabase).toHaveBeenCalledTimes(1);
        expect(database.updateGamelogLocationTimeToDatabase.mock.calls[0][0].created_at).toBe(new Date(date).toJSON());
        expect(database.getGamelogDatabase).not.toHaveBeenCalled();
        expect(mocks.stores.location.lastLocation.location).toBe('');
    });

    test('leaves the rebuild to the friends-list watcher until the friends list loaded', async () => {
        mocks.watchState.isFriendsLoaded = false;
        await tick(true);
        expect(mocks.stores.game.isGameRunning).toBe(true);
        expect(database.getGamelogDatabase).not.toHaveBeenCalled();
    });

    test("the friends-list watcher's reload also skips rows that are not the API's instance", async () => {
        mocks.stores.game.isGameRunning = true;
        mocks.stores.user.currentUser.$locationTag = 'traveling';
        await tryLoadPlayerList();
        expect(mocks.stores.location.lastLocation.location).toBe('');
    });

    test('desktop keeps the upstream reload: the rows win, whatever the API reports', async () => {
        mocks.android = false;
        mocks.stores.game.isGameRunning = true;
        mocks.stores.user.currentUser.$locationTag = 'offline';
        await tryLoadPlayerList();
        expect(mocks.stores.location.lastLocation.location).toBe(LOCATION_A);
        expect(playerIds()).toEqual([ME, P2, P1]);
    });
});

describe('PC companion disconnects (Android)', () => {
    test('while native holds the state (the 120 s grace), ticks leave the list alone', async () => {
        await tick(true);
        const before = mocks.stores.location.lastLocation;
        database.getGamelogDatabase.mockClear();

        // The connection dropped: native keeps reporting VRChat running and queues no lines.
        await tick(true);
        await tick(true);

        expect(mocks.stores.location.lastLocation).toBe(before);
        expect(playerIds()).toEqual([ME, P2, P1]);
        expect(database.addGamelogJoinLeaveBulk).toHaveBeenCalledTimes(1);
        expect(database.getGamelogDatabase).not.toHaveBeenCalled();
    });

    test('after the grace expired, a reconnect restores the instance but not the players who stayed', async () => {
        await tick(true);
        const createdAt = '2026-01-01T10:00:00.000Z';

        // Native reports a stop when the grace ends: the reset writes a leave row for everyone and closes A.
        await tick(false);
        expect(mocks.stores.game.isGameRunning).toBe(false);
        expect(
            database.addGamelogJoinLeaveBulk.mock.calls
                .flat(2)
                .map((row) => row.userId)
                .sort()
        ).toEqual([ME, P1, P2].sort());
        expect(rowsOfType('Location')[0].time).toBeGreaterThan(0);

        // The companion reconnects while VRChat is still in A: a start, then the lines it missed (P3 joined, P1 left).
        await tick(true);
        expect(mocks.stores.location.lastLocation.location).toBe(LOCATION_A);
        expect(mocks.stores.location.lastLocation.date).toBe(Date.parse(createdAt));
        // Known loss: the stop wrote leave rows for ME and P2, who stayed, so the rows cannot list them.
        expect(playerIds()).toEqual([]);

        await tick(true, [
            joinedLine('2026-01-01T10:20:00.000Z', P3, 'Three'),
            leftLine('2026-01-01T10:21:00.000Z', P1, 'One')
        ]);
        expect(playerIds()).toEqual([P3]);
        expect(rowsOfType('OnPlayerJoined').at(-1)).toMatchObject({ userId: P3, location: LOCATION_A });

        // Moving on closes A with the whole stay, and P3 leaves with it.
        database.updateGamelogLocationTimeToDatabase.mockClear();
        await tick(true, [locationLine('2026-01-01T11:00:00.000Z', LOCATION_B)]);
        expect(database.updateGamelogLocationTimeToDatabase).toHaveBeenCalledWith({
            time: Date.parse('2026-01-01T11:00:00.000Z') - Date.parse(createdAt),
            created_at: createdAt
        });
        expect(rowsOfType('OnPlayerLeft').at(-1)).toMatchObject({ userId: P3, location: LOCATION_A });
        expect(mocks.stores.location.lastLocation.location).toBe(LOCATION_B);
    });
});

describe('log lines while the rows are read (Android)', () => {
    beforeEach(() => {
        AppApi.IsGameRunning.mockResolvedValue(true);
    });

    test('a join logged meanwhile is kept, and nobody is counted twice', async () => {
        const release = deferRows();
        const flow = runAndroidGameLogFlow();
        await flush();

        // Live lines on the post-start (empty) lastLocation: a new player, and one the rows already hold.
        addGameLogEntry(joinEntry(P3, 'Three'), '');
        addGameLogEntry(joinEntry(P2, 'Two'), '');
        release();
        await flow;

        expect(mocks.stores.location.lastLocation.location).toBe(LOCATION_A);
        expect(playerIds().sort()).toEqual([ME, P1, P2, P3].sort());
    });

    test('a location change logged meanwhile wins over the rows', async () => {
        const release = deferRows();
        const flow = runAndroidGameLogFlow();
        await flush();

        addGameLogEntry({ type: 'location', dt: '2026-01-01T11:00:00.000Z', location: LOCATION_B, worldName: 'B' }, '');
        addGameLogEntry(joinEntry(P3, 'Three'), LOCATION_B);
        release();
        await flow;

        expect(mocks.stores.location.lastLocation.location).toBe(LOCATION_B);
        expect(playerIds()).toEqual([P3]);
    });

    test('a newer live location whose row is not written yet wins over the rows', async () => {
        mocks.stores.game.isGameRunning = true;
        mocks.stores.location.setLastLocation({ ...emptyLocation(), location: LOCATION_B });
        await tryLoadPlayerList();
        expect(mocks.stores.location.lastLocation.location).toBe(LOCATION_B);
    });

    test('a stop meanwhile cancels the rebuild', async () => {
        const release = deferRows();
        const flow = runAndroidGameLogFlow();
        await flush();
        mocks.stores.game.isGameRunning = false;
        release();
        await flow;
        expect(mocks.stores.location.lastLocation.location).toBe('');
    });

    test('desktop keeps the upstream reload: the rows win', async () => {
        mocks.android = false;
        mocks.stores.game.isGameRunning = true;
        const release = deferRows();
        const reload = tryLoadPlayerList();
        await flush();
        addGameLogEntry({ type: 'location', dt: '2026-01-01T11:00:00.000Z', location: LOCATION_B, worldName: 'B' }, '');
        release();
        await reload;
        expect(mocks.stores.location.lastLocation.location).toBe(LOCATION_A);
    });
});

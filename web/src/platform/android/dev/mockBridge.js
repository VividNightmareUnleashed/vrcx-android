// Dev-only fake Android host for the phone UI preview harness (README.md in this folder).
//
// Installs what the Android shim would provide before any app module runs: the platform globals,
// window.interopApi (AppApiElectron, WebApi, SQLite, VRCXStorage, LogWatcher, AndroidHost, ...), window.electron and
// window.__vrcxAndroid, plus the inset CSS variables. The fake WebApi answers the calls the app makes at start-up from
// fixtures.json, so the app auto-logs in as a made-up user. Nothing is sent to VRChat: WebApi never touches the network,
// the pipeline WebSocket is replaced by a silent stand-in, and every image is a locally generated SVG.
import fixtures from './fixtures.json';

const MINUTE = 60 * 1000;

/**
 * @param {string} color
 * @param {string} [label]
 * @returns {string} Data: URL of a flat placeholder image
 */
function placeholderImage(color, label = '') {
    const svg =
        `<svg xmlns="http://www.w3.org/2000/svg" width="256" height="192" viewBox="0 0 256 192">` +
        `<rect width="256" height="192" fill="${color}"/>` +
        `<text x="128" y="118" font-family="sans-serif" font-size="72" font-weight="600" fill="rgba(255,255,255,0.85)" text-anchor="middle">${label}</text>` +
        `</svg>`;
    return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

function isoMinutesAgo(minutes) {
    return new Date(Date.now() - minutes * MINUTE).toISOString();
}

function splitLocation(location) {
    if (!location || !location.startsWith('wrld_')) {
        return { worldId: location || 'offline', instanceId: '' };
    }
    const [worldId, ...rest] = location.split(':');
    return { worldId, instanceId: rest.join(':') };
}

function buildUser(source, index) {
    const image = placeholderImage(source.image, source.displayName.slice(0, 1));
    const location = source.location ?? 'offline';
    const { worldId, instanceId } = splitLocation(location);
    const state = source.state ?? (location === 'offline' ? 'offline' : 'online');
    return {
        id: source.id,
        displayName: source.displayName,
        username: source.username ?? source.displayName.toLowerCase(),
        bio: source.bio ?? '',
        bioLinks: [],
        pronouns: source.pronouns ?? '',
        currentAvatarImageUrl: image,
        currentAvatarThumbnailImageUrl: image,
        currentAvatarTags: [],
        profilePicOverride: '',
        profilePicOverrideThumbnail: '',
        userIcon: '',
        imageUrl: image,
        status: source.status ?? 'offline',
        statusDescription: source.statusDescription ?? '',
        state,
        location,
        worldId,
        instanceId,
        travelingToLocation: '',
        tags: source.tags ?? [],
        developerType: 'none',
        last_platform: source.last_platform ?? 'standalonewindows',
        platform: source.platform ?? source.last_platform ?? 'standalonewindows',
        last_login: isoMinutesAgo(30 + index * 7),
        last_activity: isoMinutesAgo(5 + index * 3),
        last_mobile: null,
        date_joined: '2021-03-14',
        isFriend: true,
        friendKey: `mock-${index}`,
        badges: [],
        allowAvatarCopying: false,
        ageVerificationStatus: 'hidden',
        ageVerified: false
    };
}

function buildWorld(source) {
    const image = placeholderImage(source.image, source.name.slice(0, 1));
    return {
        id: source.id,
        name: source.name,
        description: source.description ?? '',
        authorId: source.authorId,
        authorName: source.authorName,
        capacity: source.capacity ?? 32,
        recommendedCapacity: Math.round((source.capacity ?? 32) / 2),
        imageUrl: image,
        thumbnailImageUrl: image,
        releaseStatus: 'public',
        tags: ['system_approved'],
        favorites: 1234,
        visits: 56789,
        popularity: 7,
        heat: 3,
        occupants: 12,
        publicOccupants: 8,
        privateOccupants: 4,
        created_at: '2022-05-01T12:00:00.000Z',
        updated_at: '2025-01-01T12:00:00.000Z',
        publicationDate: '2022-05-02T12:00:00.000Z',
        labsPublicationDate: 'none',
        version: 3,
        unityPackages: [],
        instances: []
    };
}

function createFixtureData() {
    const friends = fixtures.friends.map((friend, index) => buildUser(friend, index));
    const worlds = fixtures.worlds.map((world) => buildWorld(world));
    const me = buildUser(fixtures.currentUser, 99);
    const online = friends.filter((f) => f.location !== 'offline').map((f) => f.id);
    const active = friends.filter((f) => f.location === 'offline' && f.state === 'active').map((f) => f.id);
    const offline = friends.filter((f) => f.state === 'offline').map((f) => f.id);
    const { worldId, instanceId } = splitLocation(me.location);
    const currentUser = {
        ...me,
        isFriend: false,
        friendKey: '',
        friends: friends.map((f) => f.id),
        onlineFriends: online,
        activeFriends: active,
        offlineFriends: offline,
        friendGroupNames: [],
        pastDisplayNames: [],
        homeLocation: worlds[0].id,
        hasLoggedInFromClient: true,
        hasBirthday: true,
        emailVerified: true,
        hasEmail: true,
        twoFactorAuthEnabled: true,
        isBoopingEnabled: true,
        acceptedTOSVersion: 10,
        obfuscatedEmail: 'p****@example.invalid',
        presence: {
            avatarThumbnail: me.currentAvatarThumbnailImageUrl,
            displayName: me.displayName,
            groups: [],
            id: me.id,
            instance: instanceId,
            instanceType: 'public',
            isRejoining: '0',
            platform: 'standalonewindows',
            profilePicOverride: '',
            status: me.status,
            travelingToInstance: '',
            travelingToWorld: '',
            world: worldId
        }
    };
    return { friends, worlds, currentUser };
}

function feedRows(data) {
    // Row shape of database.lookupFeedDatabase (services/database/feed.js): 22 positional columns.
    return fixtures.feed.map((entry, index) => {
        const user = data.friends[entry.userIndex] ?? data.friends[0];
        const world = data.worlds[entry.worldIndex] ?? null;
        const previousWorld = data.worlds[entry.previousWorldIndex] ?? null;
        const row = new Array(22).fill(null);
        row[0] = 1000 - index;
        row[1] = isoMinutesAgo(entry.minutesAgo);
        row[2] = user.id;
        row[3] = user.displayName;
        row[4] = entry.type;
        switch (entry.type) {
            case 'GPS':
                row[5] = entry.location;
                row[6] = world?.name ?? '';
                row[7] = previousWorld ? `${previousWorld.id}:11111~region(eu)` : '';
                row[8] = entry.time ?? 0;
                row[9] = '';
                break;
            case 'Online':
            case 'Offline':
                row[5] = entry.location;
                row[6] = world?.name ?? '';
                row[8] = entry.time ?? 0;
                row[9] = '';
                break;
            case 'Status':
                row[10] = entry.status;
                row[11] = entry.statusDescription;
                row[12] = entry.previousStatus;
                row[13] = entry.previousStatusDescription;
                break;
            case 'Bio':
                row[14] = entry.bio;
                row[15] = entry.previousBio;
                break;
            case 'Avatar':
                row[16] = user.id;
                row[17] = entry.avatarName;
                row[18] = placeholderImage(entry.image, entry.avatarName.slice(0, 1));
                row[19] = row[18];
                row[20] = placeholderImage(entry.previousImage, '?');
                row[21] = row[20];
                break;
        }
        return row;
    });
}

function readArgs(args) {
    if (!args) return new Map();
    if (args instanceof Map) return args;
    return new Map(Object.entries(args));
}

function createSqlite(configs, data) {
    const feed = feedRows(data);
    return {
        ExecuteJson(sql, rawArgs) {
            const args = readArgs(rawArgs);
            if (/^SELECT value FROM configs WHERE key = @key/i.test(sql)) {
                const key = args.get('@key');
                return JSON.stringify(configs.has(key) ? [[configs.get(key)]] : []);
            }
            if (/_feed_gps/.test(sql) && /UNION ALL/.test(sql) && !/LIKE/i.test(sql)) {
                const wanted = [
                    ['GPS', '_feed_gps'],
                    ['Status', '_feed_status'],
                    ['Bio', '_feed_bio'],
                    ['Avatar', '_feed_avatar']
                ]
                    .filter(([, table]) => sql.includes(table))
                    .map(([type]) => type);
                if (sql.includes('_feed_online_offline')) {
                    if (!sql.includes("type = 'Offline'")) wanted.push('Online');
                    if (!sql.includes("type = 'Online'")) wanted.push('Offline');
                }
                return JSON.stringify(feed.filter((row) => wanted.includes(row[4])));
            }
            return '[]';
        },
        Execute(sql, args) {
            return JSON.parse(this.ExecuteJson(sql, args));
        },
        ExecuteNonQuery(sql, rawArgs) {
            const args = readArgs(rawArgs);
            if (/^INSERT OR REPLACE INTO configs/i.test(sql)) {
                configs.set(args.get('@key'), args.get('@value'));
                return 1;
            }
            if (/^DELETE FROM configs WHERE key = @key/i.test(sql)) {
                return configs.delete(args.get('@key')) ? 1 : 0;
            }
            return 0;
        }
    };
}

function jsonResponse(status, body) {
    return JSON.stringify({ status, message: typeof body === 'string' ? body : JSON.stringify(body) });
}

function createWebApi(data) {
    const worldsById = new Map(data.worlds.map((world) => [world.id, world]));
    const usersById = new Map([...data.friends, data.currentUser].map((user) => [user.id, user]));

    const apiConfig = {
        clientApiKey: 'preview',
        address: 'preview',
        announcements: [],
        downloadUrls: {},
        dynamicWorldRows: [],
        events: {},
        sdkUnityVersion: '2022.3.22f1',
        serverName: 'preview',
        timeOutWorldId: data.worlds[0].id,
        homeWorldId: data.worlds[0].id,
        tutorialWorldId: data.worlds[0].id,
        whiteListedAssetUrls: [],
        constants: {
            LANGUAGE: {
                SPOKEN_LANGUAGE_OPTIONS: { eng: 'English', jpn: 'Japanese', kor: 'Korean', deu: 'German' }
            }
        }
    };

    function handleApi(path, query, method) {
        if (path === 'config') return apiConfig;
        if (path === 'auth') return { ok: true, token: 'preview-token' };
        if (path === 'auth/user' || path === 'auth/user/') return data.currentUser;
        if (path === 'auth/user/friends') {
            const offline = query.get('offline') === 'true';
            const offset = Number(query.get('offset') ?? 0);
            const n = Number(query.get('n') ?? 50);
            const list = data.friends.filter((friend) =>
                offline ? friend.location === 'offline' && friend.state === 'offline' : friend.state !== 'offline'
            );
            return list.slice(offset, offset + n);
        }
        if (path === 'auth/user/favoritelimits') {
            return {
                defaultMaxFavoriteGroups: 4,
                defaultMaxFavoritesPerGroup: 150,
                maxFavoriteGroups: { avatar: 6, friend: 3, world: 4 },
                maxFavoritesPerGroup: { avatar: 50, friend: 150, world: 100 }
            };
        }
        if (/^users\/usr_[^/]+\/instances\/groups/.test(path)) {
            return { instances: [], fetchedAt: new Date().toISOString() };
        }
        let match = path.match(/^users\/(usr_[^/]+)$/);
        if (match) return usersById.get(match[1]) ?? null;
        match = path.match(/^worlds\/(wrld_[^/]+)$/);
        if (match) return worldsById.get(match[1]) ?? null;
        match = path.match(/^instances\/(wrld_[^:]+):(.+)$/);
        if (match) {
            const world = worldsById.get(match[1]);
            if (!world) return null;
            const location = `${match[1]}:${decodeURIComponent(match[2])}`;
            const users = data.friends.filter((friend) => friend.location === location);
            return {
                id: location,
                location,
                instanceId: decodeURIComponent(match[2]),
                name: decodeURIComponent(match[2]).split('~')[0],
                worldId: world.id,
                world,
                type: 'public',
                region: 'eu',
                capacity: world.capacity,
                n_users: users.length + 3,
                userCount: users.length + 3,
                platforms: { standalonewindows: users.length + 2, android: 1, ios: 0 },
                active: true,
                full: false,
                canRequestInvite: true,
                queueEnabled: false,
                queueSize: 0,
                closedAt: null
            };
        }
        if (method !== 'GET') return {};
        return [];
    }

    return {
        ExecuteJson(requestJson) {
            let options = {};
            try {
                options = JSON.parse(requestJson);
            } catch {
                return jsonResponse(400, 'bad request');
            }
            let url;
            try {
                url = new URL(options.url);
            } catch {
                return jsonResponse(400, 'bad url');
            }
            const method = (options.method ?? 'GET').toUpperCase();
            if (url.hostname.endsWith('vrchat.cloud') && url.pathname.startsWith('/api/1/')) {
                const path = url.pathname.slice('/api/1/'.length).replace(/\/$/, '');
                const body = handleApi(path, url.searchParams, method);
                if (body === null) {
                    return jsonResponse(404, { error: { message: 'Not found (preview)', status_code: 404 } });
                }
                return jsonResponse(200, body);
            }
            if (url.hostname === 'status.vrchat.com') {
                return jsonResponse(200, {
                    page: { updated_at: new Date().toISOString() },
                    status: { indicator: 'none', description: 'All Systems Operational' },
                    incidents: [],
                    components: []
                });
            }
            // Anything else (updater, error reporting, avatar providers) is unavailable in the preview.
            return jsonResponse(404, 'not available in the preview harness');
        },
        ClearCookies() {},
        GetCookies() {
            return '';
        },
        SetCookies() {}
    };
}

const companionStates = {
    none: { status: 'unpaired', paired: [], vrchatRunning: false, steamVrRunning: false },
    paired: { status: 'idle', vrchatRunning: false, steamVrRunning: false },
    connected: { status: 'connected', vrchatRunning: false, steamVrRunning: false },
    playing: { status: 'connected', vrchatRunning: true, steamVrRunning: false }
};

/**
 * @param {string} mode None | paired | connected | playing
 * @returns {object} AndroidHost companion state object (docs/ARCHITECTURE.md §5.1)
 */
export function mockCompanionState(mode) {
    const base = companionStates[mode] ?? companionStates.playing;
    const pc = {
        id: 'pc-preview',
        name: 'DESKTOP-PREVIEW',
        hosts: ['192.168.1.20'],
        port: 47631,
        fp: 'AA:BB',
        pairedAt: 0,
        lastSeen: 0
    };
    return {
        activeId: base.status === 'unpaired' ? null : pc.id,
        paired: base.status === 'unpaired' ? [] : [pc],
        machineName: base.status === 'unpaired' ? null : pc.name,
        tz: null,
        syncing: false,
        lastError: null,
        ...base
    };
}

function createAndroidHost(companionMode) {
    return {
        CompanionGetState: () => mockCompanionState(companionMode),
        GetBackgroundMode: () => true,
        SetBackgroundMode: (value) => Boolean(value),
        IsIgnoringBatteryOptimizations: () => false,
        GetNotificationPermission: () => 'granted',
        RequestNotificationPermission: () => 'granted',
        CanLaunchVRChat: () => false,
        CopyText: () => true,
        ReadClipboardText: () => '',
        TtsGetVoices: () => [],
        GetDeviceInfo: () => ({
            model: 'Preview Phone',
            sdkInt: 35,
            webViewVersion: navigator.userAgent,
            appVersion: 'preview',
            vrcxVersion: `VRCX ${VERSION}`
        })
    };
}

function createAppApi(companionMode) {
    // On Android the game flags come from the PC companion (docs/ARCHITECTURE.md §8).
    const companion = mockCompanionState(companionMode);
    const values = {
        GetVersion: () => `VRCX ${VERSION}`,
        CurrentCulture: () => 'en-US',
        CurrentLanguage: () => 'en',
        GetLaunchCommand: () => '',
        CustomCss: () => '',
        CustomScript: () => '',
        GetZoom: () => 1,
        IsGameRunning: () => companion.vrchatRunning,
        IsSteamVRRunning: () => companion.steamVrRunning,
        VrcClosedGracefully: () => true,
        HasVRChatRegistryFolder: () => true,
        GetClipboard: () => '',
        GetColourBulk: (ids) => (Array.isArray(ids) ? ids.map((id, i) => [id, (i * 7919) % 65535]) : []),
        GetColourFromUserID: () => 12345,
        StartGame: () => false,
        TryOpenInstanceInVrc: () => false,
        GetVRChatRegistryKey: () => null,
        GetVRChatRegistryKeyString: () => null,
        GetVRChatRegistry: () => null,
        SetVRChatRegistryKey: () => false,
        ReadConfigFileSafe: () => '',
        GetVRChatModerations: () => null,
        GetVRChatUserModeration: () => 0,
        CheckForUpdateExe: () => false,
        CheckUpdateProgress: () => 0
    };
    return new Proxy(values, {
        get(target, prop) {
            return target[prop] ?? (() => null);
        }
    });
}

function createStorage() {
    const items = new Map();
    return {
        Get: (key) => items.get(key) ?? '',
        Set: (key, value) => {
            items.set(key, String(value));
        },
        Remove: (key) => items.delete(key),
        GetAll: () => JSON.stringify(Object.fromEntries(items)),
        Flush() {},
        Save() {},
        Load() {}
    };
}

function installFakeWebSocket() {
    const NativeWebSocket = window.WebSocket;
    class PreviewPipelineSocket extends EventTarget {
        static CONNECTING = 0;
        static OPEN = 1;
        static CLOSING = 2;
        static CLOSED = 3;
        constructor(url) {
            super();
            this.url = String(url);
            this.readyState = 0;
            this.protocol = '';
            this.extensions = '';
            this.binaryType = 'blob';
            this.bufferedAmount = 0;
            this.onopen = null;
            this.onclose = null;
            this.onmessage = null;
            this.onerror = null;
            setTimeout(() => {
                if (this.readyState !== 0) return;
                this.readyState = 1;
                const event = new Event('open');
                this.onopen?.(event);
                this.dispatchEvent(event);
            }, 50);
        }
        send() {}
        close(code = 1000, reason = '') {
            if (this.readyState >= 2) return;
            this.readyState = 3;
            const event = new CloseEvent('close', { code, reason, wasClean: true });
            this.onclose?.(event);
            this.dispatchEvent(event);
        }
    }
    window.WebSocket = new Proxy(NativeWebSocket, {
        construct(target, args) {
            const url = String(args[0] ?? '');
            if (/vrchat\.(cloud|com)/.test(url)) {
                return new PreviewPipelineSocket(url);
            }
            return new target(...args);
        }
    });
}

function applyInsets(insets) {
    const root = document.documentElement;
    root.style.setProperty('--safe-top', `${insets.top}px`);
    root.style.setProperty('--safe-right', `${insets.right}px`);
    root.style.setProperty('--safe-bottom', `${insets.bottom}px`);
    root.style.setProperty('--safe-left', `${insets.left}px`);
    root.style.setProperty('--ime-bottom', `${insets.ime ?? 0}px`);
}

function parseInsets(value) {
    const [top = 24, right = 0, bottom = 16, left = 0] = String(value ?? '')
        .split(',')
        .filter((part) => part !== '')
        .map(Number);
    return { top, right, bottom, left, ime: 0 };
}

/**
 * @param {object} options
 * @param {URLSearchParams} options.params Page query: theme, companion, insets, onboarding
 * @returns {{ data: object; companionMode: string }}
 */
export function installMockBridge({ params }) {
    const data = createFixtureData();
    const companionMode = params.get('companion') ?? 'playing';

    // Platform globals (docs/ARCHITECTURE.md §4.1).
    window.WINDOWS = false;
    window.LINUX = true;
    window.ANDROID = true;
    document.documentElement.classList.add('is-android');

    const configs = new Map([
        ['config:lastuserloggedin', data.currentUser.id],
        [
            'config:savedcredentials',
            JSON.stringify({
                [data.currentUser.id]: {
                    user: data.currentUser,
                    loginParams: { username: data.currentUser.username, endpoint: '', websocket: '' }
                }
            })
        ],
        ['config:vrcx_databaseversion', '17'],
        ['config:vrcx_lastvrcxversion', `VRCX ${VERSION}`],
        ['config:vrcx_onboarding_welcome_seen', params.get('onboarding') === '1' ? 'false' : 'true'],
        ['config:vrcx_thememode', params.get('theme') ?? 'system']
    ]);
    if (params.get('color')) {
        configs.set('config:vrcx_themecolor', params.get('color'));
    }

    const classes = {
        AppApiElectron: createAppApi(companionMode),
        WebApi: createWebApi(data),
        SQLite: createSqlite(configs, data),
        VRCXStorage: createStorage(),
        LogWatcher: { Get: () => [], GetLogLines: () => [], SetDateTill() {}, Reset() {} },
        Discord: { SetAssets() {}, SetActive: () => false },
        AssetBundleManager: {
            CheckVRChatCache: () => ({ Item1: -1, Item2: false, Item3: '' }),
            GetCacheSize: () => 0
        },
        AndroidHost: createAndroidHost(companionMode)
    };

    window.interopApi = {
        async callDotNetMethod(className, methodName, args) {
            const target = classes[className];
            const method = target?.[methodName];
            if (typeof method !== 'function') {
                if (!target) {
                    throw new Error(
                        `MissingMethodException: Method ${methodName} does not exist on class ${className}`
                    );
                }
                return null;
            }
            return method.apply(target, Array.isArray(args) ? args : []);
        }
    };

    const launchListeners = new Set();
    window.electron = {
        getArch: async () => 'arm64',
        getClipboardText: async () => '',
        getNoUpdater: async () => true,
        setTrayIconNotification: async () => {},
        openFileDialog: async () => '',
        openDirectoryDialog: async () => null,
        onWindowPositionChanged: () => () => {},
        onWindowSizeChanged: () => () => {},
        onWindowStateChange: () => () => {},
        onBrowserFocus: () => () => {},
        desktopNotification: async (title, body) => console.info('[preview] notification', title, body),
        restartApp: async () => location.reload(),
        getOverlayWindow: async () => false,
        updateVr: async () => {},
        ipcRenderer: {
            on(channel, fn) {
                if (channel !== 'launch-command') return undefined;
                launchListeners.add(fn);
                return () => launchListeners.delete(fn);
            }
        }
    };

    // Native → JS entry points (the shim's part of window.__vrcxAndroid).
    const handlers = new Map();
    const insets = parseInsets(params.get('insets'));
    const api = {
        on(name, fn) {
            if (!handlers.has(name)) handlers.set(name, new Set());
            handlers.get(name).add(fn);
            return () => handlers.get(name)?.delete(fn);
        },
        emit(name, detail) {
            handlers.get(name)?.forEach((fn) => fn(detail));
            window.dispatchEvent(new CustomEvent(`vrcx-android:${name}`, { detail }));
        },
        setInsets(next) {
            Object.assign(insets, next);
            applyInsets(insets);
            api.emit('insets', { ...insets });
        },
        backHandler: null,
        handleBack() {
            try {
                return api.backHandler?.() === true;
            } catch (error) {
                console.error(error);
                return false;
            }
        }
    };
    window.__vrcxAndroid = api;
    applyInsets(insets);

    installFakeWebSocket();

    // Developer controls: F8 = Android back, F9 = toggle a 300px keyboard.
    window.__vrcxDev = {
        back() {
            const handled = api.handleBack();
            console.info(`[preview] back → ${handled ? 'handled' : 'moveTaskToBack'}`);
            return handled;
        },
        setIme(px) {
            api.setInsets({ ime: px });
        },
        companionMode
    };
    window.addEventListener('keydown', (event) => {
        if (event.key === 'F8') {
            event.preventDefault();
            window.__vrcxDev.back();
        } else if (event.key === 'F9') {
            event.preventDefault();
            window.__vrcxDev.setIme(insets.ime ? 0 : 300);
        }
    });

    return { data, companionMode };
}

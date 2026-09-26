// Preview harness data for route views group A: game log sessions
// and table rows, the current instance's player list, notifications of several types, friend log and moderation
// entries, search results and a dashboard. Dev only; see ../README.md for the hooks.
//
// Every id is made up (usr_00000000-..., wrld_00000000-..., grp_00000000-...) and every image is a local SVG.

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const NOW = Date.now();

const ME = 'usr_00000000-0000-4000-8000-000000000001';
const FRIEND = {
    aurora: 'usr_00000000-0000-4000-8000-000000000101',
    birch: 'usr_00000000-0000-4000-8000-000000000102',
    cobalt: 'usr_00000000-0000-4000-8000-000000000103',
    dune: 'usr_00000000-0000-4000-8000-000000000104',
    ember: 'usr_00000000-0000-4000-8000-000000000105',
    gale: 'usr_00000000-0000-4000-8000-000000000107'
};
const FRIEND_NAMES = {
    [FRIEND.aurora]: 'Aurora',
    [FRIEND.birch]: 'Birch',
    [FRIEND.cobalt]: 'Cobalt',
    [FRIEND.dune]: 'Dune',
    [FRIEND.ember]: 'Ember',
    [FRIEND.gale]: 'Gale',
    [ME]: 'Preview User'
};

// Players who are not friends (seen in instances, search results and moderations).
const STRANGERS = [
    {
        id: 'usr_00000000-0000-4000-8000-000000000301',
        displayName: 'Kestrel',
        image: '#c9733b',
        tags: ['system_trust_basic', 'system_trust_known', 'system_trust_trusted', 'language_eng', 'language_deu'],
        platform: 'standalonewindows',
        status: 'active',
        statusDescription: 'Exploring worlds',
        bio: 'Photographer of small places.',
        bioLinks: ['https://example.org/kestrel']
    },
    {
        id: 'usr_00000000-0000-4000-8000-000000000302',
        displayName: 'Lumen',
        image: '#d4b83a',
        tags: ['system_trust_basic', 'system_trust_known', 'language_jpn'],
        platform: 'android',
        status: 'join me',
        statusDescription: 'Say hi!',
        bio: 'Quest player, night owl.',
        bioLinks: []
    },
    {
        id: 'usr_00000000-0000-4000-8000-000000000303',
        displayName: 'Maple',
        image: '#a0522d',
        tags: ['system_trust_basic'],
        platform: 'standalonewindows',
        status: 'busy',
        statusDescription: 'Recording',
        bio: '',
        bioLinks: []
    },
    {
        id: 'usr_00000000-0000-4000-8000-000000000304',
        displayName: 'Nimbus',
        image: '#5f9ea0',
        tags: [
            'system_trust_basic',
            'system_trust_known',
            'system_trust_trusted',
            'system_trust_veteran',
            'language_eng',
            'language_kor'
        ],
        platform: 'standalonewindows',
        status: 'active',
        statusDescription: 'DJ set at 22:00',
        bio: 'Music, lights and too many cables.',
        bioLinks: ['https://example.org/nimbus', 'https://example.net/mixes']
    },
    {
        id: 'usr_00000000-0000-4000-8000-000000000305',
        displayName: 'Orchid',
        image: '#9b59b6',
        tags: [],
        platform: 'ios',
        status: 'ask me',
        statusDescription: '',
        bio: '',
        bioLinks: []
    },
    {
        id: 'usr_00000000-0000-4000-8000-000000000306',
        displayName: 'Pixel',
        image: '#2e86de',
        tags: ['system_trust_basic', 'system_trust_known', 'system_trust_trusted', 'language_eng'],
        platform: 'android',
        status: 'active',
        statusDescription: 'Building a tiny world',
        bio: 'Low-poly everything.',
        bioLinks: []
    }
];
const STRANGER = Object.fromEntries(STRANGERS.map((user) => [user.displayName.toLowerCase(), user.id]));
const NAMES = { ...FRIEND_NAMES, ...Object.fromEntries(STRANGERS.map((user) => [user.id, user.displayName])) };

const WORLD = {
    harbor: 'wrld_00000000-0000-4000-8000-00000000a001',
    library: 'wrld_00000000-0000-4000-8000-00000000a002',
    rooftops: 'wrld_00000000-0000-4000-8000-00000000a003',
    observatory: 'wrld_00000000-0000-4000-8000-00000000a004'
};
const WORLD_NAMES = {
    [WORLD.harbor]: 'Lantern Harbor',
    [WORLD.library]: 'Moss Library',
    [WORLD.rooftops]: 'Neon Rooftops',
    [WORLD.observatory]: 'Glass Observatory'
};

const EXTRA_WORLDS = [
    { id: 'wrld_00000000-0000-4000-8000-00000000a005', name: 'Paper Lantern Festival', image: '#d35400' },
    { id: 'wrld_00000000-0000-4000-8000-00000000a006', name: 'Quiet Tram Line', image: '#16a085' },
    { id: 'wrld_00000000-0000-4000-8000-00000000a007', name: 'Rainy Arcade With A Very Long Name', image: '#8e44ad' },
    { id: 'wrld_00000000-0000-4000-8000-00000000a008', name: 'Snowfield Cabin', image: '#34495e' }
];

const GROUPS = [
    {
        id: 'grp_00000000-0000-4000-8000-000000000001',
        name: 'Preview Makers',
        shortCode: 'PMAKE',
        discriminator: '0001',
        description: 'People who build worlds and argue about lighting.',
        memberCount: 1240,
        image: '#1abc9c'
    },
    {
        id: 'grp_00000000-0000-4000-8000-000000000002',
        name: 'Night Tram Club',
        shortCode: 'TRAM',
        discriminator: '0420',
        description: 'Weekly rides through quiet worlds.',
        memberCount: 86,
        image: '#e67e22'
    },
    {
        id: 'grp_00000000-0000-4000-8000-000000000003',
        name: 'Harbor Watch',
        shortCode: 'HRBR',
        discriminator: '1234',
        description: '',
        memberCount: 12,
        image: '#3498db'
    }
];

const CURRENT_LOCATION = `${WORLD.harbor}:12345~region(eu)`;
const LOCATIONS = [
    { at: NOW - 3 * DAY - 5 * HOUR, location: `${WORLD.observatory}:13579~region(eu)`, duration: 50 * MINUTE },
    {
        at: NOW - 2 * DAY - 2 * HOUR,
        location: `${WORLD.library}:67890~hidden(${FRIEND.birch})~region(us)`,
        duration: 80 * MINUTE
    },
    {
        at: NOW - 3 * HOUR,
        location: `${WORLD.rooftops}:24680~friends(${FRIEND.dune})~region(jp)`,
        duration: 130 * MINUTE
    },
    { at: NOW - 45 * MINUTE, location: CURRENT_LOCATION, duration: 0 }
];

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

const iso = (ms) => new Date(ms).toISOString();
const worldIdOf = (location) => String(location).split(':')[0];

// ---------------------------------------------------------------- game log

function buildGameLog() {
    const rows = [];
    let id = 1;
    const add = (row) => rows.push({ id: id++, ...row });
    const join = (at, userId, location) =>
        add({ table: 'join_leave', at, type: 'OnPlayerJoined', userId, location, time: 0 });
    const leave = (at, userId, location, time) =>
        add({ table: 'join_leave', at, type: 'OnPlayerLeft', userId, location, time });

    const [observatory, library, rooftops, harbor] = LOCATIONS;
    for (const segment of LOCATIONS) {
        add({
            table: 'location',
            at: segment.at,
            type: 'Location',
            location: segment.location,
            time: segment.duration
        });
        join(segment.at + 4000, ME, segment.location);
    }

    // Three days ago: a short visit.
    join(observatory.at + 2 * MINUTE, FRIEND.gale, observatory.location);
    join(observatory.at + 3 * MINUTE, STRANGER.kestrel, observatory.location);
    add({
        table: 'video_play',
        at: observatory.at + 9 * MINUTE,
        type: 'VideoPlay',
        location: observatory.location,
        userId: FRIEND.gale,
        videoUrl: 'https://www.youtube.com/watch?v=preview0001',
        videoName: 'Night sky timelapse (4K)',
        videoId: 'YouTube'
    });
    leave(observatory.at + 40 * MINUTE, FRIEND.gale, observatory.location, 38 * MINUTE);
    leave(observatory.at + 45 * MINUTE, STRANGER.kestrel, observatory.location, 42 * MINUTE);

    // Two days ago.
    join(library.at + 1 * MINUTE, FRIEND.birch, library.location);
    join(library.at + 6 * MINUTE, STRANGER.lumen, library.location);
    join(library.at + 7 * MINUTE, STRANGER.maple, library.location);
    add({
        table: 'event',
        at: library.at + 20 * MINUTE,
        type: 'Event',
        data: 'Udon: BookShelf_07 moved by Birch; the reading room layout was reset to the default arrangement'
    });
    leave(library.at + 50 * MINUTE, STRANGER.lumen, library.location, 44 * MINUTE);

    // Three hours ago: a busy public-ish instance. The crowd was already there, so it is logged right after the
    // arrival, and leaves at once at the end: the sessions view folds both into "N players joined/left" groups.
    const crowd = [STRANGER.kestrel, STRANGER.lumen, STRANGER.maple, STRANGER.nimbus, STRANGER.orchid, STRANGER.pixel];
    crowd.forEach((userId, index) => join(rooftops.at + 5000 + index * 400, userId, rooftops.location));
    join(rooftops.at + 12 * MINUTE, FRIEND.dune, rooftops.location);
    add({
        table: 'video_play',
        at: rooftops.at + 25 * MINUTE,
        type: 'VideoPlay',
        location: rooftops.location,
        userId: STRANGER.nimbus,
        videoUrl: 'https://www.youtube.com/watch?v=preview0002',
        videoName: 'Synthwave mix for rooftop drives',
        videoId: 'YouTube'
    });
    add({
        table: 'video_play',
        at: rooftops.at + 26 * MINUTE,
        type: 'VideoPlay',
        location: rooftops.location,
        userId: STRANGER.nimbus,
        videoUrl: 'https://www.youtube.com/watch?v=preview0002',
        videoName: 'Synthwave mix for rooftop drives',
        videoId: 'YouTube'
    });
    add({
        table: 'portal_spawn',
        at: rooftops.at + 40 * MINUTE,
        type: 'PortalSpawn',
        location: rooftops.location,
        userId: FRIEND.dune,
        instanceId: `${WORLD.observatory}:13579~region(eu)`,
        worldName: WORLD_NAMES[WORLD.observatory]
    });
    crowd
        .slice(0, 5)
        .forEach((userId, index) =>
            leave(rooftops.at + rooftops.duration - 4000 + index * 500, userId, rooftops.location, 129 * MINUTE)
        );
    leave(rooftops.at + 120 * MINUTE, FRIEND.dune, rooftops.location, 108 * MINUTE);

    // Now: the current instance.
    join(harbor.at + 1 * MINUTE, FRIEND.aurora, harbor.location);
    join(harbor.at + 3 * MINUTE, FRIEND.ember, harbor.location);
    join(harbor.at + 6 * MINUTE, STRANGER.kestrel, harbor.location);
    join(harbor.at + 8 * MINUTE, STRANGER.nimbus, harbor.location);
    join(harbor.at + 10 * MINUTE, STRANGER.orchid, harbor.location);
    add({
        table: 'video_play',
        at: harbor.at + 12 * MINUTE,
        type: 'VideoPlay',
        location: harbor.location,
        userId: FRIEND.aurora,
        videoUrl: 'https://www.youtube.com/watch?v=preview0003',
        videoName: 'Lo-fi harbor ambience: waves, rain and distant bells for studying',
        videoId: 'YouTube'
    });
    add({
        table: 'resource_load',
        at: harbor.at + 13 * MINUTE,
        type: 'StringLoad',
        location: harbor.location,
        resourceUrl: 'https://example.org/lantern-harbor/schedule/events-this-week.json'
    });
    add({
        table: 'resource_load',
        at: harbor.at + 14 * MINUTE,
        type: 'ImageLoad',
        location: harbor.location,
        resourceUrl: 'https://example.org/lantern-harbor/posters/festival-poster.png'
    });
    add({
        table: 'external',
        at: harbor.at + 16 * MINUTE,
        type: 'External',
        location: harbor.location,
        userId: FRIEND.ember,
        message: 'OSC: chatbox message "brb, getting tea" from Ember'
    });
    add({
        table: 'video_play',
        at: harbor.at + 18 * MINUTE,
        type: 'VideoPlay',
        location: harbor.location,
        userId: FRIEND.ember,
        videoUrl: 'https://example.org/stream/harbor-radio',
        videoName: 'Harbor radio (live)',
        videoId: 'LSMedia'
    });
    join(harbor.at + 20 * MINUTE, STRANGER.pixel, harbor.location);
    leave(harbor.at + 30 * MINUTE, STRANGER.orchid, harbor.location, 20 * MINUTE);

    return rows;
}

const GAME_LOG = buildGameLog();

function worldNameOf(row) {
    const source = row.table === 'portal_spawn' ? row.instanceId : row.location;
    return WORLD_NAMES[worldIdOf(source)] ?? '';
}

// Row of the 18 columns the UNION ALL game log queries select (services/database/gameLog.js).
function unionColumns(row) {
    const name = row.userId ? (NAMES[row.userId] ?? '') : null;
    const isLocation = row.table === 'location';
    return [
        row.id,
        iso(row.at),
        row.type,
        isLocation || row.table === 'event' || row.table === 'resource_load' ? null : name,
        row.table === 'event' ? null : row.location,
        row.userId ?? null,
        row.time ?? null,
        isLocation ? worldIdOf(row.location) : null,
        isLocation || row.table === 'portal_spawn' ? worldNameOf(row) : null,
        isLocation ? '' : null,
        row.instanceId ?? null,
        row.videoUrl ?? null,
        row.videoName ?? null,
        row.videoId ?? null,
        row.resourceUrl ?? null,
        row.table === 'resource_load' ? row.type : null,
        row.data ?? null,
        row.message ?? null
    ];
}

function searchText(row) {
    return [NAMES[row.userId], worldNameOf(row), row.data, row.message, row.videoName, row.videoUrl, row.resourceUrl]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
}

function unionRows(sql, args) {
    const tables = new Set([...sql.matchAll(/FROM gamelog_(\w+)/g)].map((match) => match[1]));
    const onlyType = sql.match(/AND type = '(OnPlayerJoined|OnPlayerLeft)'/)?.[1];
    const excludedResources = [...sql.matchAll(/resource_type != '(\w+)'/g)].map((match) => match[1]);
    const vipIds = sql.match(/user_id IN \(([^)]*)\)/)?.[1]?.match(/usr_[0-9a-f-]+/g) ?? null;
    const search = String(args.get('@searchLike') ?? '')
        .replaceAll('%', '')
        .toLowerCase();
    const locationLike = String(args.get('@locationLike') ?? '').replaceAll('%', '');
    const limit = Number(args.get('@limit') ?? 500);
    return GAME_LOG.filter((row) => {
        if (!tables.has(row.table)) return false;
        if (row.table === 'join_leave' && onlyType && row.type !== onlyType) return false;
        if (row.table === 'resource_load' && excludedResources.includes(row.type)) return false;
        if (vipIds && ['join_leave', 'portal_spawn', 'external', 'video_play'].includes(row.table)) {
            if (!vipIds.includes(row.userId)) return false;
        }
        if (search && !searchText(row).includes(search)) return false;
        if (locationLike && !String(row.location ?? '').startsWith(locationLike)) return false;
        return true;
    })
        .sort((a, b) => b.at - a.at || b.id - a.id)
        .slice(0, limit)
        .map(unionColumns);
}

// Per-table rows of `SELECT * FROM gamelog_<table>` (the player list reload in coordinators/gameLogCoordinator.js).
function tableRows(table, since) {
    return GAME_LOG.filter((row) => row.table === table && row.at >= since)
        .sort((a, b) => b.id - a.id)
        .map((row) => {
            const at = iso(row.at);
            const name = NAMES[row.userId] ?? '';
            switch (table) {
                case 'location':
                    return [row.id, at, row.location, worldIdOf(row.location), worldNameOf(row), row.time, ''];
                case 'join_leave':
                    return [row.id, at, row.type, name, row.location, row.userId, row.time];
                case 'portal_spawn':
                    return [row.id, at, name, row.location, row.userId, row.instanceId, worldNameOf(row)];
                case 'video_play':
                    return [row.id, at, row.videoUrl, row.videoName, row.videoId, row.location, name, row.userId];
                case 'resource_load':
                    return [row.id, at, row.resourceUrl, row.type, row.location];
                case 'event':
                    return [row.id, at, row.data];
                case 'external':
                    return [row.id, at, row.message, name, row.userId, row.location];
                default:
                    return null;
            }
        })
        .filter(Boolean);
}

function sessionSegments(sql, args) {
    const beforeId = args.get('@beforeId');
    const since = args.get('@sinceDate');
    const limit = Number(args.get('@limit') ?? 20);
    return GAME_LOG.filter((row) => row.table === 'location')
        .filter((row) => (beforeId != null && /id < @beforeId/.test(sql) ? row.id < Number(beforeId) : true))
        .filter((row) => (since ? row.at >= Date.parse(since) : true))
        .sort((a, b) => b.id - a.id)
        .slice(0, limit)
        .map((row) => [row.id, iso(row.at), row.location, worldIdOf(row.location), worldNameOf(row), row.time, '']);
}

function sessionLocationsFromArgs(args) {
    const locations = new Set();
    for (const [key, value] of args) {
        if (key.startsWith('@loc_')) locations.add(value);
    }
    return locations;
}

function sessionEvents(table, args) {
    const locations = sessionLocationsFromArgs(args);
    const after = Date.parse(args.get('@afterDate') ?? 0) || 0;
    const before = Date.parse(args.get('@beforeDate') ?? '') || Number.MAX_SAFE_INTEGER;
    const selfId = args.get('@selfId');
    return GAME_LOG.filter(
        (row) =>
            row.table === table &&
            locations.has(row.location) &&
            row.at >= after &&
            row.at <= before &&
            (!selfId || row.userId !== selfId)
    )
        .sort((a, b) => a.at - b.at)
        .map((row) =>
            table === 'join_leave'
                ? [row.type, iso(row.at), NAMES[row.userId] ?? '', row.userId, row.location]
                : [
                      iso(row.at),
                      row.videoUrl,
                      row.videoName,
                      row.videoId,
                      NAMES[row.userId] ?? '',
                      row.userId,
                      row.location
                  ]
        );
}

// Friend list statistics (database.getAllUserStats): last seen, time together and join count per user.
function userStats() {
    const stats = new Map();
    for (const row of GAME_LOG) {
        if (row.table !== 'join_leave' || row.userId === ME) continue;
        const entry = stats.get(row.userId) ?? { lastSeen: 0, time: 0, locations: new Set(), maxId: 0 };
        entry.lastSeen = Math.max(entry.lastSeen, row.at);
        entry.time += row.time ?? 0;
        entry.locations.add(row.location);
        entry.maxId = Math.max(entry.maxId, row.id);
        stats.set(row.userId, entry);
    }
    return [...stats].map(([userId, entry]) => [
        iso(entry.lastSeen),
        userId,
        entry.time,
        entry.locations.size,
        NAMES[userId] ?? '',
        entry.maxId
    ]);
}

// ---------------------------------------------------------------- friend log

const FRIEND_LOG = [
    { at: NOW - 20 * MINUTE, type: 'TrustLevel', userId: FRIEND.ember, trust: 'Trusted User', previous: 'Known User' },
    {
        at: NOW - 3 * HOUR,
        type: 'DisplayName',
        userId: FRIEND.cobalt,
        name: 'Cobalt',
        previousName: 'CobaltBlueberryPancakes'
    },
    { at: NOW - 9 * HOUR, type: 'Friend', userId: FRIEND.gale },
    { at: NOW - 1 * DAY, type: 'FriendRequest', userId: STRANGER.kestrel, name: 'Kestrel' },
    { at: NOW - 2 * DAY, type: 'CancelFriendRequest', userId: STRANGER.maple, name: 'Maple' },
    { at: NOW - 4 * DAY, type: 'Unfriend', userId: STRANGER.orchid, name: 'Orchid' },
    { at: NOW - 6 * DAY, type: 'TrustLevel', userId: FRIEND.dune, trust: 'User', previous: 'New User' },
    { at: NOW - 12 * DAY, type: 'Friend', userId: FRIEND.aurora }
];

function friendLogRows() {
    // SELECT * FROM <user>_friend_log_history: oldest first (the store reverses it).
    return FRIEND_LOG.map((entry, index) => [
        FRIEND_LOG.length - index,
        iso(entry.at),
        entry.type,
        entry.userId,
        entry.name ?? NAMES[entry.userId] ?? '',
        entry.previousName ?? null,
        entry.trust ?? null,
        entry.previous ?? null,
        index + 1
    ]).reverse();
}

// ---------------------------------------------------------------- web API

function buildStranger(source, data) {
    const template = data.friends[0];
    const image = placeholderImage(source.image, source.displayName.slice(0, 1));
    return {
        ...template,
        id: source.id,
        displayName: source.displayName,
        username: source.displayName.toLowerCase(),
        bio: source.bio,
        bioLinks: source.bioLinks,
        currentAvatarImageUrl: image,
        currentAvatarThumbnailImageUrl: image,
        imageUrl: image,
        status: source.status,
        statusDescription: source.statusDescription,
        state: 'online',
        location: '',
        worldId: '',
        instanceId: '',
        tags: source.tags,
        last_platform: source.platform,
        platform: source.platform,
        isFriend: false,
        friendKey: ''
    };
}

function buildWorld(source, data) {
    const image = placeholderImage(source.image, source.name.slice(0, 1));
    return {
        ...data.worlds[0],
        id: source.id,
        name: source.name,
        authorName: 'Preview Studio',
        imageUrl: image,
        thumbnailImageUrl: image,
        favorites: 321,
        visits: 4567
    };
}

function buildGroup(source) {
    const icon = placeholderImage(source.image, source.name.slice(0, 1));
    return {
        id: source.id,
        name: source.name,
        shortCode: source.shortCode,
        discriminator: source.discriminator,
        description: source.description,
        memberCount: source.memberCount,
        iconUrl: icon,
        bannerUrl: icon,
        privacy: 'default',
        ownerId: FRIEND.aurora,
        tags: [],
        galleries: [],
        createdAt: '2023-04-01T12:00:00.000Z'
    };
}

function notificationsV1() {
    const at = (minutes) => iso(NOW - minutes * MINUTE);
    const base = { receiverUserId: ME, seen: false };
    return [
        {
            ...base,
            id: 'not_00000000-0000-4000-8000-000000000001',
            type: 'invite',
            senderUserId: FRIEND.aurora,
            senderUsername: 'Aurora',
            message: `This is a generated invite to ${WORLD_NAMES[WORLD.rooftops]}`,
            details: {
                worldId: `${WORLD.rooftops}:24680~friends(${FRIEND.dune})~region(jp)`,
                worldName: WORLD_NAMES[WORLD.rooftops],
                inviteMessage: 'Rooftop party, bring your glow sticks!'
            },
            created_at: at(4)
        },
        {
            ...base,
            id: 'not_00000000-0000-4000-8000-000000000002',
            type: 'requestInvite',
            senderUserId: FRIEND.birch,
            senderUsername: 'Birch',
            message: '',
            details: { platform: 'android', requestMessage: 'Can I join? I brought snacks and a very long story.' },
            created_at: at(11)
        },
        {
            ...base,
            id: 'not_00000000-0000-4000-8000-000000000003',
            type: 'friendRequest',
            senderUserId: STRANGER.kestrel,
            senderUsername: 'Kestrel',
            message: '',
            details: {},
            created_at: at(26)
        },
        {
            ...base,
            id: 'not_00000000-0000-4000-8000-000000000004',
            type: 'message',
            senderUserId: FRIEND.ember,
            senderUsername: 'Ember',
            message: 'Are you still in Lantern Harbor? The lanterns start at the top of the hour.',
            details: {},
            created_at: at(52)
        },
        {
            ...base,
            id: 'not_00000000-0000-4000-8000-000000000005',
            type: 'inviteResponse',
            senderUserId: FRIEND.dune,
            senderUsername: 'Dune',
            message: '',
            details: { responseMessage: 'Maybe later, finishing a build first.' },
            created_at: at(95),
            seen: true
        }
    ];
}

function notificationsV2() {
    const at = (minutes) => iso(NOW - minutes * MINUTE);
    const expires = iso(NOW + 7 * DAY);
    const group = GROUPS[0];
    const groupIcon = placeholderImage(group.image, 'P');
    return [
        {
            id: 'ntf_00000000-0000-4000-8000-000000000101',
            version: 2,
            type: 'boop',
            senderUserId: FRIEND.cobalt,
            senderUsername: 'Cobalt',
            receiverUserId: ME,
            title: 'Cobalt booped you!',
            message: '',
            imageUrl: 'default_boop',
            link: '',
            linkText: '',
            responses: [{ type: 'reply', data: '', icon: 'reply', text: 'Boop back' }],
            data: {},
            seen: false,
            createdAt: at(7),
            updatedAt: at(7),
            expiresAt: expires
        },
        {
            id: 'ntf_00000000-0000-4000-8000-000000000102',
            version: 2,
            type: 'group.announcement',
            senderUserId: group.id,
            senderUsername: group.name,
            receiverUserId: ME,
            title: 'Weekly meetup',
            message: 'Friday at 20:00 UTC in Lantern Harbor. Bring a lantern and a friend!',
            imageUrl: groupIcon,
            link: `group:${group.id}`,
            linkText: group.name,
            responses: [{ type: 'unsubscribe', data: '', icon: 'bell-slash', text: 'Unsubscribe' }],
            data: { groupName: group.name },
            seen: false,
            createdAt: at(33),
            updatedAt: at(33),
            expiresAt: expires
        },
        {
            id: 'ntf_00000000-0000-4000-8000-000000000103',
            version: 2,
            type: 'group.invite',
            senderUserId: GROUPS[1].id,
            senderUsername: GROUPS[1].name,
            receiverUserId: ME,
            title: 'Group invite',
            message: 'Aurora invited you to join Night Tram Club.',
            imageUrl: placeholderImage(GROUPS[1].image, 'N'),
            link: `group:${GROUPS[1].id}`,
            linkText: GROUPS[1].name,
            responses: [
                { type: 'accept', data: '', icon: 'check', text: 'Accept' },
                { type: 'decline', data: '', icon: 'cancel', text: 'Decline' },
                { type: 'block', data: '', icon: 'ban', text: 'Block group' }
            ],
            data: { groupName: GROUPS[1].name },
            seen: false,
            createdAt: at(140),
            updatedAt: at(140),
            expiresAt: expires
        },
        {
            id: 'ntf_00000000-0000-4000-8000-000000000104',
            version: 2,
            type: 'group.event.starting',
            senderUserId: group.id,
            senderUsername: group.name,
            receiverUserId: ME,
            title: 'Lantern launch is starting',
            message: 'The event starts in 15 minutes.',
            imageUrl: groupIcon,
            link: `event:${group.id},cal_00000000-0000-4000-8000-000000000001`,
            linkText: 'Lantern launch',
            responses: [],
            data: { groupName: group.name },
            seen: true,
            createdAt: at(300),
            updatedAt: at(300),
            expiresAt: expires
        }
    ];
}

function playerModerations() {
    const at = (hours) => iso(NOW - hours * HOUR);
    const mine = (id, type, targetId, hours) => ({
        id: `pmod_00000000-0000-4000-8000-${String(id).padStart(12, '0')}`,
        type,
        sourceUserId: ME,
        sourceDisplayName: 'Preview User',
        targetUserId: targetId,
        targetDisplayName: NAMES[targetId],
        created: at(hours)
    });
    return [
        mine(1, 'mute', STRANGER.maple, 2),
        mine(2, 'block', STRANGER.orchid, 30),
        mine(3, 'interactOff', STRANGER.lumen, 50),
        mine(4, 'muteChat', STRANGER.kestrel, 75),
        mine(5, 'unmute', STRANGER.nimbus, 120),
        mine(6, 'interactOn', STRANGER.pixel, 160)
    ];
}

// The invite response slots behind "Decline with message" (invites) and the request-invite answer dialog. Two slots
// were edited recently, so their cool-down is still running.
const INVITE_RESPONSE_TEXTS = {
    response: [
        'Sorry, I am busy right now!',
        'Maybe later, finishing a build first.',
        'Heading to bed, catch you tomorrow.',
        'Already in a world with friends, join me instead?',
        'On Quest right now, that world is PC only.',
        'Recording a video, will ping you when I am done.'
    ],
    requestResponse: [
        'Instance is full, try again in a bit.',
        'Private meetup today, sorry!',
        'Ask Aurora, she is hosting.',
        'Sure, sending an invite in a minute.'
    ]
};

function inviteResponseMessages(type) {
    const texts = INVITE_RESPONSE_TEXTS[type];
    return texts.map((message, slot) => {
        const minutesAgo = slot === 1 ? 12 : slot === 3 ? 41 : (3 * DAY) / MINUTE + slot * 60;
        return {
            id: `invm_00000000-0000-4000-8000-${String((type === 'response' ? 100 : 200) + slot).padStart(12, '0')}`,
            slot,
            message,
            messageType: type,
            updatedAt: iso(NOW - minutesAgo * MINUTE),
            remainingCooldownMinutes: minutesAgo < 60 ? 60 - minutesAgo : 0,
            canBeUpdated: minutesAgo >= 60
        };
    });
}

/**
 * @param {string} path
 * @param {URLSearchParams} query
 * @param {string} method
 * @param {object} options
 * @param {object} data
 * @returns {unknown} Response body, or undefined to fall through
 */
export function webApi(path, query, method, options, data) {
    if (method !== 'GET') return undefined;
    const stranger = STRANGERS.find((user) => path === `users/${user.id}`);
    if (stranger) return buildStranger(stranger, data);
    const profileUser = STRANGERS.find((user) => path === `profile/${user.id}`);
    if (profileUser) {
        return { id: profileUser.id, displayName: profileUser.displayName, bioLinks: profileUser.bioLinks };
    }
    if (path === 'auth/user/notifications') {
        return query.get('hidden') === 'true' || Number(query.get('offset') ?? 0) > 0 ? [] : notificationsV1();
    }
    if (path === 'notifications') {
        return Number(query.get('offset') ?? 0) > 0 ? [] : notificationsV2();
    }
    const inviteMessages = path.match(/^message\/([^/]+)\/(response|requestResponse)$/);
    if (inviteMessages && inviteMessages[1] === ME) return inviteResponseMessages(inviteMessages[2]);
    if (path === 'auth/user/playermoderations') return playerModerations();
    if (path === 'auth/user/avatarmoderations') return [];
    if (path === 'users') {
        const search = String(query.get('search') ?? '').toLowerCase();
        const offset = Number(query.get('offset') ?? 0);
        const n = Number(query.get('n') ?? 10);
        const users = [...data.friends, ...STRANGERS.map((user) => buildStranger(user, data))].filter(
            (user) => !search || user.displayName.toLowerCase().includes(search)
        );
        return users.slice(offset, offset + n);
    }
    if (path === 'worlds' || /^worlds\/(active|recent|favorites)$/.test(path)) {
        const offset = Number(query.get('offset') ?? 0);
        const n = Number(query.get('n') ?? 10);
        const worlds = [...data.worlds, ...EXTRA_WORLDS.map((world) => buildWorld(world, data))];
        return worlds.slice(offset, offset + n);
    }
    if (path === 'groups') {
        const search = String(query.get('query') ?? '').toLowerCase();
        const offset = Number(query.get('offset') ?? 0);
        const n = Number(query.get('n') ?? 10);
        return GROUPS.filter((group) => !search || group.name.toLowerCase().includes(search) || search.length < 3)
            .map(buildGroup)
            .slice(offset, offset + n);
    }
    const group = GROUPS.find((entry) => path === `groups/${entry.id}`);
    if (group) return buildGroup(group);
    return undefined;
}

/**
 * @param {string} sql
 * @param {Map<string, unknown>} args
 * @returns {unknown[][] | undefined}
 */
export function sqlite(sql, args) {
    const text = String(sql).replace(/\s+/g, ' ').trim();
    if (/^SELECT \* FROM \w+_friend_log_history$/.test(text)) return friendLogRows();
    if (text.includes('FROM gamelog_join_leave g WHERE')) return userStats();
    if (text.includes('UNION ALL') && text.includes('FROM gamelog_')) return unionRows(text, args);
    if (
        text.startsWith('SELECT id, created_at, location, world_id, world_name, time, group_name FROM gamelog_location')
    ) {
        return sessionSegments(text, args);
    }
    if (text.startsWith('SELECT type, created_at, display_name, user_id, location FROM gamelog_join_leave')) {
        return sessionEvents('join_leave', args);
    }
    if (
        text.startsWith(
            'SELECT created_at, video_url, video_name, video_id, display_name, user_id, location FROM gamelog_video_play'
        )
    ) {
        return sessionEvents('video_play', args);
    }
    const perTable = text.match(/^SELECT \* FROM gamelog_(\w+) WHERE created_at >= date\('([^']+)'\)/);
    if (perTable) {
        const since = Date.parse(perTable[2].slice(0, 10));
        return tableRows(perTable[1], Number.isFinite(since) ? since : NOW - DAY);
    }
    return undefined;
}

// ---------------------------------------------------------------- dashboard

// A dashboard with the three widgets and a full page panel. Seeded through the fake SQLite config table once the fake
// host exists, unless the user already saved dashboards in this preview session.
const DASHBOARD_CONFIG = {
    dashboards: [
        {
            id: 'dashboard-preview-0001',
            name: 'Overview',
            icon: 'ri-dashboard-line',
            rows: [
                {
                    panels: [
                        { key: 'widget:instance', config: { columns: ['icon', 'displayName', 'rank', 'timer'] } },
                        { key: 'widget:game-log', config: { filters: [] } }
                    ],
                    direction: 'horizontal'
                },
                { panels: ['feed'], direction: 'horizontal' },
                {
                    panels: [{ key: 'widget:feed', config: { filters: [] } }, 'friends-locations'],
                    direction: 'vertical'
                }
            ]
        }
    ]
};

function seedDashboard() {
    const api = window.interopApi;
    if (!api) return false;
    const key = 'config:vrcx_dashboardconfigs';
    Promise.resolve(
        api.callDotNetMethod('SQLite', 'ExecuteJson', ['SELECT value FROM configs WHERE key = @key', { '@key': key }])
    )
        .then((json) => {
            if (JSON.parse(json).length) return undefined;
            return api.callDotNetMethod('SQLite', 'ExecuteNonQuery', [
                'INSERT OR REPLACE INTO configs (key, value) VALUES (@key, @value)',
                { '@key': key, '@value': JSON.stringify(DASHBOARD_CONFIG) }
            ]);
        })
        .catch((error) => console.warn('[preview] dashboard seed failed', error));
    return true;
}

// The companion reports VRChat as running only after the app has started, so the player list reload that runs when
// the friends list arrives finds no game yet. On a device the companion's log sync fills it; the preview replays the
// game log once instead (companion=playing only).
function reloadPlayerListWhenReady() {
    let attempts = 0;
    const timer = setInterval(() => {
        attempts += 1;
        const pinia = window.$pinia;
        const ready =
            pinia?.game?.isGameRunning &&
            pinia.friend?.friends?.size > 0 &&
            pinia.location?.lastLocation &&
            !pinia.location.lastLocation.location;
        if (ready) {
            clearInterval(timer);
            import('../../../../coordinators/gameLogCoordinator.js')
                .then((module) => module.tryLoadPlayerList())
                .catch((error) => console.warn('[preview] player list reload failed', error));
        } else if (attempts > 120) {
            clearInterval(timer);
        }
    }, 500);
}

if (typeof window !== 'undefined') {
    // mockBridge.js imports this module before it installs window.interopApi; the app itself loads much later.
    const timer = setInterval(() => {
        if (seedDashboard()) clearInterval(timer);
    }, 0);
    reloadPlayerListWhenReady();
}

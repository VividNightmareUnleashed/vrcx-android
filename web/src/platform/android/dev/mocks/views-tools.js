// Preview harness data for these route views: Favorites, My Avatars, Charts,
// Tools (Gallery, group calendar) and Settings. Dev only; loaded by ../mockBridge.js through its mocks/*.js hooks.
//
// Every id is made up (usr_/wrld_/avtr_/grp_/file_/prn_/cal_ 00000000-... ranges), every image is a local SVG, and
// nothing here is ever sent anywhere.

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

const ME = 'usr_00000000-0000-4000-8000-000000000001';
const FRIENDS = {
    aurora: 'usr_00000000-0000-4000-8000-000000000101',
    birch: 'usr_00000000-0000-4000-8000-000000000102',
    cobalt: 'usr_00000000-0000-4000-8000-000000000103',
    dune: 'usr_00000000-0000-4000-8000-000000000104',
    ember: 'usr_00000000-0000-4000-8000-000000000105',
    fjord: 'usr_00000000-0000-4000-8000-000000000106',
    gale: 'usr_00000000-0000-4000-8000-000000000107',
    harbor: 'usr_00000000-0000-4000-8000-000000000108'
};
const FRIEND_NAMES = {
    [FRIENDS.aurora]: 'Aurora',
    [FRIENDS.birch]: 'Birch',
    [FRIENDS.cobalt]: 'Cobalt',
    [FRIENDS.dune]: 'Dune',
    [FRIENDS.ember]: 'Ember',
    [FRIENDS.fjord]: 'Fjord',
    [FRIENDS.gale]: 'Gale',
    [FRIENDS.harbor]: 'Harbor'
};
const FIXTURE_WORLDS = {
    lantern: ['wrld_00000000-0000-4000-8000-00000000a001', 'Lantern Harbor'],
    moss: ['wrld_00000000-0000-4000-8000-00000000a002', 'Moss Library'],
    neon: ['wrld_00000000-0000-4000-8000-00000000a003', 'Neon Rooftops'],
    glass: ['wrld_00000000-0000-4000-8000-00000000a004', 'Glass Observatory']
};

const PALETTE = ['#5b8def', '#e0823d', '#3fae6a', '#b05bd6', '#d94f6b', '#2fa6b8', '#c9a227', '#6f7bd8'];

/**
 * @param {string} color
 * @param {string} label
 * @param {number} [width]
 * @param {number} [height]
 * @returns {string} Data: URL of a flat placeholder image
 */
function placeholder(color, label, width = 256, height = 192) {
    const size = Math.round(Math.min(width, height) * 0.38);
    const svg =
        `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">` +
        `<rect width="${width}" height="${height}" fill="${color}"/>` +
        `<text x="${width / 2}" y="${height / 2 + size * 0.35}" font-family="sans-serif" font-size="${size}" ` +
        `font-weight="600" fill="rgba(255,255,255,0.85)" text-anchor="middle">${label}</text></svg>`;
    return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

function iso(msAgo) {
    return new Date(Date.now() - msAgo).toISOString();
}

function pad(n, width) {
    return String(n).padStart(width, '0');
}

// ---------------------------------------------------------------- worlds

const EXTRA_WORLD_NAMES = [
    'Sunset Pier',
    'Paper Garden',
    'Midnight Arcade',
    'Cloud Terrace',
    'Rainy Tram Stop',
    'Crystal Caverns',
    'Retro Living Room',
    'Starlight Theatre',
    'Tidepool Lab',
    'Maple Cabin'
];

const extraWorlds = EXTRA_WORLD_NAMES.map((name, index) => {
    const id = `wrld_00000000-0000-4000-8000-00000000b${pad(index + 1, 3)}`;
    const image = placeholder(PALETTE[index % PALETTE.length], name.slice(0, 1));
    return {
        id,
        name,
        description: `${name}: a made-up world for the preview harness.`,
        authorId: index % 2 ? FRIENDS.birch : FRIENDS.dune,
        authorName: index % 2 ? 'Birch' : 'Dune',
        capacity: 32,
        recommendedCapacity: 16,
        imageUrl: image,
        thumbnailImageUrl: image,
        releaseStatus: index === 6 ? 'private' : 'public',
        tags: ['system_approved'],
        favorites: 1000 + index * 37,
        visits: 20000 + index * 911,
        popularity: 5,
        heat: 2,
        occupants: (index * 7) % 23,
        publicOccupants: 3,
        privateOccupants: 1,
        created_at: '2023-02-01T12:00:00.000Z',
        updated_at: '2025-06-01T12:00:00.000Z',
        publicationDate: '2023-02-02T12:00:00.000Z',
        labsPublicationDate: 'none',
        version: 4,
        unityPackages: [],
        instances: []
    };
});
const extraWorldsById = new Map(extraWorlds.map((world) => [world.id, world]));

// ---------------------------------------------------------------- avatars

const AVATAR_NAMES = [
    'Fox Courier',
    'Moth Librarian',
    'Chrome Knight',
    'Pastel Robot',
    'Forest Spirit',
    'Night Owl',
    'Velvet Cat',
    'Neon Samurai',
    'Tea Witch',
    'Snow Rabbit',
    'Deep Sea Diver',
    'Clockwork Bard',
    'Paper Dragon',
    'Lunar Moth',
    'Arcade Hero',
    'Glass Golem'
];
const PERF = ['Excellent', 'Good', 'Medium', 'Poor', 'VeryPoor'];

function buildAvatar(name, index, { authorId, authorName, idBlock }) {
    const id = `avtr_00000000-0000-4000-8000-0000000${idBlock}${pad(index + 1, 2)}`;
    const image = placeholder(PALETTE[(index + 3) % PALETTE.length], name.slice(0, 1));
    const unityPackages = [
        {
            id: `unp_00000000-0000-4000-8000-0000000${idBlock}${pad(index + 1, 2)}`,
            platform: 'standalonewindows',
            variant: 'standard',
            performanceRating: PERF[index % PERF.length],
            unityVersion: '2022.3.22f1'
        }
    ];
    if (index % 3 !== 2) {
        unityPackages.push({
            platform: 'android',
            variant: 'standard',
            performanceRating: PERF[(index + 2) % PERF.length],
            unityVersion: '2022.3.22f1'
        });
    }
    if (index % 4 === 0) {
        unityPackages.push({
            platform: 'ios',
            variant: 'standard',
            performanceRating: PERF[(index + 1) % PERF.length],
            unityVersion: '2022.3.22f1'
        });
    }
    if (index % 5 === 1) {
        unityPackages.push({ platform: 'standalonewindows', variant: 'impostor', impostorizerVersion: '1.2.0' });
    }
    return {
        id,
        name,
        description: `${name} (preview avatar)`,
        authorId,
        authorName,
        imageUrl: image,
        thumbnailImageUrl: image,
        releaseStatus: index % 4 === 3 ? 'private' : 'public',
        version: 3 + (index % 9),
        featured: false,
        tags: index % 2 ? ['author_tag_cute', 'content_sex'] : ['author_tag_armor'],
        styles: { primary: null, secondary: null },
        unityPackages,
        created_at: iso((400 - index * 17) * DAY),
        updated_at: iso((index * 5 + 1) * DAY)
    };
}

const ownAvatars = AVATAR_NAMES.slice(0, 14).map((name, index) =>
    buildAvatar(name, index, { authorId: ME, authorName: 'Preview User', idBlock: '0c' })
);
const ownAvatarsById = new Map(ownAvatars.map((avatar) => [avatar.id, avatar]));

const favoriteAvatars = AVATAR_NAMES.map((name, index) =>
    buildAvatar(`${name} Remix`, index, {
        authorId: Object.values(FRIENDS)[index % 8],
        authorName: Object.values(FRIEND_NAMES)[index % 8],
        idBlock: '0d'
    })
);
const favoriteAvatarsById = new Map(favoriteAvatars.map((avatar) => [avatar.id, avatar]));

const AVATAR_TAGS = [
    [0, 'main', 'oklch(0.62 0.17 250 / 0.25)'],
    [0, 'quest ok', null],
    [1, 'events', 'oklch(0.7 0.15 150 / 0.25)'],
    [3, 'wip', 'oklch(0.7 0.17 60 / 0.25)'],
    [4, 'main', 'oklch(0.62 0.17 250 / 0.25)'],
    [7, 'photo', null],
    [9, 'quest ok', null]
];

// ---------------------------------------------------------------- favorites

const FAVORITE_GROUPS = [
    ['friend', 'group_0', 'Close friends', 'public'],
    ['friend', 'group_1', 'Event crew', 'friends'],
    ['friend', 'group_2', 'Group 3', 'private'],
    ['world', 'worlds1', 'Chill spots', 'public'],
    ['world', 'worlds2', 'Game nights', 'friends'],
    ['world', 'worlds3', 'Worlds 3', 'private'],
    ['world', 'worlds4', 'Worlds 4', 'private'],
    ['avatar', 'avatars1', 'Daily', 'private'],
    ['avatar', 'avatars2', 'Costumes', 'private'],
    ['avatar', 'avatars3', 'Avatars 3', 'private'],
    ['avatar', 'avatars4', 'Avatars 4', 'private'],
    ['avatar', 'avatars5', 'Avatars 5', 'private'],
    ['avatar', 'avatars6', 'Avatars 6', 'private']
].map(([type, name, displayName, visibility], index) => ({
    id: `fvgrp_00000000-0000-4000-8000-0000000000${pad(index + 1, 2)}`,
    ownerId: ME,
    ownerDisplayName: 'Preview User',
    name,
    displayName,
    type,
    visibility,
    tags: []
}));

const favoriteEntries = [];
function addFavorite(type, objectId, group, index) {
    favoriteEntries.push({
        id: `fvrt_00000000-0000-4000-8000-000000${type.slice(0, 1) === 'f' ? '1' : type === 'world' ? '2' : '3'}${pad(
            favoriteEntries.length + 1,
            5
        )}`,
        type,
        favoriteId: objectId,
        tags: [group],
        created_at: iso((index + 1) * 3 * DAY)
    });
}
[FRIENDS.aurora, FRIENDS.birch, FRIENDS.dune, FRIENDS.ember, FRIENDS.harbor].forEach((id, index) =>
    addFavorite('friend', id, 'group_0', index)
);
[FRIENDS.cobalt, FRIENDS.fjord, FRIENDS.gale].forEach((id, index) => addFavorite('friend', id, 'group_1', index));
const favoriteWorldList = [
    ...Object.values(FIXTURE_WORLDS).map(([id]) => ({ id, group: 'worlds1' })),
    ...extraWorlds.slice(0, 6).map((world) => ({ id: world.id, group: 'worlds1' })),
    ...extraWorlds.slice(6).map((world) => ({ id: world.id, group: 'worlds2' }))
];
favoriteWorldList.forEach(({ id, group }, index) => addFavorite('world', id, group, index));
favoriteAvatars.forEach((avatar, index) =>
    addFavorite('avatar', avatar.id, index < 11 ? 'avatars1' : 'avatars2', index)
);

// Local (VRCX) favourite groups live in SQLite.
const LOCAL_WORLD_FAVORITES = [
    ['Photo spots', extraWorlds.slice(1, 9).map((world) => world.id)],
    ['To visit', [extraWorlds[9].id, FIXTURE_WORLDS.glass[0]]]
];
const LOCAL_AVATAR_FAVORITES = [['Try later', favoriteAvatars.slice(4, 9).map((avatar) => avatar.id)]];
const LOCAL_FRIEND_FAVORITES = [['Quest players', [FRIENDS.birch, FRIENDS.gale]]];

function cacheRow(entity) {
    return [
        entity.id,
        iso(DAY),
        entity.authorId,
        entity.authorName,
        entity.created_at,
        entity.description,
        entity.imageUrl,
        entity.name,
        entity.releaseStatus,
        entity.thumbnailImageUrl,
        entity.updated_at,
        entity.version
    ];
}

function fixtureWorldEntity(worldId, name, index) {
    const image = placeholder(PALETTE[index % PALETTE.length], name.slice(0, 1));
    return {
        id: worldId,
        authorId: FRIENDS.aurora,
        authorName: 'Aurora',
        created_at: '2022-05-01T12:00:00.000Z',
        description: '',
        imageUrl: image,
        name,
        releaseStatus: 'public',
        thumbnailImageUrl: image,
        updated_at: '2025-01-01T12:00:00.000Z',
        version: 3
    };
}

const cachedWorldRows = [
    ...extraWorlds.map(cacheRow),
    ...Object.values(FIXTURE_WORLDS).map(([id, name], index) => cacheRow(fixtureWorldEntity(id, name, index)))
];

// ---------------------------------------------------------------- Gallery (VRC+ files, prints, inventory)

function fileEntry(tag, index, extra = {}) {
    const color = PALETTE[(index + (tag.length % 5)) % PALETTE.length];
    const square = tag !== 'gallery';
    const url = placeholder(color, String(index + 1), square ? 256 : 320, square ? 256 : 240);
    return {
        id: `file_00000000-0000-4000-8000-0000${pad(tag.length, 2)}${pad(index + 1, 6)}`,
        name: `${tag} ${index + 1}`,
        ownerId: ME,
        mimeType: 'image/png',
        extension: '.png',
        tags: [tag],
        versions: [
            { version: 0, status: 'complete', created_at: iso((index + 2) * DAY) },
            { version: 1, status: 'complete', created_at: iso((index + 1) * DAY), file: { url } }
        ],
        ...extra
    };
}

const galleryFiles = {
    gallery: Array.from({ length: 7 }, (_, i) => fileEntry('gallery', i)),
    icon: Array.from({ length: 5 }, (_, i) => fileEntry('icon', i)),
    emoji: Array.from({ length: 3 }, (_, i) =>
        fileEntry(
            'emoji',
            i,
            i === 1 ? { animationStyle: 'bats', frames: 4, framesOverTime: 12, loopStyle: 'linear' } : {}
        )
    ),
    sticker: Array.from({ length: 2 }, (_, i) => fileEntry('sticker', i))
};

const prints = Array.from({ length: 5 }, (_, index) => {
    const [worldId, worldName] = Object.values(FIXTURE_WORLDS)[index % 4];
    const url = placeholder(PALETTE[(index + 5) % PALETTE.length], `P${index + 1}`, 320, 180);
    return {
        id: `prn_00000000-0000-4000-8000-0000000000${pad(index + 1, 2)}`,
        authorId: index % 2 ? FRIENDS.aurora : ME,
        authorName: index % 2 ? 'Aurora' : 'Preview User',
        ownerId: ME,
        createdAt: iso((index + 1) * 2 * DAY),
        timestamp: iso((index + 1) * 2 * DAY),
        note: index === 0 ? 'Sunset at the harbor with everyone' : index === 2 ? 'Library night' : '',
        worldId,
        worldName,
        files: { image: url, fileId: `file_00000000-0000-4000-8000-00000009${pad(index + 1, 4)}` }
    };
});

const inventoryItems = ['Balloon Bundle', 'Confetti Popper', 'Snowball Pack'].map((name, index) => ({
    id: `inv_00000000-0000-4000-8000-0000000000${pad(index + 1, 2)}`,
    name,
    description: `${name} (preview item)`,
    itemType: index === 1 ? 'emoji' : 'prop',
    itemTypeLabel: index === 1 ? 'Emoji' : 'Prop',
    imageUrl: placeholder(PALETTE[(index + 1) % PALETTE.length], name.slice(0, 1), 256, 256),
    created_at: iso((index + 1) * 9 * DAY),
    holderId: ME,
    flags: [],
    tags: []
}));

// ---------------------------------------------------------------- group calendar

const GROUPS = [
    ['grp_00000000-0000-4000-8000-000000000c01', 'Harbor Social Club', 'HARBOR'],
    ['grp_00000000-0000-4000-8000-000000000c02', 'Late Night DJs', 'DJNITE'],
    ['grp_00000000-0000-4000-8000-000000000c03', 'World Hoppers', 'HOPPER']
].map(([id, name, shortCode], index) => ({
    id,
    name,
    shortCode,
    discriminator: '0001',
    description: `${name} (preview group)`,
    iconUrl: placeholder(PALETTE[index + 2], name.slice(0, 1), 128, 128),
    bannerUrl: placeholder(PALETTE[index + 4], name.slice(0, 1), 480, 270),
    privacy: 'default',
    ownerId: FRIENDS.aurora,
    rules: '',
    links: [],
    languages: ['eng'],
    memberCount: 120 + index * 40,
    onlineMemberCount: 12,
    memberCountSyncedAt: iso(HOUR),
    isVerified: false,
    joinState: 'open',
    tags: [],
    galleries: [],
    createdAt: '2024-01-01T00:00:00.000Z',
    lastPostCreatedAt: null,
    membershipStatus: 'member',
    myMember: {
        id: `gmem_00000000-0000-4000-8000-000000000c0${index + 1}`,
        groupId: id,
        userId: ME,
        roleIds: [],
        isRepresenting: false,
        membershipStatus: 'member',
        permissions: []
    },
    roles: []
}));
const groupsById = new Map(GROUPS.map((group) => [group.id, group]));

function buildCalendarEvents() {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const plan = [
        [0, 20, 2, 0, 'Harbor meetup', 'hangout'],
        [0, 20, 1.5, 1, 'Vinyl night', 'music'],
        [0, 22, 3, 2, 'World hop: horror edition', 'exploration'],
        [1, 19, 2, 0, 'Photo walk', 'hangout'],
        [2, 21, 2, 1, 'Drum and bass session', 'music'],
        [4, 18, 1, 2, 'New worlds showcase', 'exploration'],
        [7, 20, 2, 0, 'Monthly town hall', 'other'],
        [-2, 21, 2, 1, 'House classics', 'music']
    ];
    return plan.map(([dayOffset, hour, hours, groupIndex, title, category], index) => {
        const start = new Date(today.getTime() + dayOffset * DAY + hour * HOUR);
        const group = GROUPS[groupIndex];
        return {
            id: `cal_00000000-0000-4000-8000-0000000000${pad(index + 1, 2)}`,
            ownerId: group.id,
            title,
            description: `${title} hosted by ${group.name}. Everyone is welcome; bring friends.`,
            startsAt: start.toISOString(),
            endsAt: new Date(start.getTime() + hours * HOUR).toISOString(),
            accessType: index % 3 ? 'group' : 'public',
            category,
            imageId: null,
            imageUrl: placeholder(PALETTE[(index + 1) % PALETTE.length], title.slice(0, 1), 480, 200),
            interestedUserCount: 5 + index * 3,
            closeInstanceAfterEndMinutes: 30,
            createdAt: iso((10 + index) * DAY),
            updatedAt: iso((5 + index) * DAY),
            featured: index === 2,
            isDraft: false,
            languages: ['eng'],
            platforms: ['standalonewindows', 'android'],
            roleIds: [],
            tags: [],
            type: 'event',
            usesInstanceOverflow: false,
            userInterest: { isFollowing: index === 0 || index === 4, createdAt: null, updatedAt: null }
        };
    });
}

// ---------------------------------------------------------------- Charts: instance activity, hot worlds, mutuals

function buildInstanceActivityRows() {
    // gamelog_join_leave rows: [id, created_at (leave time), type, display_name, location, user_id, time (ms)].
    const rows = [];
    const dayStart = new Date();
    dayStart.setHours(0, 0, 0, 0);
    const sessions = [
        // [day offset, start hour, hours, world, instance, friends]
        [0, 1, 1.5, FIXTURE_WORLDS.lantern, '12345~region(eu)', [FRIENDS.aurora, FRIENDS.ember, FRIENDS.dune]],
        [
            0,
            3,
            0.75,
            FIXTURE_WORLDS.neon,
            '44120~friends(usr_00000000-0000-4000-8000-000000000101)~region(eu)',
            [FRIENDS.aurora]
        ],
        [
            0,
            8,
            2.25,
            FIXTURE_WORLDS.moss,
            '80211~region(eu)',
            [FRIENDS.birch, FRIENDS.cobalt, FRIENDS.gale, FRIENDS.harbor]
        ],
        [0, 11, 1, FIXTURE_WORLDS.glass, '10033~private(usr_00000000-0000-4000-8000-000000000001)~region(eu)', []],
        [-1, 20, 3, FIXTURE_WORLDS.lantern, '50505~region(us)', [FRIENDS.fjord, FRIENDS.dune]]
    ];
    let id = 1;
    for (const [dayOffset, startHour, hours, [worldId], instance, friends] of sessions) {
        const start = dayStart.getTime() + dayOffset * DAY + startHour * HOUR;
        const end = Math.min(start + hours * HOUR, Date.now() - MINUTE);
        if (end <= start) {
            continue;
        }
        const location = `${worldId}:${instance}`;
        rows.push([id++, new Date(end).toISOString(), 'OnPlayerLeft', 'Preview User', location, ME, end - start]);
        friends.forEach((friendId, index) => {
            const joined = start + (index + 1) * 7 * MINUTE;
            const left = Math.max(joined + 10 * MINUTE, end - index * 11 * MINUTE);
            rows.push([
                id++,
                new Date(left).toISOString(),
                'OnPlayerLeft',
                FRIEND_NAMES[friendId],
                location,
                friendId,
                left - joined
            ]);
        });
    }
    return rows;
}

const HOT_WORLDS = [
    [FIXTURE_WORLDS.lantern, 7, 31, 'rising'],
    [FIXTURE_WORLDS.moss, 6, 22, 'stable'],
    [FIXTURE_WORLDS.neon, 5, 18, 'cooling'],
    ...extraWorlds.map((world, index) => [
        [world.id, world.name],
        Math.max(1, 5 - Math.floor(index / 2)),
        14 - index,
        index % 3 ? 'stable' : 'rising'
    ])
];

function hotWorldRows() {
    return HOT_WORLDS.map(([[worldId, worldName], friends, visits], index) => [
        worldId,
        worldName,
        visits,
        friends,
        iso((index + 1) * 5 * HOUR)
    ]);
}

// Trend queries compare the older and the newer half of the window.
function hotWorldTrendRows(recent) {
    return HOT_WORLDS.map(([[worldId], friends, , trend]) => {
        const older = Math.max(1, Math.round(friends / 2));
        const newer = trend === 'rising' ? older + 2 : trend === 'cooling' ? Math.max(0, older - 1) : older;
        return [worldId, recent ? newer : older];
    });
}

function hotWorldFriendRows() {
    return Object.entries(FRIEND_NAMES)
        .slice(0, 6)
        .map(([userId, name], index) => [userId, name, 6 - index, iso((index + 1) * 9 * HOUR)]);
}

const MUTUAL_LINKS = [
    [FRIENDS.aurora, [FRIENDS.birch, FRIENDS.ember, FRIENDS.dune, FRIENDS.harbor]],
    [FRIENDS.birch, [FRIENDS.aurora, FRIENDS.cobalt, FRIENDS.gale]],
    [FRIENDS.cobalt, [FRIENDS.birch, FRIENDS.gale]],
    [FRIENDS.dune, [FRIENDS.aurora, FRIENDS.ember, FRIENDS.fjord]],
    [FRIENDS.ember, [FRIENDS.aurora, FRIENDS.dune]],
    [FRIENDS.fjord, [FRIENDS.dune, FRIENDS.harbor]],
    [FRIENDS.gale, [FRIENDS.birch, FRIENDS.cobalt]],
    [FRIENDS.harbor, [FRIENDS.aurora, FRIENDS.fjord]]
];

// ---------------------------------------------------------------- hooks

function avatarTimeRows() {
    return ownAvatars.slice(0, 8).map((avatar, index) => [avatar.id, (index + 1) * 47 * MINUTE]);
}

function queryNumber(query, key, fallback) {
    const value = Number(query.get(key));
    return Number.isFinite(value) && query.has(key) ? value : fallback;
}

/**
 * VRChat API responses for /api/1/<path>; undefined falls through to the harness defaults.
 *
 * @param {string} path
 * @param {URLSearchParams} query
 * @param {string} method
 * @returns {unknown}
 */
export function webApi(path, query, method) {
    if (method !== 'GET') {
        return undefined;
    }
    const offset = queryNumber(query, 'offset', 0);
    const n = queryNumber(query, 'n', 100);
    const page = (list) => list.slice(offset, offset + n);

    switch (path) {
        case 'favorites': {
            const type = query.get('type');
            return page(type ? favoriteEntries.filter((entry) => entry.type === type) : favoriteEntries);
        }
        case 'favorite/groups':
            return page(FAVORITE_GROUPS);
        case 'worlds/favorites':
            return page(
                favoriteWorldList.map(({ id, group }) => {
                    const entry = favoriteEntries.find((item) => item.favoriteId === id);
                    const world = extraWorldsById.get(id);
                    const fixture = Object.values(FIXTURE_WORLDS).find(([worldId]) => worldId === id);
                    const base =
                        world ??
                        fixtureWorldEntity(id, fixture?.[1] ?? 'World', Object.values(FIXTURE_WORLDS).indexOf(fixture));
                    return { ...base, occupants: base.occupants ?? 4, favoriteId: entry?.id, favoriteGroup: group };
                })
            );
        case 'avatars/favorites': {
            const tag = query.get('tag');
            return page(
                favoriteAvatars
                    .map((avatar) => ({ avatar, entry: favoriteEntries.find((item) => item.favoriteId === avatar.id) }))
                    .filter(({ entry }) => !tag || entry?.tags[0] === tag)
                    .map(({ avatar, entry }) => ({ ...avatar, favoriteId: entry?.id, favoriteGroup: entry?.tags[0] }))
            );
        }
        case 'avatars':
            if (query.get('user') === 'me') {
                return page(ownAvatars);
            }
            return undefined;
        case 'files': {
            const list = galleryFiles[query.get('tag')];
            return list ? page(list) : undefined;
        }
        case 'inventory':
            return { data: offset === 0 ? inventoryItems : [], totalCount: inventoryItems.length };
        case 'inventory/global':
            return [];
        case 'calendar':
            return { results: offset === 0 ? buildCalendarEvents() : [], hasNext: false, totalCount: 8 };
        case 'calendar/following':
            return {
                results: offset === 0 ? buildCalendarEvents().filter((event) => event.userInterest.isFollowing) : [],
                hasNext: false
            };
        case 'calendar/featured':
            return {
                results: offset === 0 ? buildCalendarEvents().filter((event) => event.featured) : [],
                hasNext: false
            };
        default:
            break;
    }

    if (path === `prints/user/${ME}`) {
        return prints;
    }
    let match = path.match(/^worlds\/(wrld_00000000-0000-4000-8000-00000000b\d{3})$/);
    if (match) {
        return extraWorldsById.get(match[1]) ?? null;
    }
    match = path.match(/^avatars\/(avtr_[^/]+)$/);
    if (match) {
        return ownAvatarsById.get(match[1]) ?? favoriteAvatarsById.get(match[1]) ?? undefined;
    }
    match = path.match(/^groups\/(grp_[^/]+)$/);
    if (match) {
        return groupsById.get(match[1]) ?? undefined;
    }
    return undefined;
}

/**
 * SQLite rows for SQLite.Execute; undefined falls through to the harness defaults.
 *
 * @param {string} sql
 * @param {Map<string, unknown>} args
 * @returns {unknown[][] | undefined}
 */
export function sqlite(sql, args) {
    const text = sql.replace(/\s+/g, ' ').trim();

    // Local favourites (Favorites pages).
    if (text === 'SELECT * FROM favorite_world') {
        return LOCAL_WORLD_FAVORITES.flatMap(([group, ids]) =>
            ids.map((worldId, index) => [index + 1, iso((index + 1) * DAY), worldId, group])
        );
    }
    if (text === 'SELECT * FROM cache_world') {
        return cachedWorldRows;
    }
    if (text === 'SELECT * FROM favorite_avatar') {
        return LOCAL_AVATAR_FAVORITES.flatMap(([group, ids]) =>
            ids.map((avatarId, index) => [index + 1, iso((index + 1) * DAY), avatarId, group])
        );
    }
    if (text === 'SELECT * FROM cache_avatar') {
        return favoriteAvatars.map(cacheRow);
    }
    if (text === 'SELECT * FROM favorite_friend') {
        return LOCAL_FRIEND_FAVORITES.flatMap(([group, ids]) =>
            ids.map((userId, index) => [index + 1, iso((index + 1) * DAY), userId, group])
        );
    }
    if (/_avatar_history INNER JOIN cache_avatar/.test(text)) {
        // [avatar_id, created_at, time, ...history columns, then cache_avatar columns from index 3].
        return favoriteAvatars
            .slice(0, 9)
            .map((avatar, index) => [
                avatar.id,
                iso((index + 1) * 6 * HOUR),
                (index + 1) * 20 * MINUTE,
                ...cacheRow(avatar)
            ]);
    }

    // My Avatars: colour tags and time spent.
    if (text === 'SELECT avatar_id, tag, color FROM avatar_tags') {
        return AVATAR_TAGS.map(([index, tag, color]) => [ownAvatars[index].id, tag, color]);
    }
    if (text === 'SELECT DISTINCT tag FROM avatar_tags ORDER BY tag') {
        return [...new Set(AVATAR_TAGS.map(([, tag]) => tag))].sort().map((tag) => [tag]);
    }
    if (/^SELECT avatar_id, time FROM \S+_avatar_history$/.test(text)) {
        return avatarTimeRows();
    }

    // Charts › Instance Activity (game log join/leave rows).
    if (/^SELECT created_at FROM gamelog_join_leave WHERE user_id = @userId/.test(text)) {
        return buildInstanceActivityRows()
            .filter((row) => row[5] === ME)
            .map((row) => [row[1]]);
    }
    if (/^SELECT \* FROM gamelog_join_leave WHERE type = 'OnPlayerLeft'/.test(text)) {
        const start = String(args.get('@utc_start_date') ?? '');
        const end = String(args.get('@utc_end_date') ?? '');
        return buildInstanceActivityRows().filter((row) => {
            const left = row[1];
            const joined = new Date(Date.parse(left) - row[6]).toISOString();
            return (left >= start && left <= end) || (joined >= start && joined <= end);
        });
    }

    // Charts › Hot Worlds (friends' GPS feed).
    if (/_feed_gps/.test(text) && /AS world_id, world_name, COUNT\(\*\) AS visit_count/.test(text)) {
        return hotWorldRows();
    }
    if (/_feed_gps/.test(text) && /AS world_id, COUNT\(DISTINCT user_id\) AS unique_friends/.test(text)) {
        return hotWorldTrendRows(!args.has('@daysOffset'));
    }
    if (/_feed_gps/.test(text) && /AS last_visit/.test(text)) {
        return hotWorldFriendRows();
    }

    // Charts › Mutual Friends (stored graph snapshot).
    if (/^SELECT friend_id FROM \S+_mutual_graph_friends$/.test(text)) {
        return MUTUAL_LINKS.map(([friendId]) => [friendId]);
    }
    if (/^SELECT friend_id, mutual_id FROM \S+_mutual_graph_links$/.test(text)) {
        return MUTUAL_LINKS.flatMap(([friendId, mutuals]) => mutuals.map((mutualId) => [friendId, mutualId]));
    }
    if (/^SELECT friend_id, last_fetched_at, opted_out FROM \S+_mutual_graph_meta$/.test(text)) {
        return MUTUAL_LINKS.map(([friendId]) => [friendId, iso(2 * DAY), 0]);
    }
    return undefined;
}

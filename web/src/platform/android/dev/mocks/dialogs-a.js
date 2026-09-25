// Preview harness data for the entity dialogs (User, World, Avatar, Group). Dev only, see ../README.md.
//
// - Aurora (usr_...0101) has groups, mutual friends, uploaded worlds, favourite worlds and a represented group.
// - The preview user owns a world (Preview Garden) and two avatars (with a gallery), so the author menus
//   (Change Image, gallery Upload) can be checked, and belongs to the groups (Groups tab edit mode, leave/delete).
// - Harbor Lights (grp_...c001) is owned by the preview user: members, posts, events, a gallery and an instance.
// - Lantern Harbor (wrld_...a001) lists two more instances besides the friends' one.
// All ids are in the made-up usr_00000000-... / wrld_00000000-... ranges; images are generated SVGs.

const ME = 'usr_00000000-0000-4000-8000-000000000001';
const AURORA = 'usr_00000000-0000-4000-8000-000000000101';
const BIRCH = 'usr_00000000-0000-4000-8000-000000000102';
const COBALT = 'usr_00000000-0000-4000-8000-000000000103';
const DUNE = 'usr_00000000-0000-4000-8000-000000000104';
const EMBER = 'usr_00000000-0000-4000-8000-000000000105';
const FJORD = 'usr_00000000-0000-4000-8000-000000000106';

const LANTERN_HARBOR = 'wrld_00000000-0000-4000-8000-00000000a001';
const PREVIEW_GARDEN = 'wrld_00000000-0000-4000-8000-00000000a0f1';
const AURORA_WORLD_1 = 'wrld_00000000-0000-4000-8000-00000000a0a1';
const AURORA_WORLD_2 = 'wrld_00000000-0000-4000-8000-00000000a0a2';

const GROUP_HARBOR = 'grp_00000000-0000-4000-8000-00000000c001';
const GROUP_MOSS = 'grp_00000000-0000-4000-8000-00000000c002';
const GROUP_ROOFTOP = 'grp_00000000-0000-4000-8000-00000000c003';
const GROUP_OWLS = 'grp_00000000-0000-4000-8000-00000000c004';

const AVATAR_CRANE = 'avtr_00000000-0000-4000-8000-00000000e001';
const AVATAR_GLASS = 'avtr_00000000-0000-4000-8000-00000000e002';

const MINUTE = 60 * 1000;
const DAY = 24 * 60 * MINUTE;

function image(color, label = '', width = 256, height = 192) {
    const svg =
        `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">` +
        `<rect width="${width}" height="${height}" fill="${color}"/>` +
        `<text x="${width / 2}" y="${height / 2 + 24}" font-family="sans-serif" font-size="64" font-weight="600" ` +
        `fill="rgba(255,255,255,0.85)" text-anchor="middle">${label}</text></svg>`;
    return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

function daysAgo(days) {
    return new Date(Date.now() - days * DAY).toISOString();
}

function inDays(days, hour = 20) {
    const date = new Date(Date.now() + days * DAY);
    date.setHours(hour, 0, 0, 0);
    return date.toISOString();
}

// ------------------------------------------------------------------ groups

const ROLES = [
    {
        id: 'grol_00000000-0000-4000-8000-00000000c101',
        groupId: GROUP_HARBOR,
        name: 'Owner',
        description: 'Keeps the lights on.',
        isSelfAssignable: false,
        permissions: ['*'],
        isManagementRole: true,
        requiresTwoFactor: false,
        requiresPurchase: false,
        order: 0,
        createdAt: daysAgo(400),
        updatedAt: daysAgo(30)
    },
    {
        id: 'grol_00000000-0000-4000-8000-00000000c102',
        groupId: GROUP_HARBOR,
        name: 'Harbor Crew',
        description: 'Hosts events and welcomes new members.',
        isSelfAssignable: false,
        permissions: ['group-announcement-manage', 'group-calendar-manage', 'group-invites-manage'],
        isManagementRole: false,
        requiresTwoFactor: false,
        requiresPurchase: false,
        order: 1,
        createdAt: daysAgo(380),
        updatedAt: daysAgo(12)
    }
];

const GROUP_BASE = {
    [GROUP_HARBOR]: {
        name: 'Harbor Lights',
        shortCode: 'HARBOR',
        discriminator: '0001',
        color: '#1f6f8b',
        ownerId: ME,
        memberCount: 128,
        onlineMemberCount: 14,
        description: 'A small community that meets at Lantern Harbor every Friday.\nEveryone is welcome.',
        rules: '1. Be kind.\n2. No crashers.\n3. Keep the harbor tidy.',
        joinState: 'request',
        privacy: 'default',
        isVerified: true,
        languages: ['eng', 'jpn'],
        links: ['https://example.invalid/harbor-lights'],
        tags: ['community'],
        roleIds: [ROLES[0].id],
        roles: ROLES,
        galleries: [
            {
                id: 'ggal_00000000-0000-4000-8000-00000000c1a1',
                name: 'Friday nights',
                description: 'Photos from the weekly meetup.',
                membersOnly: false,
                roleIdsToView: null,
                roleIdsToSubmit: null,
                roleIdsToAutoApprove: null,
                roleIdsToManage: null,
                createdAt: daysAgo(300),
                updatedAt: daysAgo(3)
            }
        ]
    },
    [GROUP_MOSS]: {
        name: 'Moss Readers',
        shortCode: 'MOSS',
        discriminator: '0420',
        color: '#4a7c59',
        ownerId: AURORA,
        memberCount: 42,
        onlineMemberCount: 3,
        description: 'A reading circle in the Moss Library.',
        joinState: 'open',
        privacy: 'default',
        languages: ['eng'],
        roleIds: [],
        roles: []
    },
    [GROUP_ROOFTOP]: {
        name: 'Rooftop Runners',
        shortCode: 'ROOF',
        discriminator: '7777',
        color: '#7b3fa0',
        ownerId: DUNE,
        memberCount: 2048,
        onlineMemberCount: 211,
        description: 'Parkour and city worlds.',
        joinState: 'open',
        privacy: 'default',
        languages: ['eng', 'kor'],
        roleIds: [],
        roles: []
    },
    [GROUP_OWLS]: {
        name: 'Night Owls Social Club With A Long Name',
        shortCode: 'OWLS',
        discriminator: '1234',
        color: '#b56576',
        ownerId: FJORD,
        memberCount: 9,
        onlineMemberCount: 1,
        description: 'Late-night hangouts.',
        joinState: 'invite',
        privacy: 'private',
        languages: ['deu'],
        roleIds: [],
        roles: []
    }
};

// Which groups each user is in, and whether the membership is shared with the preview user.
const USER_GROUPS = {
    [ME]: [GROUP_HARBOR, GROUP_MOSS, GROUP_ROOFTOP, GROUP_OWLS],
    [AURORA]: [GROUP_MOSS, GROUP_HARBOR, GROUP_ROOFTOP]
};

function groupIcon(groupId) {
    const base = GROUP_BASE[groupId];
    return image(base.color, base.name.slice(0, 1), 256, 256);
}

function groupBanner(groupId) {
    const base = GROUP_BASE[groupId];
    return image(base.color, '', 680, 240);
}

function myMember(groupId) {
    const base = GROUP_BASE[groupId];
    const isOwner = base.ownerId === ME;
    return {
        id: `gmem_00000000-0000-4000-8000-${groupId.slice(-12)}`,
        groupId,
        userId: ME,
        roleIds: base.roleIds,
        isRepresenting: groupId === GROUP_HARBOR,
        visibility: groupId === GROUP_OWLS ? 'hidden' : 'visible',
        isSubscribedToAnnouncements: true,
        isSubscribedToEventAnnouncements: true,
        joinedAt: daysAgo(groupId === GROUP_HARBOR ? 400 : 120),
        bannedAt: null,
        has2FA: false,
        membershipStatus: 'member',
        permissions: isOwner ? ['*'] : []
    };
}

function buildGroup(groupId) {
    const base = GROUP_BASE[groupId];
    const member = myMember(groupId);
    return {
        id: groupId,
        name: base.name,
        shortCode: base.shortCode,
        discriminator: base.discriminator,
        description: base.description,
        iconUrl: groupIcon(groupId),
        bannerUrl: groupBanner(groupId),
        iconId: null,
        bannerId: null,
        privacy: base.privacy,
        ownerId: base.ownerId,
        rules: base.rules ?? '',
        links: base.links ?? [],
        languages: base.languages ?? [],
        memberCount: base.memberCount,
        memberCountSyncedAt: new Date().toISOString(),
        onlineMemberCount: base.onlineMemberCount,
        isVerified: Boolean(base.isVerified),
        joinState: base.joinState,
        tags: base.tags ?? [],
        transferTargetId: null,
        galleries: base.galleries ?? [],
        createdAt: daysAgo(420),
        updatedAt: daysAgo(2),
        lastPostCreatedAt: daysAgo(1),
        membershipStatus: 'member',
        roles: base.roles,
        myMember: member
    };
}

function buildUserGroup(groupId, userId) {
    const base = GROUP_BASE[groupId];
    return {
        id: `gmem_00000000-0000-4000-8000-${userId.slice(-4)}${groupId.slice(-8)}`,
        groupId,
        name: base.name,
        shortCode: base.shortCode,
        discriminator: base.discriminator,
        description: base.description,
        iconId: null,
        iconUrl: groupIcon(groupId),
        bannerId: null,
        bannerUrl: groupBanner(groupId),
        privacy: base.privacy,
        lastPostCreatedAt: daysAgo(1),
        ownerId: base.ownerId,
        memberCount: base.memberCount,
        groupRoles: [],
        memberVisibility: userId === ME && groupId === GROUP_OWLS ? 'hidden' : 'visible',
        isRepresenting: (userId === ME && groupId === GROUP_HARBOR) || (userId === AURORA && groupId === GROUP_MOSS),
        mutualGroup: userId !== ME && USER_GROUPS[ME].includes(groupId)
    };
}

function representedGroup(userId) {
    const groupId = userId === ME ? GROUP_HARBOR : userId === AURORA ? GROUP_MOSS : null;
    if (!groupId) return {};
    const base = GROUP_BASE[groupId];
    return {
        groupId,
        name: base.name,
        shortCode: base.shortCode,
        discriminator: base.discriminator,
        description: base.description,
        iconId: null,
        iconUrl: groupIcon(groupId),
        bannerId: null,
        bannerUrl: groupBanner(groupId),
        privacy: base.privacy,
        ownerId: base.ownerId,
        memberCount: base.memberCount,
        memberVisibility: 'visible',
        isRepresenting: true
    };
}

function groupMembers(groupId, data) {
    const people = [data.currentUser, ...data.friends];
    return people.map((user, index) => ({
        id: `gmem_00000000-0000-4000-8000-${String(index).padStart(4, '0')}${groupId.slice(-8)}`,
        groupId,
        userId: user.id,
        isRepresenting: index % 3 === 0,
        user: {
            id: user.id,
            displayName: user.displayName,
            thumbnailUrl: user.currentAvatarThumbnailImageUrl,
            iconUrl: '',
            profilePicOverride: '',
            currentAvatarThumbnailImageUrl: user.currentAvatarThumbnailImageUrl
        },
        roleIds: index === 0 ? [ROLES[0].id] : index < 3 ? [ROLES[1].id] : [],
        joinedAt: daysAgo(400 - index * 20),
        membershipStatus: 'member',
        visibility: index === 4 ? 'friends' : 'visible',
        isSubscribedToAnnouncements: index !== 2,
        managerNotes: index === 1 ? 'Runs the Friday quiz.' : ''
    }));
}

function groupPosts(groupId) {
    if (groupId !== GROUP_HARBOR) return [];
    return [
        {
            id: 'gpos_00000000-0000-4000-8000-00000000c201',
            groupId,
            authorId: ME,
            editorId: null,
            visibility: 'group',
            roleIds: [],
            title: 'Friday meetup moves to 21:00',
            text: 'Daylight saving strikes again. See you at the lighthouse!',
            imageId: null,
            imageUrl: image('#1f6f8b', 'F', 256, 256),
            createdAt: daysAgo(1),
            updatedAt: daysAgo(1)
        },
        {
            id: 'gpos_00000000-0000-4000-8000-00000000c202',
            groupId,
            authorId: AURORA,
            editorId: ME,
            visibility: 'group',
            roleIds: [ROLES[1].id],
            title: 'Crew notes',
            text: 'Please check the new welcome script before the next event.',
            imageId: null,
            imageUrl: null,
            createdAt: daysAgo(6),
            updatedAt: daysAgo(4)
        }
    ];
}

function groupEvents(groupId) {
    if (groupId !== GROUP_HARBOR) return [];
    const event = (id, title, startDays, endDays, accessType) => ({
        id,
        ownerId: groupId,
        title,
        description: `${title} at Lantern Harbor.`,
        category: 'hangout',
        accessType,
        platforms: ['standalonewindows', 'android'],
        languages: ['eng'],
        tags: [],
        imageId: null,
        imageUrl: image('#1f6f8b', title.slice(0, 1), 680, 240),
        startsAt: inDays(startDays),
        endsAt: inDays(endDays, 23),
        createdAt: daysAgo(10),
        updatedAt: daysAgo(2),
        isDraft: false,
        featured: false,
        interestedUserCount: 12,
        userInterest: { isFollowing: accessType === 'public' }
    });
    return [
        event('cal_00000000-0000-4000-8000-00000000c301', 'Friday Lanterns', 2, 2, 'public'),
        event('cal_00000000-0000-4000-8000-00000000c302', 'Harbor Quiz Night', 9, 9, 'group'),
        event('cal_00000000-0000-4000-8000-00000000c303', 'Summer Fireworks', -20, -20, 'public')
    ];
}

function groupGallery(galleryId) {
    if (!galleryId.startsWith('ggal_00000000')) return [];
    const colors = ['#1f6f8b', '#4a7c59', '#7b3fa0', '#b56576', '#e8a33c'];
    return colors.map((color, index) => ({
        id: `gimg_00000000-0000-4000-8000-00000000c4${String(index).padStart(2, '0')}`,
        groupId: GROUP_HARBOR,
        galleryId,
        fileId: `file_00000000-0000-4000-8000-00000000c4${String(index).padStart(2, '0')}`,
        imageUrl: image(color, String(index + 1), 480, 270),
        createdAt: daysAgo(index + 1),
        submittedByUserId: ME,
        approved: true,
        approvedByUserId: ME,
        approvedAt: daysAgo(index + 1)
    }));
}

function groupInstances(groupId, data) {
    if (groupId !== GROUP_HARBOR) return [];
    const world = data.worlds.find((w) => w.id === LANTERN_HARBOR);
    const instanceId = `77777~group(${GROUP_HARBOR})~groupAccessType(public)~region(eu)`;
    return [
        {
            instanceId,
            location: `${LANTERN_HARBOR}:${instanceId}`,
            worldId: LANTERN_HARBOR,
            ownerId: GROUP_HARBOR,
            memberCount: 6,
            userCount: 6,
            capacity: 32,
            hasCapacityForYou: true,
            platforms: { standalonewindows: 4, android: 2, ios: 0 },
            world
        }
    ];
}

// ------------------------------------------------------------------ worlds and avatars

function extraWorld(id, name, authorId, authorName, color, occupants) {
    return {
        id,
        name,
        description: `${name}: a preview world.`,
        authorId,
        authorName,
        capacity: 20,
        recommendedCapacity: 10,
        imageUrl: image(color, name.slice(0, 1)),
        thumbnailImageUrl: image(color, name.slice(0, 1)),
        releaseStatus: 'public',
        tags: ['system_approved', 'author_tag_cozy', 'author_tag_chill', 'content_horror'],
        favorites: 321,
        visits: 4567,
        popularity: 5,
        heat: 2,
        occupants,
        publicOccupants: occupants,
        privateOccupants: 0,
        created_at: daysAgo(500),
        updated_at: daysAgo(40),
        publicationDate: daysAgo(480),
        labsPublicationDate: daysAgo(500),
        version: 12,
        previewYoutubeId: null,
        unityPackages: [
            {
                id: `unp_${id.slice(5)}`,
                platform: 'standalonewindows',
                unityVersion: '2022.3.22f1',
                assetVersion: 4,
                created_at: daysAgo(40)
            },
            {
                id: `unp_${id.slice(5)}_q`,
                platform: 'android',
                unityVersion: '2022.3.22f1',
                assetVersion: 4,
                created_at: daysAgo(40)
            }
        ],
        instances: []
    };
}

const EXTRA_WORLDS = {
    [PREVIEW_GARDEN]: () => ({
        ...extraWorld(PREVIEW_GARDEN, 'Preview Garden', ME, 'Preview User', '#3cb371', 3),
        instances: [['30303~region(eu)', 3]]
    }),
    [AURORA_WORLD_1]: () => extraWorld(AURORA_WORLD_1, 'Aurora Skies', AURORA, 'Aurora', '#e8a33c', 9),
    [AURORA_WORLD_2]: () => extraWorld(AURORA_WORLD_2, 'Quiet Pier', AURORA, 'Aurora', '#2f8fb8', 0)
};

function avatar(id, name, color, releaseStatus, days) {
    return {
        id,
        name,
        description: `${name}, made for the preview harness.`,
        authorId: ME,
        authorName: 'Preview User',
        imageUrl: image(color, name.slice(0, 1)),
        thumbnailImageUrl: image(color, name.slice(0, 1)),
        releaseStatus,
        tags: ['author_tag_cute', 'content_sex'],
        styles: { primary: 'anime', secondary: null, supplementary: [] },
        created_at: daysAgo(days + 100),
        updated_at: daysAgo(days),
        version: 7,
        featured: false,
        searchable: false,
        unityPackages: [
            {
                id: `unp_${id.slice(5)}`,
                platform: 'standalonewindows',
                unityVersion: '2022.3.22f1',
                assetVersion: 1,
                variant: 'standard',
                performanceRating: 'Good',
                created_at: daysAgo(days)
            },
            {
                id: `unp_${id.slice(5)}_q`,
                platform: 'android',
                unityVersion: '2022.3.22f1',
                assetVersion: 1,
                variant: 'standard',
                performanceRating: 'Medium',
                created_at: daysAgo(days)
            }
        ]
    };
}

const AVATARS = {
    [AVATAR_CRANE]: () => avatar(AVATAR_CRANE, 'Paper Crane', '#e0c068', 'public', 3),
    [AVATAR_GLASS]: () => avatar(AVATAR_GLASS, 'Glass Fox', '#6c8ebf', 'private', 30)
};

function avatarGallery(avatarId) {
    if (avatarId !== AVATAR_CRANE) return [];
    return ['#e0c068', '#c9a84f', '#b08f3a'].map((color, index) => ({
        id: `file_00000000-0000-4000-8000-00000000e1${String(index).padStart(2, '0')}`,
        name: `Gallery ${index + 1}`,
        ownerId: ME,
        mimeType: 'image/png',
        extension: '.png',
        tags: ['avatargallery'],
        order: index,
        versions: [
            {
                version: 1,
                status: 'complete',
                created_at: daysAgo(index + 2),
                file: { url: image(color, String(index + 1), 480, 360) }
            }
        ]
    }));
}

// ------------------------------------------------------------------ users

const MUTUALS = {
    [AURORA]: [BIRCH, COBALT, DUNE, EMBER, FJORD],
    [EMBER]: [AURORA, BIRCH]
};

function publicProfile(userId, data) {
    const user = userId === ME ? data.currentUser : data.friends.find((friend) => friend.id === userId);
    if (!user) return undefined;
    const badges =
        userId === AURORA || userId === ME
            ? [
                  {
                      badgeId: 'bdg_00000000-0000-4000-8000-00000000f001',
                      badgeName: 'Early Supporter',
                      badgeDescription: 'Was here before the harbor had lights.',
                      badgeImageUrl: image('#e8a33c', '★', 128, 128),
                      assignedAt: daysAgo(700),
                      hidden: false,
                      showcased: true
                  },
                  {
                      badgeId: 'bdg_00000000-0000-4000-8000-00000000f002',
                      badgeName: 'World Builder',
                      badgeDescription: 'Published a world.',
                      badgeImageUrl: image('#1f6f8b', 'W', 128, 128),
                      assignedAt: daysAgo(300),
                      hidden: false,
                      showcased: false
                  }
              ]
            : [];
    return {
        id: user.id,
        displayName: user.displayName,
        bio:
            userId === AURORA
                ? 'Builder of skies and piers.\nFriday nights at Lantern Harbor.\nDMs open for world collabs.'
                : user.bio,
        bioLinks: userId === AURORA ? ['https://example.invalid/aurora', 'https://example.invalid/aurora/worlds'] : [],
        iconUrl: user.currentAvatarThumbnailImageUrl,
        bannerUrl: '',
        bannerType: 'color',
        bannerColor: userId === AURORA ? 'e8a33c' : '5b8def',
        badges,
        isEconomyCreator: userId === AURORA
    };
}

// ------------------------------------------------------------------ hooks

export const fixtures = {};

/**
 * Game log rows for the Previous instances dialogs (normally streamed by the PC companion).
 *
 * @param {string} sql
 * @param {Map<string, unknown>} args
 * @returns {unknown[][] | undefined}
 */
export function sqlite(sql, args) {
    const visits = [
        [2, `${LANTERN_HARBOR}:12345~region(eu)`, 'Lantern Harbor', ''],
        [5, `${LANTERN_HARBOR}:24242~region(us)`, 'Lantern Harbor', ''],
        [
            9,
            `${LANTERN_HARBOR}:77777~group(${GROUP_HARBOR})~groupAccessType(public)~region(eu)`,
            'Lantern Harbor',
            'Harbor Lights'
        ],
        [16, `${PREVIEW_GARDEN}:30303~region(eu)`, 'Preview Garden', '']
    ];
    if (/FROM gamelog_join_leave/.test(sql) && /WHERE user_id = @userId/.test(sql) && args.get('@userId') === AURORA) {
        return visits.map(([days, location, worldName, groupName], index) => {
            const iso = daysAgo(days);
            return [
                iso,
                Date.parse(iso),
                location,
                (index + 1) * 45 * MINUTE,
                worldName,
                groupName,
                900 + index,
                'OnPlayerLeft'
            ];
        });
    }
    if (
        /FROM gamelog_location/.test(sql) &&
        /WHERE world_id = @worldId/.test(sql) &&
        args.get('@worldId') === LANTERN_HARBOR
    ) {
        return visits
            .filter(([, location]) => location.startsWith(LANTERN_HARBOR))
            .map(([days, location, worldName, groupName], index) => [
                daysAgo(days),
                location,
                (index + 2) * 30 * MINUTE,
                worldName,
                groupName
            ]);
    }
    return undefined;
}

/**
 * One page of a list endpoint. The app pages with processBulk until a page comes back empty, so every list must
 * honour offset/n or the preview loops forever.
 *
 * @param {object[]} list
 * @param {URLSearchParams} query
 * @returns {object[]}
 */
function page(list, query) {
    const offset = Number(query.get('offset') ?? 0);
    const n = Number(query.get('n') ?? 100);
    return list.slice(offset, offset + n);
}

/**
 * @param {string} path /api/1/<path> without the prefix
 * @param {URLSearchParams} query
 * @param {string} method
 * @param {object} options
 * @param {{ friends: object[]; worlds: object[]; currentUser: object }} data
 */
export function webApi(path, query, method, options, data) {
    let match;

    // --- users
    match = path.match(/^profile\/(usr_[^/]+)$/);
    if (match && method === 'GET') return publicProfile(match[1], data);

    match = path.match(/^users\/(usr_[^/]+)\/mutuals$/);
    if (match) {
        const friends = MUTUALS[match[1]]?.length ?? 0;
        return { friends, groups: match[1] === AURORA ? 2 : 0 };
    }

    match = path.match(/^users\/(usr_[^/]+)\/mutuals\/friends$/);
    if (match) {
        const ids = MUTUALS[match[1]] ?? [];
        return page(ids.map((id) => data.friends.find((friend) => friend.id === id)).filter(Boolean), query);
    }

    match = path.match(/^users\/(usr_[^/]+)\/groups\/represented$/);
    if (match) return representedGroup(match[1]);

    match = path.match(/^users\/(usr_[^/]+)\/groups$/);
    if (match && USER_GROUPS[match[1]]) {
        return page(
            USER_GROUPS[match[1]].map((groupId) => buildUserGroup(groupId, match[1])),
            query
        );
    }

    // --- groups
    match = path.match(/^users\/usr_[^/]+\/instances\/groups\/(grp_[^/]+)$/);
    if (match && GROUP_BASE[match[1]]) {
        return { instances: groupInstances(match[1], data), fetchedAt: new Date().toISOString() };
    }

    match = path.match(/^groups\/(grp_[^/]+)$/);
    if (match && GROUP_BASE[match[1]] && method === 'GET') return buildGroup(match[1]);

    match = path.match(/^groups\/(grp_[^/]+)\/members$/);
    if (match && GROUP_BASE[match[1]] && method === 'GET') {
        return page(groupMembers(match[1], data), query);
    }

    match = path.match(/^groups\/(grp_[^/]+)\/members\/(usr_[^/]+)$/);
    if (match && GROUP_BASE[match[1]] && method === 'GET') {
        return groupMembers(match[1], data).find((member) => member.userId === match[2]) ?? null;
    }

    match = path.match(/^groups\/(grp_[^/]+)\/posts$/);
    if (match && GROUP_BASE[match[1]] && method === 'GET') {
        const posts = groupPosts(match[1]);
        return { posts: Number(query.get('offset') ?? 0) > 0 ? [] : posts, total: posts.length };
    }

    match = path.match(/^groups\/(grp_[^/]+)\/galleries\/(ggal_[^/]+)$/);
    if (match) return page(groupGallery(match[2]), query);

    match = path.match(/^calendar\/(grp_[^/]+)$/);
    if (match && GROUP_BASE[match[1]]) {
        const results = groupEvents(match[1]);
        return { results, totalCount: results.length, hasNext: false };
    }

    match = path.match(/^calendar\/(grp_[^/]+)\/(cal_[^/]+)$/);
    if (match && method === 'GET') {
        return groupEvents(match[1]).find((event) => event.id === match[2]) ?? null;
    }

    // --- instances the preview user owns (the instance details offer "Close instance")
    match = path.match(/^instances\/(wrld_[^:]+):(.+)$/);
    if (match && method === 'GET' && match[1] === PREVIEW_GARDEN) {
        const instanceId = decodeURIComponent(match[2]);
        const world = EXTRA_WORLDS[PREVIEW_GARDEN]();
        return {
            id: `${PREVIEW_GARDEN}:${instanceId}`,
            location: `${PREVIEW_GARDEN}:${instanceId}`,
            instanceId,
            name: instanceId.split('~')[0],
            worldId: PREVIEW_GARDEN,
            world,
            ownerId: ME,
            type: 'public',
            region: 'eu',
            capacity: world.capacity,
            n_users: 3,
            userCount: 3,
            platforms: { standalonewindows: 2, android: 1, ios: 0 },
            gameServerVersion: 1234,
            active: true,
            full: false,
            hasCapacityForYou: true,
            canRequestInvite: true,
            queueEnabled: true,
            queueSize: 2,
            closedAt: null
        };
    }

    // --- worlds
    match = path.match(/^worlds\/(wrld_[^/]+)$/);
    if (match && method === 'GET') {
        if (EXTRA_WORLDS[match[1]]) return EXTRA_WORLDS[match[1]]();
        if (match[1] === LANTERN_HARBOR) {
            const world = data.worlds.find((w) => w.id === LANTERN_HARBOR);
            return {
                ...world,
                instances: [
                    ['12345~region(eu)', 5],
                    ['24242~region(us)', 11],
                    [`88888~hidden(${AURORA})~region(jp)`, 2]
                ]
            };
        }
    }

    if (path === 'worlds' && method === 'GET') {
        const userId = query.get('userId');
        if (query.get('user') === 'me' || userId === ME) return page([EXTRA_WORLDS[PREVIEW_GARDEN]()], query);
        if (userId === AURORA) return page([EXTRA_WORLDS[AURORA_WORLD_1](), EXTRA_WORLDS[AURORA_WORLD_2]()], query);
        return undefined;
    }

    if (path === 'favorite/groups' && method === 'GET' && query.get('ownerId') === AURORA) {
        if (Number(query.get('offset') ?? 0) > 0) return [];
        return [
            {
                id: 'fvgrp_00000000-0000-4000-8000-00000000b001',
                ownerId: AURORA,
                name: 'worlds1',
                displayName: 'Chill spots',
                type: 'world',
                visibility: 'public',
                tags: ['worlds1']
            },
            {
                id: 'fvgrp_00000000-0000-4000-8000-00000000b002',
                ownerId: AURORA,
                name: 'worlds2',
                displayName: 'Builds I love',
                type: 'world',
                visibility: 'friends',
                tags: ['worlds2']
            }
        ];
    }

    if (path === 'worlds/favorites' && method === 'GET' && query.get('ownerId') === AURORA) {
        const tag = query.get('tag');
        const worlds = tag === 'worlds1' ? data.worlds.slice(0, 3) : [data.worlds[3], EXTRA_WORLDS[PREVIEW_GARDEN]()];
        if (Number(query.get('offset') ?? 0) > 0) return [];
        return worlds.map((world, index) => ({
            ...world,
            favoriteId: `fvrt_00000000-0000-4000-8000-00000000b${tag === 'worlds1' ? 1 : 2}${String(index).padStart(2, '0')}`,
            favoriteGroup: tag
        }));
    }

    // --- avatars
    match = path.match(/^avatars\/(avtr_[^/]+)$/);
    if (match && method === 'GET' && AVATARS[match[1]]) return AVATARS[match[1]]();

    if (path === 'avatars' && method === 'GET' && query.get('user') === 'me') {
        return page(
            Object.values(AVATARS).map((build) => build()),
            query
        );
    }

    if (path === 'files' && method === 'GET' && query.get('tag') === 'avatargallery') {
        return page(avatarGallery(query.get('galleryId')), query);
    }

    return undefined;
}

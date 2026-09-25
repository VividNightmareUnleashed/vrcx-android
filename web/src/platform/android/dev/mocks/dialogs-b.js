// Preview-harness mocks for the dialogs, onboarding, Login and the phone shell. Dev only (see ../README.md).
//
// Query parameters:
//   login=0      start logged out with no saved accounts (the Login page as on a first start)
//   login=saved  start logged out with two saved accounts (the Login page's saved-account column)
// In both modes every `auth/user` request answers 401, so the Login page stays up; the rest of the app is unchanged.
//
// Always on: the invite message slots (SendInviteDialog and its confirm/edit dialogs) and instance short names
// (LaunchDialog's short URL field).

const MINUTE = 60 * 1000;

const params = new URLSearchParams(globalThis.location?.search ?? '');
const loginMode = params.get('login');

/** Config keys the login modes answer themselves (the fake SQLite keeps everything else). */
const LAST_USER_KEY = 'config:lastuserloggedin';
const SAVED_CREDENTIALS_KEY = 'config:savedcredentials';

function placeholderAvatar(color, letter) {
    const svg =
        `<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">` +
        `<rect width="128" height="128" fill="${color}"/>` +
        `<text x="64" y="84" font-family="sans-serif" font-size="56" font-weight="600" fill="rgba(255,255,255,0.85)" text-anchor="middle">${letter}</text>` +
        `</svg>`;
    return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

function savedAccount(id, displayName, username, color, endpoint = '') {
    const image = placeholderAvatar(color, displayName.slice(0, 1));
    return {
        user: {
            id,
            displayName,
            username,
            currentAvatarThumbnailImageUrl: image,
            currentAvatarImageUrl: image,
            profilePicOverride: '',
            userIcon: ''
        },
        loginParams: { username, endpoint, websocket: '' }
    };
}

/**
 * @param {string | null} mode
 * @returns {Map<string, string>} Initial values of the config keys the login mode owns ('' = absent)
 */
export function loginModeConfigs(mode) {
    const configs = new Map([[LAST_USER_KEY, '']]);
    if (mode === 'saved') {
        configs.set(
            SAVED_CREDENTIALS_KEY,
            JSON.stringify({
                'usr_00000000-0000-4000-8000-000000000001': savedAccount(
                    'usr_00000000-0000-4000-8000-000000000001',
                    'Preview User',
                    'previewer',
                    '#5b8def'
                ),
                'usr_00000000-0000-4000-8000-000000000002': savedAccount(
                    'usr_00000000-0000-4000-8000-000000000002',
                    'Second Account With A Long Display Name',
                    'second.account.previewer',
                    '#3cb371',
                    'https://api.example.invalid/api/1'
                )
            })
        );
    } else {
        configs.set(SAVED_CREDENTIALS_KEY, '');
    }
    return configs;
}

function readKey(args) {
    if (!args) return undefined;
    if (args instanceof Map) return args.get('@key');
    return args['@key'];
}

function readValue(args) {
    if (!args) return undefined;
    if (args instanceof Map) return args.get('@value');
    return args['@value'];
}

const UNAUTHORIZED = JSON.stringify({
    status: 401,
    message: JSON.stringify({ error: { message: '"Invalid Username/Email or Password"', status_code: 401 } })
});

/**
 * Wraps the fake interop API so the login modes can answer config reads and `auth/user` before the defaults.
 *
 * @param {{ callDotNetMethod: Function }} api
 * @param {string} mode
 * @returns {{ callDotNetMethod: Function }}
 */
export function wrapInteropForLogin(api, mode) {
    const owned = loginModeConfigs(mode);
    return {
        ...api,
        async callDotNetMethod(className, methodName, args) {
            const list = Array.isArray(args) ? args : [];
            if (className === 'SQLite') {
                const [sql, sqlArgs] = list;
                const key = readKey(sqlArgs);
                if (typeof sql === 'string' && owned.has(key)) {
                    if (/^SELECT value FROM configs WHERE key = @key/i.test(sql)) {
                        const value = owned.get(key);
                        const rows = value ? [[value]] : [];
                        return methodName === 'ExecuteJson' ? JSON.stringify(rows) : rows;
                    }
                    if (/^INSERT OR REPLACE INTO configs/i.test(sql)) {
                        owned.set(key, String(readValue(sqlArgs) ?? ''));
                        return 1;
                    }
                    if (/^DELETE FROM configs WHERE key = @key/i.test(sql)) {
                        owned.set(key, '');
                        return 1;
                    }
                }
            }
            if (className === 'WebApi' && methodName === 'ExecuteJson') {
                try {
                    const url = new URL(JSON.parse(list[0]).url);
                    if (/^\/api\/1\/auth\/user\/?$/.test(url.pathname)) {
                        return UNAUTHORIZED;
                    }
                } catch {
                    // Not a request this mode answers.
                }
            }
            return api.callDotNetMethod(className, methodName, args);
        }
    };
}

// mockBridge assigns window.interopApi after this module has been evaluated; wrap it on assignment.
if ((loginMode === '0' || loginMode === 'saved') && typeof window !== 'undefined') {
    let current;
    Object.defineProperty(window, 'interopApi', {
        configurable: true,
        enumerable: true,
        get: () => current,
        set(value) {
            current = value ? wrapInteropForLogin(value, loginMode) : value;
        }
    });
}

const INVITE_MESSAGES = [
    'Come hang out with us!',
    'Join me, the world is almost full',
    'Want to explore a new world together? We are meeting at the portal in five minutes.',
    'Party time',
    'Quiet chill hangout, no screaming please',
    'Movie night starts soon',
    'Dance session in progress, bring your best moves and your friends',
    'Photo walk',
    'Need one more for the game',
    'Late night talk',
    'Karaoke!',
    'Testing the phone port of VRCX, feedback welcome'
];

/**
 * @param {string} messageType message | response | request | requestResponse
 * @returns {object[]} VRChat invite message slots (GET message/{userId}/{messageType})
 */
export function inviteMessageSlots(messageType) {
    return INVITE_MESSAGES.map((message, slot) => ({
        id: `invm_00000000-0000-4000-8000-${String(slot).padStart(12, '0')}`,
        slot,
        message,
        messageType,
        canBeUpdated: slot % 4 !== 1,
        remainingCooldownMinutes: slot % 4 === 1 ? 42 : 0,
        // Slots 1, 5 and 9 were edited recently, so their one-hour cooldown is still running.
        updatedAt: new Date(Date.now() - (slot % 4 === 1 ? 18 : 24 * 60) * MINUTE).toISOString()
    }));
}

export function webApi(path, query, method) {
    let match = path.match(/^message\/usr_[^/]+\/(message|response|request|requestResponse)(?:\/\d+)?$/);
    if (match) {
        return inviteMessageSlots(match[1]);
    }
    match = path.match(/^instances\/(wrld_[^:]+):(.+)\/shortName$/);
    if (match && method === 'GET') {
        return { shortName: 'prvw1234', secureName: 'prvwsecure' };
    }
    return undefined;
}

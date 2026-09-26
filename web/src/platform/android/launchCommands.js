// Launch commands that only exist on Android (docs/ARCHITECTURE.md §6.9):
// - `search/<text>`: text shared to VRCX from another app (ACTION_SEND), opened like a Direct Access paste;
// - state-changing commands from another app or a browser (`external-launch-command`), run only after the user
//   confirms them in a dialog that says in plain words what was asked.
import { watch } from 'vue';
import { toast } from 'vue-sonner';

import { getAndroidHost, isAndroid, onAndroidEvent } from '../../shared/utils/platform.js';
import { router as appRouter } from '../../plugins/router.js';
import { watchState } from '../../services/watchState.js';

/** Longest shared text put into the Search field. */
export const SHARED_SEARCH_MAX_LENGTH = 256;

/** How long a shared search waits for the post-login redirect before it opens Search anyway. */
const LOGIN_REDIRECT_WAIT_MS = 3000;

const VRCHAT_URL_PATTERN = /\bhttps?:\/\/(?:www\.)?(?:vrchat\.(?:com|cloud)|vrch\.at|vrc\.group)\/[^\s<>"'`)\]]+/i;
const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
// World ids may carry an instance (`wrld_...:12345~region(eu)`), which Direct Access opens as that instance.
const VRCHAT_ID_PATTERN = new RegExp(`\\b(?:wrld_${UUID}(?::[^\\s]+)?|(?:usr|avtr|grp)_${UUID})`, 'i');
const AVATAR_ID_PATTERN = new RegExp(`^avtr_${UUID}$`, 'i');

/**
 * Puts a VRChat link in the form Direct Access expects (`https://vrchat.com/...` without `www.`).
 *
 * @param {string} url
 * @returns {string}
 */
function normalizeVrchatUrl(url) {
    return url.replace(/^https?:\/\/(?:www\.)?/i, 'https://').replace(/[.,;:!?]+$/, '');
}

/**
 * The strings a shared text can be opened with, most specific first: the whole text when it is a single token (an
 * id, a link or a group short code), then the first VRChat link and the first VRChat id found inside longer text
 * ("Look at this world https://vrchat.com/home/world/wrld_...").
 *
 * @param {string} text
 * @returns {string[]}
 */
export function shareCandidates(text) {
    const trimmed = String(text ?? '').trim();
    if (!trimmed) return [];
    const candidates = [];
    const add = (value) => {
        if (value && !candidates.includes(value)) candidates.push(value);
    };
    if (!/\s/.test(trimmed)) {
        add(/^https?:\/\//i.test(trimmed) && VRCHAT_URL_PATTERN.test(trimmed) ? normalizeVrchatUrl(trimmed) : trimmed);
    }
    const url = trimmed.match(VRCHAT_URL_PATTERN);
    if (url) add(normalizeVrchatUrl(url[0]));
    const id = trimmed.match(VRCHAT_ID_PATTERN);
    if (id) add(id[0]);
    return candidates;
}

/**
 * Navigates once the app has left the Login page: right after a cold start the login redirect to the Feed would
 * otherwise replace the page this command opens.
 *
 * @param {import('vue-router').Router} router
 * @param {import('vue-router').RouteLocationRaw} location
 * @param {number} [timeoutMs]
 * @returns {Promise<unknown>}
 */
export function pushAfterLogin(router, location, timeoutMs = LOGIN_REDIRECT_WAIT_MS) {
    if (router.currentRoute?.value?.name !== 'login') {
        return router.push(location);
    }
    return new Promise((resolve) => {
        let timer = null;
        const stop = router.afterEach((to) => {
            if (to.name === 'login') return;
            finish();
        });
        function finish() {
            stop();
            clearTimeout(timer);
            resolve(router.push(location));
        }
        timer = setTimeout(finish, timeoutMs);
    });
}

/**
 * Handles `search/<text>`: opens what Direct Access recognises in the text (a user, world, avatar or group link or
 * id), otherwise the Search page with the text filled in.
 *
 * @param {string} text Everything after `search/` (it may contain slashes)
 * @param {object} deps
 * @param {{ directAccessParse: (input: string) => unknown; setSearchText: (value: string) => void }} deps.searchStore
 * @param {import('vue-router').Router} [deps.router] Defaults to the app router
 * @returns {Promise<'direct' | 'search' | 'empty'>}
 */
export async function handleSearchLaunchCommand(text, { searchStore, router = appRouter }) {
    const trimmed = String(text ?? '').trim();
    if (!trimmed) return 'empty';
    for (const candidate of shareCandidates(trimmed)) {
        try {
            if (searchStore.directAccessParse(candidate)) {
                return 'direct';
            }
        } catch (error) {
            // A malformed link (new URL() throws): try the next candidate, then fall back to Search.
            console.warn('Shared text is not a Direct Access link', error);
        }
    }
    searchStore.setSearchText(trimmed.slice(0, SHARED_SEARCH_MAX_LENGTH));
    await pushAfterLogin(router, { name: 'search' });
    return 'search';
}

// ---------------------------------------------------------------------------------------------------------------
// Commands from other apps (external-launch-command)
// ---------------------------------------------------------------------------------------------------------------

// Commands from other apps can arrive between page start and the moment the app installs its handler (the stores are
// created when the app mounts); they are kept here until then.
const earlyExternalPayloads = [];
let stopEarlyBuffer = isAndroid
    ? onAndroidEvent('external-launch-command', (payload) => earlyExternalPayloads.push(payload))
    : null;

/**
 * Stops buffering and hands out what arrived before the handler was installed.
 *
 * @returns {unknown[]}
 */
export function takeEarlyExternalPayloads() {
    stopEarlyBuffer?.();
    stopEarlyBuffer = null;
    return earlyExternalPayloads.splice(0);
}

/** Commands that only open something; they need no confirmation. */
const NAVIGATION_COMMANDS = new Set(['world', 'avatar', 'user', 'group', 'search']);

const IMPORT_TYPES = new Set(['avatar', 'world', 'friend']);

/** Longest provider URL shown in the confirmation. */
const MAX_SHOWN_URL = 200;

/**
 * @param {string} value
 * @param {number} max
 * @returns {string}
 */
function shorten(value, max) {
    return value.length > max ? `${value.slice(0, max - 1)}…` : value;
}

/**
 * Describes a command from another app for the confirmation dialog, or says why it is not run.
 *
 * @param {string} command Launch command without `vrcx://`
 * @returns {{ kind: 'navigate' }
 *     | { kind: 'confirm'; key: string; params: Record<string, string> }
 *     | { kind: 'invalid' }}
 */
export function describeExternalCommand(command) {
    const input = String(command ?? '').trim();
    const slash = input.indexOf('/');
    const name = slash === -1 ? input : input.slice(0, slash);
    const rest = slash === -1 ? '' : input.slice(slash + 1).trim();
    if (NAVIGATION_COMMANDS.has(name)) {
        return rest ? { kind: 'navigate' } : { kind: 'invalid' };
    }
    switch (name) {
        case 'switchavatar': {
            const avatarId = rest.split('/')[0];
            return AVATAR_ID_PATTERN.test(avatarId)
                ? { kind: 'confirm', key: 'switchavatar', params: { avatar: avatarId } }
                : { kind: 'invalid' };
        }
        case 'addavatardb': {
            // Only web addresses can serve avatar searches.
            return /^https?:\/\/\S+$/i.test(rest)
                ? { kind: 'confirm', key: 'addavatardb', params: { url: shorten(rest, MAX_SHOWN_URL) } }
                : { kind: 'invalid' };
        }
        case 'local-favorite-world':
        case 'local-favorite-avatar': {
            const [id, group] = rest.split('/')[0].split(':');
            const prefix = name === 'local-favorite-world' ? 'wrld_' : 'avtr_';
            if (!id || !group || !id.startsWith(prefix)) return { kind: 'invalid' };
            return {
                kind: 'confirm',
                key: name === 'local-favorite-world' ? 'favorite_world' : 'favorite_avatar',
                params: { id, group: shorten(group, 80) }
            };
        }
        case 'import': {
            const type = rest.split('/')[0];
            const data = rest.slice(type.length + 1);
            if (!IMPORT_TYPES.has(type) || !data.trim()) return { kind: 'invalid' };
            return { kind: 'confirm', key: `import_${type}`, params: {} };
        }
        default:
            return { kind: 'invalid' };
    }
}

/**
 * Asks for, and runs, launch commands that other apps sent (docs/ARCHITECTURE.md §6.9). Commands wait until a
 * VRChat session is logged in, are shown one at a time, and run through the normal launch-command path only after the
 * user allows them. `switchavatar` is always confirmed here, whatever the "confirm avatar switch" setting says.
 *
 * @param {object} deps
 * @param {(command: string, options: { confirmed: boolean }) => unknown} deps.run The app's eventLaunchCommand
 * @param {(options: {
 *     title: string;
 *     description: string;
 *     confirmText: string;
 *     cancelText: string;
 * }) => Promise<{ ok: boolean }>} deps.confirm
 * @param {(key: string, params?: object) => string} deps.t
 * @param {typeof onAndroidEvent} [deps.on]
 * @param {typeof getAndroidHost} [deps.getHost]
 * @param {{ isLoggedIn: boolean }} [deps.state]
 * @param {{ error: (message: string) => void }} [deps.notify]
 * @param {() => unknown[]} [deps.early] Events that arrived before this was installed
 * @returns {{
 *     enqueue: (command: string) => void;
 *     drain: () => Promise<void>;
 *     takePending: () => Promise<void>;
 *     dispose: () => void;
 *     queue: string[];
 * }}
 */
export function installExternalLaunchCommands({
    run,
    confirm,
    t,
    on = onAndroidEvent,
    getHost = getAndroidHost,
    state = watchState,
    notify = toast,
    early = takeEarlyExternalPayloads
}) {
    const queue = [];
    let draining = null;
    /** The command whose dialog is showing. */
    let current = null;

    function enqueue(command) {
        const text = typeof command === 'string' ? command.trim() : '';
        if (!text || text === current || queue.includes(text)) return;
        queue.push(text);
    }

    async function takePending() {
        const host = getHost();
        if (!host) return;
        try {
            const command = await host.TakeExternalLaunchCommand();
            enqueue(command);
        } catch (error) {
            // Older hosts do not have the method yet.
            console.warn('TakeExternalLaunchCommand failed', error);
        }
    }

    async function handle(command) {
        const described = describeExternalCommand(command);
        if (described.kind === 'invalid') {
            console.warn('Ignored launch command from another app:', command);
            notify.error(t('android.launch_command.invalid'));
            return;
        }
        if (described.kind === 'navigate') {
            run(command, { confirmed: false });
            return;
        }
        const request = t(`android.launch_command.${described.key}`, described.params);
        const { ok } = await confirm({
            title: t('android.launch_command.title'),
            description: `${request} ${t('android.launch_command.warning')}`,
            confirmText: t('android.launch_command.allow'),
            cancelText: t('android.launch_command.cancel')
        });
        if (ok && state.isLoggedIn) {
            run(command, { confirmed: true });
        }
    }

    async function processQueue() {
        while (state.isLoggedIn && queue.length) {
            current = queue.shift();
            try {
                await handle(current);
            } catch (error) {
                console.error('Launch command from another app failed', error);
            } finally {
                current = null;
            }
        }
    }

    function drain() {
        if (!draining) {
            draining = processQueue().finally(() => {
                draining = null;
                // A command that arrived while the last one was finishing.
                if (state.isLoggedIn && queue.length) drain();
            });
        }
        return draining;
    }

    function onExternalCommand(payload) {
        if (typeof payload === 'string' && payload.trim()) {
            enqueue(payload);
            drain();
        } else {
            // No payload: the host keeps the command for TakeExternalLaunchCommand.
            takePending().then(drain);
        }
    }

    const off = on('external-launch-command', onExternalCommand);

    const stop = watch(
        () => state.isLoggedIn,
        (isLoggedIn) => {
            if (isLoggedIn) {
                takePending().then(drain);
            } else {
                // A request that was meant for the account that just logged out is not carried over.
                queue.length = 0;
            }
        },
        { immediate: true }
    );

    early().forEach(onExternalCommand);

    return {
        queue,
        enqueue,
        drain,
        takePending,
        dispose() {
            off();
            stop();
        }
    };
}

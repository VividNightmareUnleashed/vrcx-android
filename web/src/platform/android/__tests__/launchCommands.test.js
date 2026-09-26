import { beforeEach, describe, expect, test, vi } from 'vitest';
import { nextTick, reactive } from 'vue';

// Share to VRCX (`search/<text>`) and launch commands from other apps (docs/ARCHITECTURE.md §6.9).
vi.mock('../../../plugins/router.js', () => ({ router: { push: vi.fn(), currentRoute: { value: {} } } }));
vi.mock('vue-sonner', () => ({ toast: Object.assign(vi.fn(), { error: vi.fn(), success: vi.fn() }) }));

import {
    describeExternalCommand,
    handleSearchLaunchCommand,
    installExternalLaunchCommands,
    pushAfterLogin,
    shareCandidates
} from '../launchCommands.js';

const USER_ID = 'usr_00000000-0000-4000-8000-000000000101';
const WORLD_ID = 'wrld_00000000-0000-4000-8000-00000000a001';
const AVATAR_ID = 'avtr_00000000-0000-4000-8000-00000000b001';

/** Direct Access as far as these tests need it: VRChat links and ids open, anything else does not. */
function createSearchStore() {
    return {
        directAccessParse: vi.fn((input) => /^https:\/\/vrchat\.com\/home\/|^(usr|wrld|avtr|grp)_/.test(input)),
        setSearchText: vi.fn()
    };
}

function createRouter(routeName = 'feed') {
    const afterEachHooks = new Set();
    return {
        currentRoute: { value: { name: routeName } },
        push: vi.fn(async () => undefined),
        afterEach: vi.fn((hook) => {
            afterEachHooks.add(hook);
            return () => afterEachHooks.delete(hook);
        }),
        navigate(name) {
            this.currentRoute.value = { name };
            afterEachHooks.forEach((hook) => hook({ name }));
        }
    };
}

describe('shareCandidates', () => {
    test('a single token is tried as it is', () => {
        expect(shareCandidates(`  ${USER_ID}  `)).toEqual([USER_ID]);
        expect(shareCandidates('ABCD.1234')).toEqual(['ABCD.1234']);
    });

    test('a VRChat link is normalised for Direct Access, with its id as the fallback', () => {
        expect(shareCandidates(`https://www.vrchat.com/home/user/${USER_ID}`)).toEqual([
            `https://vrchat.com/home/user/${USER_ID}`,
            USER_ID
        ]);
    });

    test('links and ids are found inside longer text', () => {
        expect(shareCandidates(`Look at this world! https://vrchat.com/home/world/${WORLD_ID}.`)).toEqual([
            `https://vrchat.com/home/world/${WORLD_ID}`,
            WORLD_ID
        ]);
        expect(shareCandidates(`join ${WORLD_ID}:12345~region(eu) now`)).toEqual([`${WORLD_ID}:12345~region(eu)`]);
    });

    test('free text gives no candidate except itself when it is one word', () => {
        expect(shareCandidates('Aurora and friends')).toEqual([]);
        expect(shareCandidates('')).toEqual([]);
    });
});

describe('handleSearchLaunchCommand', () => {
    let searchStore;
    let router;

    beforeEach(() => {
        searchStore = createSearchStore();
        router = createRouter();
    });

    test('opens a shared VRChat URL through Direct Access, slashes included', async () => {
        const url = `https://vrchat.com/home/user/${USER_ID}`;
        await expect(handleSearchLaunchCommand(url, { searchStore, router })).resolves.toBe('direct');
        expect(searchStore.directAccessParse).toHaveBeenCalledWith(url);
        expect(router.push).not.toHaveBeenCalled();
    });

    test('opens a bare id', async () => {
        await expect(handleSearchLaunchCommand(AVATAR_ID, { searchStore, router })).resolves.toBe('direct');
        expect(searchStore.directAccessParse).toHaveBeenCalledWith(AVATAR_ID);
    });

    test('opens a link inside shared text', async () => {
        const text = `Check this out https://vrchat.com/home/world/${WORLD_ID}`;
        await expect(handleSearchLaunchCommand(text, { searchStore, router })).resolves.toBe('direct');
        expect(searchStore.directAccessParse).toHaveBeenCalledWith(`https://vrchat.com/home/world/${WORLD_ID}`);
    });

    test('opens Search with free text filled in', async () => {
        await expect(handleSearchLaunchCommand('  Aurora  ', { searchStore, router })).resolves.toBe('search');
        expect(searchStore.setSearchText).toHaveBeenCalledWith('Aurora');
        expect(router.push).toHaveBeenCalledWith({ name: 'search' });
    });

    test('a link Direct Access cannot parse falls back to Search', async () => {
        searchStore.directAccessParse.mockImplementation(() => {
            throw new TypeError('Invalid URL');
        });
        const text = 'https://vrchat.com/home/%%%';
        await expect(handleSearchLaunchCommand(text, { searchStore, router })).resolves.toBe('search');
        expect(searchStore.setSearchText).toHaveBeenCalledWith(text);
    });

    test('long text is shortened for the search field, empty text is ignored', async () => {
        await handleSearchLaunchCommand('word '.repeat(200), { searchStore, router });
        expect(searchStore.setSearchText.mock.calls[0][0].length).toBe(256);

        await expect(handleSearchLaunchCommand('   ', { searchStore, router })).resolves.toBe('empty');
    });
});

describe('pushAfterLogin', () => {
    test('waits for the login redirect before opening the page', async () => {
        const router = createRouter('login');
        const done = pushAfterLogin(router, { name: 'search' }, 5000);
        expect(router.push).not.toHaveBeenCalled();
        router.navigate('login');
        expect(router.push).not.toHaveBeenCalled();
        router.navigate('feed');
        await done;
        expect(router.push).toHaveBeenCalledWith({ name: 'search' });
    });

    test('gives up waiting after the timeout', async () => {
        vi.useFakeTimers();
        const router = createRouter('login');
        const done = pushAfterLogin(router, { name: 'search' }, 100);
        vi.advanceTimersByTime(100);
        await done;
        expect(router.push).toHaveBeenCalledTimes(1);
        vi.useRealTimers();
    });
});

describe('describeExternalCommand', () => {
    test('navigation commands need no confirmation', () => {
        expect(describeExternalCommand(`world/${WORLD_ID}`)).toEqual({ kind: 'navigate' });
        expect(describeExternalCommand(`user/${USER_ID}`)).toEqual({ kind: 'navigate' });
        expect(describeExternalCommand('search/hello')).toEqual({ kind: 'navigate' });
    });

    test('says what a state-changing command does', () => {
        expect(describeExternalCommand(`switchavatar/${AVATAR_ID}`)).toEqual({
            kind: 'confirm',
            key: 'switchavatar',
            params: { avatar: AVATAR_ID }
        });
        expect(describeExternalCommand('addavatardb/https://avatars.example.invalid/search')).toEqual({
            kind: 'confirm',
            key: 'addavatardb',
            params: { url: 'https://avatars.example.invalid/search' }
        });
        expect(describeExternalCommand(`local-favorite-world/${WORLD_ID}:Chill`)).toEqual({
            kind: 'confirm',
            key: 'favorite_world',
            params: { id: WORLD_ID, group: 'Chill' }
        });
        expect(describeExternalCommand(`local-favorite-avatar/${AVATAR_ID}:Mine`)).toMatchObject({
            key: 'favorite_avatar'
        });
        expect(describeExternalCommand(`import/avatar/${AVATAR_ID}`)).toEqual({
            kind: 'confirm',
            key: 'import_avatar',
            params: {}
        });
    });

    test('rejects malformed and unknown commands, including crash/', () => {
        for (const command of [
            'switchavatar/avtr_bad',
            'addavatardb/javascript:alert(1)',
            `local-favorite-world/${AVATAR_ID}:Group`,
            `local-favorite-avatar/${AVATAR_ID}`,
            'import/other/x',
            'import/avatar/',
            'crash/Browser crashed.',
            'world/',
            'something'
        ]) {
            expect(describeExternalCommand(command), command).toEqual({ kind: 'invalid' });
        }
    });
});

describe('installExternalLaunchCommands', () => {
    let state;
    let events;
    let host;
    let run;
    let confirm;
    let notify;

    const t = (key, params) => (params && Object.keys(params).length ? `${key} ${JSON.stringify(params)}` : key);
    const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

    function install() {
        return installExternalLaunchCommands({
            run,
            confirm,
            t,
            state,
            notify,
            on: (name, handler) => {
                events.set(name, handler);
                return () => events.delete(name);
            },
            getHost: () => host
        });
    }

    beforeEach(() => {
        state = reactive({ isLoggedIn: false });
        events = new Map();
        host = { TakeExternalLaunchCommand: vi.fn(async () => '') };
        run = vi.fn();
        confirm = vi.fn(async () => ({ ok: true }));
        notify = { error: vi.fn() };
    });

    test('asks in plain words, then runs the command as confirmed', async () => {
        state.isLoggedIn = true;
        install();
        events.get('external-launch-command')(`switchavatar/${AVATAR_ID}`);
        await flush();

        expect(confirm).toHaveBeenCalledTimes(1);
        const options = confirm.mock.calls[0][0];
        expect(options.title).toBe('android.launch_command.title');
        expect(options.description).toContain('android.launch_command.switchavatar');
        expect(options.description).toContain(AVATAR_ID);
        expect(options.description).toContain('android.launch_command.warning');
        expect(run).toHaveBeenCalledWith(`switchavatar/${AVATAR_ID}`, { confirmed: true });
    });

    test('does nothing when the user does not allow it', async () => {
        confirm.mockResolvedValue({ ok: false });
        state.isLoggedIn = true;
        install();
        events.get('external-launch-command')('addavatardb/https://avatars.example.invalid');
        await flush();
        expect(confirm).toHaveBeenCalled();
        expect(run).not.toHaveBeenCalled();
    });

    test('waits for login, then also takes the command held since a cold start', async () => {
        host.TakeExternalLaunchCommand.mockResolvedValueOnce(`local-favorite-world/${WORLD_ID}:Chill`);
        install();
        events.get('external-launch-command')(`import/world/${WORLD_ID}`);
        await flush();
        expect(confirm).not.toHaveBeenCalled();

        state.isLoggedIn = true;
        await nextTick();
        await flush();
        expect(host.TakeExternalLaunchCommand).toHaveBeenCalledTimes(1);
        expect(run.mock.calls.map(([command]) => command)).toEqual([
            `import/world/${WORLD_ID}`,
            `local-favorite-world/${WORLD_ID}:Chill`
        ]);
    });

    test('shows one request at a time', async () => {
        let answer;
        confirm.mockImplementation(() => new Promise((resolve) => (answer = resolve)));
        state.isLoggedIn = true;
        install();
        events.get('external-launch-command')(`switchavatar/${AVATAR_ID}`);
        events.get('external-launch-command')('addavatardb/https://avatars.example.invalid');
        await flush();
        expect(confirm).toHaveBeenCalledTimes(1);

        answer({ ok: false });
        await flush();
        expect(confirm).toHaveBeenCalledTimes(2);
    });

    test('an event without payload takes the command from the host', async () => {
        state.isLoggedIn = true;
        install();
        await flush();
        host.TakeExternalLaunchCommand.mockResolvedValueOnce(`switchavatar/${AVATAR_ID}`);
        events.get('external-launch-command')(null);
        await flush();
        expect(run).toHaveBeenCalledWith(`switchavatar/${AVATAR_ID}`, { confirmed: true });
    });

    test('navigation commands run without a dialog; invalid ones are reported and dropped', async () => {
        state.isLoggedIn = true;
        install();
        events.get('external-launch-command')(`user/${USER_ID}`);
        events.get('external-launch-command')('crash/Browser crashed.');
        await flush();
        expect(run).toHaveBeenCalledWith(`user/${USER_ID}`, { confirmed: false });
        expect(run).toHaveBeenCalledTimes(1);
        expect(confirm).not.toHaveBeenCalled();
        expect(notify.error).toHaveBeenCalledWith('android.launch_command.invalid');
    });

    test('a logout drops requests that were waiting', async () => {
        confirm.mockImplementation(() => new Promise(() => {}));
        state.isLoggedIn = true;
        const controller = install();
        events.get('external-launch-command')(`switchavatar/${AVATAR_ID}`);
        events.get('external-launch-command')('addavatardb/https://avatars.example.invalid');
        await flush();
        expect(controller.queue).toEqual(['addavatardb/https://avatars.example.invalid']);

        state.isLoggedIn = false;
        await flush();
        expect(controller.queue).toEqual([]);
    });

    test('commands that came before the handler was installed are not lost', async () => {
        state.isLoggedIn = true;
        installExternalLaunchCommands({
            run,
            confirm,
            t,
            state,
            notify,
            on: () => () => {},
            getHost: () => host,
            early: () => [`switchavatar/${AVATAR_ID}`]
        });
        await flush();
        expect(run).toHaveBeenCalledWith(`switchavatar/${AVATAR_ID}`, { confirmed: true });
    });

    test('an old host without TakeExternalLaunchCommand is tolerated', async () => {
        host.TakeExternalLaunchCommand.mockRejectedValue(new Error('MissingMethodException: no'));
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
        state.isLoggedIn = true;
        install();
        await flush();
        expect(run).not.toHaveBeenCalled();
        warn.mockRestore();
    });
});

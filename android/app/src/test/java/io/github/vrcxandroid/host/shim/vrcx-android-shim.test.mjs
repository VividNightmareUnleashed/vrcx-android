// Tests for android/app/src/main/assets/shim/vrcx-android-shim.js, run in a node:vm sandbox that fakes the few
// browser APIs the shim touches. Run with `node --test` (ShimJsTest.kt does this from `gradlew testDebugUnitTest`).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const here = dirname(fileURLToPath(import.meta.url));
const shimPath = resolve(here, '../../../../../../../main/assets/shim/vrcx-android-shim.js');
const shimSource = readFileSync(shimPath, 'utf8');

const tick = () => new Promise((r) => setImmediate(r));
/** Copy of a value from the vm realm, so deepStrictEqual does not trip over foreign prototypes. */
const plain = (v) => (v === undefined ? v : JSON.parse(JSON.stringify(v)));

function createEnv({ nativePresent = true } = {}) {
    const posted = [];
    const nativeListeners = [];
    const native = {
        postMessage(text) {
            posted.push(JSON.parse(text));
        },
        addEventListener(type, fn) {
            if (type === 'message') nativeListeners.push(fn);
        }
    };

    const clock = { now: 1_000_000 };
    const styleProps = new Map();
    const classes = new Set();
    const documentElement = {
        classList: { add: (c) => classes.add(c), contains: (c) => classes.has(c) },
        style: {
            setProperty: (k, v) => styleProps.set(k, v),
            getPropertyValue: (k) => styleProps.get(k) || ''
        }
    };

    class FakeAnchor {
        constructor() {
            this.attrs = new Map();
            this.href = '';
            this.defaultClicks = 0;
        }
        setAttribute(k, v) {
            this.attrs.set(k, String(v));
            if (k === 'href') this.href = String(v);
        }
        getAttribute(k) {
            return this.attrs.has(k) ? this.attrs.get(k) : null;
        }
        hasAttribute(k) {
            return this.attrs.has(k);
        }
        closest(selector) {
            return selector === 'a[download]' && this.hasAttribute('download') ? this : null;
        }
    }
    FakeAnchor.prototype.click = function () {
        this.defaultClicks++;
    };

    const objectUrls = new Map();
    let urlSeq = 0;
    class FakeURL extends URL {}
    FakeURL.createObjectURL = (blob) => {
        const url = `blob:https://appassets.androidplatform.net/${++urlSeq}`;
        objectUrls.set(url, blob);
        return url;
    };
    FakeURL.revokeObjectURL = (url) => objectUrls.delete(url);

    const sockets = [];
    class FakeWebSocket extends EventTarget {
        constructor(url) {
            super();
            this.url = url;
            this.readyState = 1;
            this.closed = false;
            sockets.push(this);
        }
        close() {
            this.closed = true;
            this.readyState = 3;
        }
    }
    FakeWebSocket.OPEN = 1;

    class FakeCloseEvent extends Event {
        constructor(type, init = {}) {
            super(type);
            this.code = init.code;
            this.reason = init.reason;
        }
    }

    const sandbox = {
        console: { log() {}, warn() {}, error() {} },
        setTimeout: (fn, ms) => setTimeout(fn, Math.min(ms || 0, 5)),
        clearTimeout,
        requestAnimationFrame: (fn) => setTimeout(fn, 0),
        queueMicrotask,
        Promise,
        Map,
        Set,
        JSON,
        Object,
        Array,
        Number,
        String,
        Error,
        TypeError,
        RegExp,
        Math,
        Date: { now: () => clock.now },
        EventTarget,
        Event,
        CustomEvent,
        MutationObserver: class {
            observe() {}
            disconnect() {}
        },
        DOMException,
        Blob,
        TextEncoder,
        btoa,
        atob,
        fetch: async () => {
            throw new Error('no fetch in tests');
        },
        URL: FakeURL,
        HTMLAnchorElement: FakeAnchor,
        WebSocket: FakeWebSocket,
        CloseEvent: FakeCloseEvent,
        location: { href: 'https://appassets.androidplatform.net/assets/web/index.html' },
        navigator: {},
        document: { documentElement, activeElement: null, body: {} },
        __vrcxBridgeConfig: { arch: 'x64', vrcxVersion: '2026.09.16', sdkInt: 35 }
    };
    if (nativePresent) sandbox.VRCXNative = native;
    // window-level event target
    const windowEvents = new EventTarget();
    sandbox.addEventListener = windowEvents.addEventListener.bind(windowEvents);
    sandbox.removeEventListener = windowEvents.removeEventListener.bind(windowEvents);
    sandbox.dispatchEvent = windowEvents.dispatchEvent.bind(windowEvents);
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(shimSource, sandbox, { filename: 'vrcx-android-shim.js' });

    const env = {
        win: sandbox,
        posted,
        clock,
        styleProps,
        classes,
        sockets,
        objectUrls,
        FakeAnchor,
        /** Delivers a native message (reply or event) to the shim. */
        deliver(message) {
            for (const fn of nativeListeners) fn({ data: JSON.stringify(message) });
        },
        event(name, data = null) {
            env.deliver({ ev: name, d: data });
        },
        /** Last posted call (not the hello). */
        lastCall() {
            return posted.filter((m) => m.t !== 'hello').at(-1);
        },
        calls() {
            return posted.filter((m) => m.t !== 'hello');
        },
        reply(call, result) {
            env.deliver({ id: call.id, ok: true, r: result });
        },
        fail(call, error) {
            env.deliver({ id: call.id, ok: false, e: error });
        }
    };
    return env;
}

test('defines read-only platform globals and the html class', () => {
    const { win, classes } = createEnv();
    assert.equal(win.WINDOWS, false);
    assert.equal(win.LINUX, true);
    assert.equal(win.ANDROID, true);
    assert.throws(() => {
        win.LINUX = false;
    }, TypeError);
    assert.equal(win.LINUX, true);
    assert.ok(classes.has('is-android'));
    assert.equal(win.__vrcxBridgeConfig, undefined);
    assert.equal(win.__vrcxAndroid.config.arch, 'x64');
    assert.throws(() => {
        win.interopApi.callDotNetMethod = null;
    }, TypeError);
});

test('says hello first so native can replay state events', () => {
    const { posted } = createEnv();
    assert.equal(posted[0].t, 'hello');
    assert.match(posted[0].href, /index\.html$/);
});

test('callDotNetMethod encodes Map, Set, undefined and BigInt and resolves replies', async () => {
    const env = createEnv();
    const p = env.win.interopApi.callDotNetMethod('SQLite', 'ExecuteJson', [
        'SELECT * FROM t WHERE a = @a',
        new Map([
            ['@a', 1],
            ['@b', undefined]
        ]),
        new Set(['x', 'y']),
        undefined,
        10n
    ]);
    const call = env.lastCall();
    assert.equal(call.c, 'SQLite');
    assert.equal(call.m, 'ExecuteJson');
    assert.deepEqual(call.a, ['SELECT * FROM t WHERE a = @a', { '@a': 1, '@b': null }, ['x', 'y'], null, '10']);
    env.reply(call, '[[1,"a"]]');
    assert.equal(await p, '[[1,"a"]]');
});

test('rejections carry the native error text', async () => {
    const env = createEnv();
    const p = env.win.interopApi.callDotNetMethod('Nope', 'Nothing', []);
    env.fail(env.lastCall(), 'MissingMethodException: Method Nothing does not exist on class Nope');
    await assert.rejects(p, (e) => e instanceof Error && e.message === 'MissingMethodException: Method Nothing does not exist on class Nope');
});

test('PC-only calls resolve locally without crossing the bridge', async () => {
    const env = createEnv();
    const api = env.win.interopApi;
    for (const m of ['ExecuteVrOverlayFunction', 'SetVR', 'XSNotification', 'OVRTNotification', 'SendIpc', 'IPCAnnounceStart', 'SetUserAgent']) {
        assert.equal(await api.callDotNetMethod('AppApiElectron', m, ['x']), undefined);
    }
    assert.equal(await api.callDotNetMethod('Discord', 'SetAssets', []), undefined);
    assert.equal(await api.callDotNetMethod('Discord', 'SetActive', [true]), false);
    assert.equal(await env.win.electron.updateVr(true, true, false, false, 0), undefined);
    assert.equal(env.calls().length, 0);
});

test('GetLogLines only crosses the bridge after log-available', async () => {
    const env = createEnv();
    const api = env.win.interopApi;
    const first = api.callDotNetMethod('LogWatcher', 'GetLogLines', []);
    assert.equal(env.calls().length, 1);
    env.reply(env.lastCall(), ['["2026-09-25","location"]']);
    assert.deepEqual(plain(await first), ['["2026-09-25","location"]']);

    assert.deepEqual(plain(await api.callDotNetMethod('LogWatcher', 'GetLogLines', [])), []);
    assert.deepEqual(plain(await api.callDotNetMethod('LogWatcher', 'GetLogLines', [])), []);
    assert.equal(env.calls().length, 1);

    env.event('log-available');
    const again = api.callDotNetMethod('LogWatcher', 'GetLogLines', []);
    assert.equal(env.calls().length, 2);
    env.reply(env.lastCall(), []);
    assert.deepEqual(plain(await again), []);

    // A safety re-check after 30 s even without an event.
    env.clock.now += 31_000;
    api.callDotNetMethod('LogWatcher', 'GetLogLines', []);
    assert.equal(env.calls().length, 3);
});

test('a failed GetLogLines is retried on the next tick', async () => {
    const env = createEnv();
    const api = env.win.interopApi;
    const p = api.callDotNetMethod('LogWatcher', 'GetLogLines', []);
    env.fail(env.lastCall(), 'IOException: boom');
    await assert.rejects(p);
    api.callDotNetMethod('LogWatcher', 'GetLogLines', []);
    assert.equal(env.calls().length, 2);
});

test('game state is cached until game-state', async () => {
    const env = createEnv();
    const api = env.win.interopApi;
    const a = api.callDotNetMethod('AppApiElectron', 'IsGameRunning', []);
    env.reply(env.lastCall(), true);
    assert.equal(await a, true);
    const b = api.callDotNetMethod('AppApiElectron', 'IsSteamVRRunning', []);
    env.reply(env.lastCall(), false);
    assert.equal(await b, false);
    assert.equal(env.calls().length, 2);

    assert.equal(await api.callDotNetMethod('AppApiElectron', 'IsGameRunning', []), true);
    assert.equal(await api.callDotNetMethod('AppApiElectron', 'IsSteamVRRunning', []), false);
    assert.equal(env.calls().length, 2);

    env.event('game-state', { isGameRunning: false, isSteamVRRunning: false });
    const c = api.callDotNetMethod('AppApiElectron', 'IsGameRunning', []);
    assert.equal(env.calls().length, 3);
    env.reply(env.lastCall(), false);
    assert.equal(await c, false);
});

test('window.electron follows the preload contract', async () => {
    const env = createEnv();
    const electron = env.win.electron;
    assert.equal(await electron.getArch(), 'x64');
    assert.equal(await electron.getNoUpdater(), true);
    assert.equal(await electron.getOverlayWindow(), false);
    assert.equal(electron.ipcRenderer.on('something-else', () => {}), undefined);

    const clip = electron.getClipboardText();
    assert.equal(env.lastCall().c, 'AndroidHost');
    assert.equal(env.lastCall().m, 'ElectronGetClipboardText');
    env.reply(env.lastCall(), null);
    assert.equal(await clip, '');

    const file = electron.openFileDialog();
    assert.equal(env.lastCall().m, 'ElectronOpenFileDialog');
    env.reply(env.lastCall(), '');
    assert.equal(await file, '');

    const dir = electron.openDirectoryDialog();
    assert.equal(env.lastCall().m, 'ElectronOpenDirectoryDialog');
    env.reply(env.lastCall(), null);
    assert.equal(await dir, null);

    const note = electron.desktopNotification('Title', 'Body', undefined);
    assert.deepEqual(env.lastCall().a, ['Title', 'Body', '']);
    assert.equal(env.lastCall().m, 'ElectronDesktopNotification');
    env.reply(env.lastCall(), null);
    assert.equal(await note, undefined);

    electron.setTrayIconNotification(true);
    assert.deepEqual(env.lastCall(), { id: env.lastCall().id, c: 'AndroidHost', m: 'ElectronSetTrayIconNotification', a: [true] });

    electron.restartApp();
    assert.equal(env.lastCall().m, 'ElectronRestartApp');

    const unsubscribe = electron.onWindowSizeChanged(() => {});
    assert.equal(typeof unsubscribe, 'function');
    unsubscribe();
});

test('launch commands reach the ipcRenderer listener, including ones that arrive before it registers', async () => {
    const env = createEnv();
    env.event('launch-command', 'world/wrld_1');
    const received = [];
    env.win.electron.ipcRenderer.on('launch-command', (cmd) => received.push(cmd));
    await new Promise((r) => setTimeout(r, 10));
    assert.deepEqual(received, ['world/wrld_1']);
    env.event('launch-command', 'user/usr_2');
    assert.deepEqual(received, ['world/wrld_1', 'user/usr_2']);

    // One managed listener per channel: a new registration replaces the old one.
    const second = [];
    const off = env.win.electron.ipcRenderer.on('launch-command', (cmd) => second.push(cmd));
    env.event('launch-command', 'group/grp_3');
    assert.deepEqual(received, ['world/wrld_1', 'user/usr_2']);
    assert.deepEqual(second, ['group/grp_3']);
    off();
});

test('an external launch command that arrives before the page subscribes goes to the first subscriber only', async () => {
    const env = createEnv();
    env.event('external-launch-command', 'switchavatar/avtr_1');
    env.event('external-launch-command', 'addavatardb/https://example.com');
    const first = [];
    const second = [];
    env.win.__vrcxAndroid.on('external-launch-command', (cmd) => first.push(cmd));
    env.win.__vrcxAndroid.on('external-launch-command', (cmd) => second.push(cmd));
    await new Promise((r) => setTimeout(r, 10));
    // The last one wins, like the native pending slot.
    assert.deepEqual(first, ['addavatardb/https://example.com']);
    assert.deepEqual(second, []);
    env.event('external-launch-command', 'import/avatar/avtr_2');
    assert.deepEqual(first, ['addavatardb/https://example.com', 'import/avatar/avtr_2']);
    assert.deepEqual(second, ['import/avatar/avtr_2']);
});

test('other events are not buffered for later subscribers', async () => {
    const env = createEnv();
    env.event('network-changed', { available: true });
    const received = [];
    env.win.__vrcxAndroid.on('network-changed', (d) => received.push(d));
    await new Promise((r) => setTimeout(r, 10));
    assert.deepEqual(received, []);
});

test('focus fires onBrowserFocus with a null event', () => {
    const env = createEnv();
    const calls = [];
    env.win.electron.onBrowserFocus((event) => calls.push(event));
    env.event('focus');
    assert.deepEqual(calls, [null]);
});

test('events reach __vrcxAndroid.on subscribers and window CustomEvents', () => {
    const env = createEnv();
    const seen = [];
    const off = env.win.__vrcxAndroid.on('companion-state', (d) => seen.push(d.status));
    const custom = [];
    env.win.addEventListener('vrcx-android:companion-state', (e) => custom.push(e.detail.status));
    env.event('companion-state', { status: 'connected' });
    off();
    env.event('companion-state', { status: 'idle' });
    assert.deepEqual(seen, ['connected']);
    assert.deepEqual(custom, ['connected', 'idle']);
});

test('a throwing subscriber does not break the others', () => {
    const env = createEnv();
    const seen = [];
    env.win.__vrcxAndroid.on('focus', () => {
        throw new Error('bad');
    });
    env.win.__vrcxAndroid.on('focus', () => seen.push('ok'));
    env.event('focus');
    assert.deepEqual(seen, ['ok']);
});

test('handleBack delegates to backHandler', () => {
    const { win } = createEnv();
    const android = win.__vrcxAndroid;
    assert.equal(android.handleBack(), false);
    android.backHandler = () => 1;
    assert.equal(android.handleBack(), true);
    android.backHandler = () => 0;
    assert.equal(android.handleBack(), false);
    android.backHandler = () => {
        throw new Error('x');
    };
    assert.equal(android.handleBack(), false);
    android.backHandler = null;
    assert.equal(android.handleBack(), false);
});

test('insets become CSS variables', () => {
    const env = createEnv();
    assert.equal(env.styleProps.get('--safe-top'), '0px');
    env.event('insets', { top: 24, right: 0, bottom: 48, left: 1.5, imeBottom: 0 });
    assert.equal(env.styleProps.get('--safe-top'), '24px');
    assert.equal(env.styleProps.get('--safe-bottom'), '48px');
    assert.equal(env.styleProps.get('--safe-left'), '1.5px');
    assert.equal(env.styleProps.get('--ime-bottom'), '0px');
    env.event('insets', { top: 24, right: 0, bottom: 48, left: 0, imeBottom: 300 });
    assert.equal(env.styleProps.get('--ime-bottom'), '300px');
    assert.equal(env.win.__vrcxAndroid.insets.imeBottom, 300);
    // The `ime` spelling (phone-shell preview bridge) is accepted too.
    env.event('insets', { top: 24, right: 0, bottom: 48, left: 0, ime: 120 });
    assert.equal(env.styleProps.get('--ime-bottom'), '120px');
    env.event('insets', { top: 24, right: 0, bottom: 48, left: 0 });
    assert.equal(env.styleProps.get('--ime-bottom'), '0px');
});

test('speechSynthesis polyfill: voices, ordering, speak and progress events', async () => {
    const env = createEnv();
    const synth = env.win.speechSynthesis;
    assert.deepEqual(Array.from(synth.getVoices()), []);
    assert.equal(env.lastCall().m, 'TtsGetVoices');

    let changed = 0;
    synth.onvoiceschanged = () => changed++;
    env.event('tts-voices', [
        { name: 'de-1', lang: 'de-DE', voiceURI: 'de-1', default: false, localService: true },
        { name: 'en-us-1', lang: 'en-US', voiceURI: 'en-us-1', default: true, localService: true },
        { name: 'en-us-2', lang: 'en-US', voiceURI: 'en-us-2', default: false, localService: false }
    ]);
    assert.equal(changed, 1);
    const voices = synth.getVoices();
    assert.deepEqual(
        plain(voices.map((v) => v.name)),
        ['en-us-1', 'de-1', 'en-us-2']
    );
    assert.equal(voices[0].default, true);
    assert.equal(voices[2].localService, false);

    const u = new env.win.SpeechSynthesisUtterance('hello');
    u.voice = voices[0];
    u.rate = 1.5;
    const events = [];
    u.onstart = () => events.push('start');
    u.addEventListener('end', () => events.push('end'));
    synth.cancel();
    synth.speak(u);
    await tick();
    await tick();
    const ttsCalls = env.calls().filter((c) => c.m === 'TtsCancel' || c.m === 'TtsSpeak');
    assert.deepEqual(
        ttsCalls.map((c) => c.m),
        ['TtsCancel']
    );
    // TtsSpeak waits for TtsCancel to settle, so the two keep their order on the concurrent AndroidHost lane.
    env.reply(ttsCalls[0], null);
    await tick();
    await tick();
    const speak = env.lastCall();
    assert.equal(speak.m, 'TtsSpeak');
    assert.equal(speak.a[0].text, 'hello');
    assert.equal(speak.a[0].voiceURI, 'en-us-1');
    assert.equal(speak.a[0].lang, 'en-US');
    assert.equal(speak.a[0].rate, 1.5);
    env.reply(speak, null);
    assert.equal(synth.pending, true);
    env.event('tts-event', { id: speak.a[0].id, type: 'start' });
    assert.equal(synth.speaking, true);
    env.event('tts-event', { id: speak.a[0].id, type: 'end' });
    assert.equal(synth.speaking, false);
    assert.deepEqual(events, ['start', 'end']);
});

test('speechSynthesis asks native again while the voice list is empty', async () => {
    const env = createEnv();
    const synth = env.win.speechSynthesis;
    const asks = () => env.calls().filter((c) => c.m === 'TtsGetVoices');

    synth.getVoices();
    synth.getVoices();
    assert.equal(asks().length, 1, 'one request in flight at a time');
    env.reply(asks()[0], []);
    await tick();
    await tick();

    synth.getVoices();
    assert.equal(asks().length, 1, 'not again right away');
    env.clock.now += 10_000;
    assert.deepEqual(Array.from(synth.getVoices()), []);
    assert.equal(asks().length, 2, 'the engine may have voices by now');
    env.reply(asks()[1], [{ name: 'en-us-1', lang: 'en-US', voiceURI: 'en-us-1', default: true, localService: true }]);
    await tick();
    await tick();
    assert.deepEqual(plain(synth.getVoices().map((v) => v.name)), ['en-us-1']);

    env.clock.now += 60_000;
    synth.getVoices();
    assert.equal(asks().length, 2, 'no more requests once voices are known');
});

test('a failed voice request is retried later', async () => {
    const env = createEnv();
    const synth = env.win.speechSynthesis;
    const asks = () => env.calls().filter((c) => c.m === 'TtsGetVoices');
    synth.getVoices();
    env.fail(asks()[0], 'boom');
    await tick();
    await tick();
    env.clock.now += 10_000;
    synth.getVoices();
    assert.equal(asks().length, 2);
});

test('speechSynthesis.cancel reports dropped utterances as errors', async () => {
    const env = createEnv();
    const synth = env.win.speechSynthesis;
    const u = new env.win.SpeechSynthesisUtterance('x');
    const errors = [];
    u.onerror = (e) => errors.push(e.error);
    synth.speak(u);
    synth.cancel();
    await new Promise((r) => setTimeout(r, 10));
    assert.deepEqual(errors, ['canceled']);
    assert.equal(synth.pending, false);
});

test('navigator.clipboard is backed by AndroidHost', async () => {
    const env = createEnv();
    const clip = env.win.navigator.clipboard;
    const w = clip.writeText('copied');
    assert.equal(env.lastCall().m, 'CopyText');
    assert.deepEqual(env.lastCall().a, ['copied']);
    env.reply(env.lastCall(), true);
    assert.equal(await w, undefined);

    const r = clip.readText();
    assert.equal(env.lastCall().m, 'ReadClipboardText');
    env.reply(env.lastCall(), 'pasted');
    assert.equal(await r, 'pasted');

    const png = new Blob([new Uint8Array([137, 80, 78, 71])], { type: 'image/png' });
    const item = new env.win.ClipboardItem({ 'image/png': png });
    const wi = clip.write([item]);
    await tick();
    await tick();
    assert.equal(env.lastCall().m, 'CopyImage');
    assert.equal(env.lastCall().a[0], 'iVBORw==');
    env.reply(env.lastCall(), true);
    await wi;
});

test('<a download> with a data: URL is saved through AndroidHost.SaveFile', async () => {
    const env = createEnv();
    const a = new env.FakeAnchor();
    a.setAttribute('href', 'data:application/json;charset=utf-8,' + encodeURIComponent('{"ü":1}'));
    a.setAttribute('download', 'dialog.json');
    a.click();
    await tick();
    assert.equal(a.defaultClicks, 0);
    const call = env.lastCall();
    assert.equal(call.m, 'SaveFile');
    assert.equal(call.a[0], 'dialog.json');
    assert.equal(call.a[1], 'application/json');
    assert.equal(Buffer.from(call.a[2], 'base64').toString('utf8'), '{"ü":1}');
});

test('<a download> with a base64 image data: URL keeps the payload', async () => {
    const env = createEnv();
    const a = new env.FakeAnchor();
    a.setAttribute('href', 'data:image/png;base64,iVBORw0KGgo=');
    a.setAttribute('download', 'file_1.png');
    a.click();
    await tick();
    assert.deepEqual(env.lastCall().a, ['file_1.png', 'image/png', 'iVBORw0KGgo=']);
});

test('<a download> with a blob: URL survives an immediate revoke', async () => {
    const env = createEnv();
    const blob = new Blob(['BEGIN:VCALENDAR\nEND:VCALENDAR'], { type: 'text/calendar' });
    const url = env.win.URL.createObjectURL(blob);
    const a = new env.FakeAnchor();
    a.href = url;
    a.setAttribute('download', 'evt_1.ics');
    a.click();
    env.win.URL.revokeObjectURL(url);
    await tick();
    await tick();
    const call = env.lastCall();
    assert.equal(call.m, 'SaveFile');
    assert.equal(call.a[0], 'evt_1.ics');
    assert.equal(call.a[1], 'text/calendar');
    assert.equal(Buffer.from(call.a[2], 'base64').toString('utf8'), 'BEGIN:VCALENDAR\nEND:VCALENDAR');
});

test('ordinary anchors still click normally', () => {
    const env = createEnv();
    const a = new env.FakeAnchor();
    a.setAttribute('href', 'https://vrchat.com/');
    a.click();
    assert.equal(a.defaultClicks, 1);
    assert.equal(env.calls().length, 0);
});

test('the pipeline socket is kicked on a network change', () => {
    const env = createEnv();
    const other = new env.win.WebSocket('wss://example.com/socket');
    const ws = new env.win.WebSocket('wss://pipeline.vrchat.cloud/?auth=authcookie_x');
    assert.ok(ws instanceof env.win.WebSocket);
    const closes = [];
    ws.onclose = (e) => closes.push(e.code);
    other.onclose = () => closes.push('other');
    env.event('network-changed', { available: false });
    assert.deepEqual(closes, []);
    env.event('network-changed', { available: true });
    assert.deepEqual(closes, [4000]);
    assert.equal(ws.closed, true);
    assert.equal(ws.onclose, null);
    // Only once: the socket is no longer tracked.
    env.event('network-changed', { available: true });
    assert.deepEqual(closes, [4000]);
    assert.equal(other.closed, false);
});

test('the pipeline socket is kicked when visible again after more than 60 s without messages', () => {
    const env = createEnv();
    const ws = new env.win.WebSocket('wss://pipeline.vrchat.cloud/?auth=a');
    const closes = [];
    ws.onclose = (e) => closes.push(e.code);

    env.event('visibility', { visible: false });
    env.clock.now += 30_000;
    env.event('visibility', { visible: true, paused: false });
    assert.deepEqual(closes, [], 'short background: no kick');

    env.event('visibility', { visible: false });
    env.clock.now += 120_000;
    ws.dispatchEvent(new Event('message'));
    env.clock.now += 5_000;
    env.event('visibility', { visible: true, paused: false });
    assert.deepEqual(closes, [], 'a message in the last 60 s: the socket works right now');

    env.clock.now += 1_000;
    env.event('visibility', { visible: false });
    env.clock.now += 120_000;
    env.event('visibility', { visible: true, paused: false });
    assert.deepEqual(closes, [4000]);
});

test('a message early in a long background does not spare the socket', () => {
    const env = createEnv();
    const ws = new env.win.WebSocket('wss://pipeline.vrchat.cloud/?auth=a');
    const closes = [];
    ws.onclose = (e) => closes.push(e.code);
    env.event('visibility', { visible: false });
    env.clock.now += 3_000;
    ws.dispatchEvent(new Event('message'));
    // Hours later a NAT or idle timeout may have left the socket half-open.
    env.clock.now += 3 * 3600_000;
    env.event('visibility', { visible: true, paused: false });
    assert.deepEqual(closes, [4000]);
});

test('a paused WebView always kicks the socket on return', () => {
    const env = createEnv();
    const ws = new env.win.WebSocket('wss://pipeline.vrchat.cloud/?auth=a');
    const closes = [];
    ws.onclose = (e) => closes.push(e.code);
    env.event('visibility', { visible: false });
    env.clock.now += 61_000;
    ws.dispatchEvent(new Event('message'));
    env.event('visibility', { visible: true, paused: true });
    assert.deepEqual(closes, [4000]);
});

test('calls made before VRCXNative exists are queued and flushed', async () => {
    const env = createEnv({ nativePresent: false });
    const posted = [];
    env.win.VRCXNative = {
        postMessage: (t) => posted.push(JSON.parse(t)),
        addEventListener() {}
    };
    env.win.interopApi.callDotNetMethod('AndroidHost', 'GetDeviceInfo', []);
    await new Promise((r) => setTimeout(r, 30));
    assert.deepEqual(
        posted.map((m) => m.t || m.m),
        ['hello', 'GetDeviceInfo']
    );
});

/*
 * VRCX for Android: document-start shim (docs/ARCHITECTURE.md §4).
 *
 * Injected by host/WebViewHolder.kt with WebViewCompat.addDocumentStartJavaScript for the app origin only, after a
 * one-line prelude that sets `window.__vrcxBridgeConfig` ({vrcxVersion, appVersion, arch, sdkInt, debug, origin}).
 * It runs before any page script and provides:
 *   - the platform globals WINDOWS=false, LINUX=true, ANDROID=true and <html class="is-android">;
 *   - window.interopApi.callDotNetMethod over the VRCXNative web message channel, with the local short-circuits and
 *     polling caches of §4.4;
 *   - window.electron (the preload contract of upstream src-electron/preload.js);
 *   - speechSynthesis / SpeechSynthesisUtterance backed by native TextToSpeech;
 *   - navigator.clipboard backed by AndroidHost, and <a download> interception for data:/blob: links;
 *   - a WebSocket wrapper that force-closes the VRChat pipeline socket after a network change or a long background;
 *   - the insets CSS variables, and window.__vrcxAndroid for native → JS calls and event subscriptions.
 *
 * Everything is guarded: this script must never throw into the page.
 */
(function () {
    'use strict';

    if (window.__vrcxAndroid) {
        return;
    }

    var config = {};
    try {
        config = window.__vrcxBridgeConfig || {};
        delete window.__vrcxBridgeConfig;
    } catch (e) {
        /* ignore */
    }

    function warn(message, error) {
        try {
            console.warn('[vrcx-android] ' + message, error);
        } catch (e) {
            /* ignore */
        }
    }

    function guard(name, fn) {
        try {
            fn();
        } catch (e) {
            warn(name + ' failed', e);
        }
    }

    function defineConst(target, name, value) {
        try {
            Object.defineProperty(target, name, { value: value, writable: false, enumerable: true, configurable: false });
        } catch (e) {
            try {
                target[name] = value;
            } catch (e2) {
                /* ignore */
            }
        }
    }

    function toError(value) {
        if (value instanceof Error) {
            return value;
        }
        return new Error(String(value));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 1. Platform globals (§4.1)
    // ---------------------------------------------------------------------------------------------------------------

    defineConst(window, 'WINDOWS', false);
    defineConst(window, 'LINUX', true);
    defineConst(window, 'ANDROID', true);

    function withRoot(fn) {
        try {
            if (document.documentElement) {
                fn(document.documentElement);
                return;
            }
            var observer = new MutationObserver(function () {
                if (document.documentElement) {
                    observer.disconnect();
                    guard('root callback', function () {
                        fn(document.documentElement);
                    });
                }
            });
            observer.observe(document, { childList: true });
        } catch (e) {
            warn('withRoot', e);
        }
    }

    withRoot(function (root) {
        root.classList.add('is-android');
    });

    // ---------------------------------------------------------------------------------------------------------------
    // 2. Transport (§4.2): {id, c, m, a} → VRCXNative; replies {id, ok, r|e}; events {ev, d}
    // ---------------------------------------------------------------------------------------------------------------

    var nativeObject = null;
    var nextId = 1;
    var pending = new Map();
    var outbox = [];

    function argumentReplacer(key, value) {
        if (value === undefined) {
            return null;
        }
        if (typeof value === 'bigint') {
            return value.toString();
        }
        if (value instanceof Map) {
            var obj = {};
            value.forEach(function (v, k) {
                obj[String(k)] = v === undefined ? null : v;
            });
            return obj;
        }
        if (value instanceof Set) {
            return Array.from(value);
        }
        return value;
    }

    function getNative() {
        if (nativeObject) {
            return nativeObject;
        }
        var candidate = null;
        try {
            candidate = window.VRCXNative || null;
        } catch (e) {
            candidate = null;
        }
        if (!candidate || typeof candidate.postMessage !== 'function') {
            return null;
        }
        nativeObject = candidate;
        try {
            nativeObject.addEventListener('message', function (event) {
                onNativeMessage(event && event.data);
            });
        } catch (e) {
            try {
                nativeObject.onmessage = function (event) {
                    onNativeMessage(event && event.data);
                };
            } catch (e2) {
                warn('cannot listen to VRCXNative', e2);
            }
        }
        return nativeObject;
    }

    function post(text) {
        var target = getNative();
        if (!target || outbox.length > 0) {
            // Keep the order: while anything is queued, later messages queue behind it.
            outbox.push(text);
            scheduleFlush();
            return;
        }
        target.postMessage(text);
    }

    var flushScheduled = false;
    var flushAttempts = 0;

    function scheduleFlush() {
        if (flushScheduled) {
            return;
        }
        flushScheduled = true;
        setTimeout(function () {
            flushScheduled = false;
            var target = getNative();
            if (!target) {
                flushAttempts++;
                if (flushAttempts < 50) {
                    scheduleFlush();
                } else {
                    failOutbox('InvalidOperationException: the native bridge is not available');
                }
                return;
            }
            var queued = outbox;
            outbox = [];
            queued.forEach(function (text) {
                try {
                    target.postMessage(text);
                } catch (e) {
                    warn('postMessage failed', e);
                }
            });
        }, 20);
    }

    function failOutbox(message) {
        var queued = outbox;
        outbox = [];
        queued.forEach(function (text) {
            try {
                var id = JSON.parse(text).id;
                var entry = pending.get(id);
                if (entry) {
                    pending.delete(id);
                    entry.reject(new Error(message));
                }
            } catch (e) {
                /* ignore */
            }
        });
    }

    /** Sends one call to native and returns a Promise of its result. */
    function nativeCall(className, methodName, args) {
        return new Promise(function (resolve, reject) {
            var id = nextId++;
            var text;
            try {
                text = JSON.stringify({ id: id, c: className, m: methodName, a: args || [] }, argumentReplacer);
            } catch (e) {
                reject(new Error('ArgumentException: ' + (e && e.message ? e.message : String(e))));
                return;
            }
            pending.set(id, { resolve: resolve, reject: reject });
            try {
                post(text);
            } catch (e) {
                pending.delete(id);
                reject(new Error('InvalidOperationException: ' + (e && e.message ? e.message : String(e))));
            }
        });
    }

    function onNativeMessage(data) {
        if (typeof data !== 'string') {
            return;
        }
        var message;
        try {
            message = JSON.parse(data);
        } catch (e) {
            warn('unparseable native message', e);
            return;
        }
        if (!message || typeof message !== 'object') {
            return;
        }
        if (typeof message.ev === 'string') {
            dispatchEvent(message.ev, message.d === undefined ? null : message.d);
            return;
        }
        var entry = pending.get(message.id);
        if (!entry) {
            return;
        }
        pending.delete(message.id);
        if (message.ok) {
            entry.resolve(message.r === undefined ? null : message.r);
        } else {
            entry.reject(new Error(typeof message.e === 'string' ? message.e : 'Exception: native call failed'));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 3. Native events: internal handling, __vrcxAndroid.on() subscribers, and window CustomEvents
    // ---------------------------------------------------------------------------------------------------------------

    var listeners = new Map();
    var internalHandlers = {};

    function dispatchEvent(name, data) {
        var internal = internalHandlers[name];
        if (internal) {
            guard('event ' + name, function () {
                internal(data);
            });
        }
        var set = listeners.get(name);
        if (set) {
            Array.from(set).forEach(function (callback) {
                try {
                    callback(data);
                } catch (e) {
                    warn('listener for ' + name + ' threw', e);
                }
            });
        }
        guard('CustomEvent ' + name, function () {
            window.dispatchEvent(new CustomEvent('vrcx-android:' + name, { detail: data }));
        });
    }

    function on(name, callback) {
        if (typeof callback !== 'function') {
            return function () {};
        }
        var key = String(name);
        var set = listeners.get(key);
        if (!set) {
            set = new Set();
            listeners.set(key, set);
        }
        set.add(callback);
        return function () {
            off(key, callback);
        };
    }

    function off(name, callback) {
        var set = listeners.get(String(name));
        if (set) {
            set.delete(callback);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 4. interopApi.callDotNetMethod with local short-circuits (§4.4 items 2-3)
    // ---------------------------------------------------------------------------------------------------------------

    var LOCAL_NOOPS = {
        AppApiElectron: {
            ExecuteVrOverlayFunction: true,
            SetVR: true,
            XSNotification: true,
            OVRTNotification: true,
            SendIpc: true,
            IPCAnnounceStart: true,
            SetUserAgent: true
        },
        Discord: { SetAssets: true }
    };

    /** A cached answer is still re-checked with native this often, in case an event was missed. */
    var CACHE_MAX_AGE_MS = 30000;

    function now() {
        return Date.now();
    }

    // LogWatcher.GetLogLines: native signals `log-available`; until then the queue is known to be empty.
    var logState = { dirty: true, at: 0, inflight: null };

    function getLogLines(args) {
        if (logState.inflight) {
            return logState.inflight.then(function () {
                return [];
            });
        }
        if (!logState.dirty && now() - logState.at < CACHE_MAX_AGE_MS) {
            return Promise.resolve([]);
        }
        logState.dirty = false;
        logState.at = now();
        var call = nativeCall('LogWatcher', 'GetLogLines', args);
        logState.inflight = call;
        call.then(
            function () {
                logState.inflight = null;
            },
            function () {
                logState.inflight = null;
                logState.dirty = true;
            }
        );
        return call;
    }

    // AppApiElectron.IsGameRunning / IsSteamVRRunning: native signals `game-state` when either changes.
    var gameCache = {
        IsGameRunning: { dirty: true, at: 0, value: false, inflight: null },
        IsSteamVRRunning: { dirty: true, at: 0, value: false, inflight: null }
    };

    function getGameState(methodName, args) {
        var entry = gameCache[methodName];
        if (entry.inflight) {
            return entry.inflight;
        }
        if (!entry.dirty && now() - entry.at < CACHE_MAX_AGE_MS) {
            return Promise.resolve(entry.value);
        }
        entry.dirty = false;
        var call = nativeCall('AppApiElectron', methodName, args);
        entry.inflight = call;
        call.then(
            function (value) {
                entry.inflight = null;
                entry.value = value === true;
                entry.at = now();
            },
            function () {
                entry.inflight = null;
                entry.dirty = true;
            }
        );
        return call;
    }

    internalHandlers['log-available'] = function () {
        logState.dirty = true;
    };
    internalHandlers['game-state'] = function () {
        gameCache.IsGameRunning.dirty = true;
        gameCache.IsSteamVRRunning.dirty = true;
    };

    // TTS calls keep their order although AndroidHost runs calls concurrently: each waits for the previous one.
    var ttsTail = Promise.resolve();

    function ttsCall(methodName, args) {
        var call = ttsTail.then(function () {
            return nativeCall('AndroidHost', methodName, args);
        });
        ttsTail = call.then(
            function () {},
            function () {}
        );
        return call;
    }

    function localCall(className, methodName, args) {
        var noops = LOCAL_NOOPS[className];
        if (noops && noops[methodName] === true) {
            return Promise.resolve(undefined);
        }
        if (className === 'Discord' && methodName === 'SetActive') {
            return Promise.resolve(false);
        }
        if (className === 'LogWatcher') {
            if (methodName === 'GetLogLines') {
                return getLogLines(args);
            }
            if (methodName === 'Reset' || methodName === 'SetDateTill' || methodName === 'Get') {
                logState.dirty = true;
            }
            return null;
        }
        if (className === 'AppApiElectron' && gameCache[methodName]) {
            return getGameState(methodName, args);
        }
        if (className === 'AndroidHost' && (methodName === 'TtsSpeak' || methodName === 'TtsCancel')) {
            return ttsCall(methodName, args);
        }
        return null;
    }

    function callDotNetMethod(className, methodName, args) {
        try {
            var c = String(className);
            var m = String(methodName);
            var a = Array.isArray(args) ? args : args == null ? [] : Array.prototype.slice.call(args);
            var local = localCall(c, m, a);
            if (local) {
                return local;
            }
            return nativeCall(c, m, a);
        } catch (e) {
            return Promise.reject(toError(e));
        }
    }

    defineConst(window, 'interopApi', Object.freeze({ callDotNetMethod: callDotNetMethod }));

    // ---------------------------------------------------------------------------------------------------------------
    // 5. window.electron (upstream src-electron/preload.js:43-68)
    // ---------------------------------------------------------------------------------------------------------------

    var managed = new Map();

    function registerManaged(key, callback) {
        if (typeof callback !== 'function') {
            return function () {};
        }
        managed.set(key, callback);
        return function () {
            if (managed.get(key) === callback) {
                managed.delete(key);
            }
        };
    }

    function hostCall(methodName, args) {
        return callDotNetMethod('AndroidHost', methodName, args || []);
    }

    var pendingLaunchCommand = null;

    function deliverLaunchCommand(command) {
        var callback = managed.get('ipcRenderer:launch-command');
        if (!callback) {
            // Keep it until the frontend registers its listener (stores/vrcx.js init).
            pendingLaunchCommand = command;
            return;
        }
        pendingLaunchCommand = null;
        callback(command);
    }

    var electron = {
        getArch: function () {
            return Promise.resolve(config.arch || 'arm64');
        },
        getClipboardText: function () {
            return hostCall('ElectronGetClipboardText').then(function (text) {
                return typeof text === 'string' ? text : '';
            });
        },
        getNoUpdater: function () {
            return Promise.resolve(true);
        },
        setTrayIconNotification: function (notify) {
            return hostCall('ElectronSetTrayIconNotification', [notify === true]).then(function () {
                return undefined;
            });
        },
        openFileDialog: function () {
            return hostCall('ElectronOpenFileDialog');
        },
        openDirectoryDialog: function () {
            return hostCall('ElectronOpenDirectoryDialog');
        },
        onWindowPositionChanged: function (callback) {
            return registerManaged('setWindowPosition', callback);
        },
        onWindowSizeChanged: function (callback) {
            return registerManaged('setWindowSize', callback);
        },
        onWindowStateChange: function (callback) {
            return registerManaged('setWindowState', callback);
        },
        onBrowserFocus: function (callback) {
            return registerManaged('onBrowserFocus', callback);
        },
        desktopNotification: function (title, body, icon) {
            return hostCall('ElectronDesktopNotification', [
                title == null ? '' : String(title),
                body == null ? '' : String(body),
                icon == null ? '' : String(icon)
            ]).then(function () {
                return undefined;
            });
        },
        restartApp: function () {
            return hostCall('ElectronRestartApp');
        },
        getOverlayWindow: function () {
            return Promise.resolve(false);
        },
        updateVr: function () {
            return Promise.resolve(undefined);
        },
        ipcRenderer: Object.freeze({
            on: function (channel, func) {
                if (channel !== 'launch-command') {
                    return undefined;
                }
                var unsubscribe = registerManaged('ipcRenderer:launch-command', function (command) {
                    func(command);
                });
                if (pendingLaunchCommand !== null) {
                    var command = pendingLaunchCommand;
                    pendingLaunchCommand = null;
                    setTimeout(function () {
                        guard('launch-command', function () {
                            deliverLaunchCommand(command);
                        });
                    }, 0);
                }
                return unsubscribe;
            }
        })
    };

    defineConst(window, 'electron', Object.freeze(electron));

    internalHandlers['launch-command'] = function (command) {
        if (typeof command === 'string' && command) {
            deliverLaunchCommand(command);
        }
    };
    internalHandlers.focus = function () {
        var callback = managed.get('onBrowserFocus');
        if (callback) {
            callback(null);
        }
    };

    // ---------------------------------------------------------------------------------------------------------------
    // 6. Insets → CSS variables (§4.4 item 7)
    // ---------------------------------------------------------------------------------------------------------------

    var lastInsets = { top: 0, right: 0, bottom: 0, left: 0, imeBottom: 0 };

    function px(value) {
        var n = Number(value);
        return (isFinite(n) && n > 0 ? n : 0) + 'px';
    }

    function setInsets(insets) {
        var next = insets && typeof insets === 'object' ? insets : {};
        var imeGrew = Number(next.imeBottom) > Number(lastInsets.imeBottom || 0);
        lastInsets = {
            top: Number(next.top) || 0,
            right: Number(next.right) || 0,
            bottom: Number(next.bottom) || 0,
            left: Number(next.left) || 0,
            imeBottom: Number(next.imeBottom) || 0
        };
        withRoot(function (root) {
            var style = root.style;
            style.setProperty('--safe-top', px(lastInsets.top));
            style.setProperty('--safe-right', px(lastInsets.right));
            style.setProperty('--safe-bottom', px(lastInsets.bottom));
            style.setProperty('--safe-left', px(lastInsets.left));
            // Keyboard height from the window bottom (overlaps --safe-bottom): pad with max(--safe-bottom, --ime-bottom).
            style.setProperty('--ime-bottom', px(lastInsets.imeBottom));
        });
        if (imeGrew) {
            // Once the page has made room for the keyboard, keep the focused field visible.
            requestAnimationFrame(function () {
                guard('scroll focused field', function () {
                    var el = document.activeElement;
                    if (el && el !== document.body && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName))) {
                        el.scrollIntoView({ block: 'nearest' });
                    }
                });
            });
        }
    }

    setInsets(lastInsets);
    internalHandlers.insets = setInsets;

    // ---------------------------------------------------------------------------------------------------------------
    // 7. window.__vrcxAndroid: native → JS entry points
    // ---------------------------------------------------------------------------------------------------------------

    var vrcxAndroid = {
        /** The phone shell registers the real back algorithm (DESIGN.md §6) as backHandler. */
        backHandler: null,
        handleBack: function () {
            try {
                var handler = vrcxAndroid.backHandler;
                return typeof handler === 'function' ? !!handler() : false;
            } catch (e) {
                warn('backHandler threw', e);
                return false;
            }
        },
        on: on,
        off: off,
        setInsets: function (insets) {
            guard('setInsets', function () {
                setInsets(insets);
            });
        },
        /** Delivers an event as if native had sent it (diagnostics, evaluateJavascript fallbacks). */
        onEvent: function (name, data) {
            guard('onEvent', function () {
                dispatchEvent(String(name), data === undefined ? null : data);
            });
        }
    };
    Object.defineProperty(vrcxAndroid, 'insets', {
        enumerable: true,
        get: function () {
            return Object.assign({}, lastInsets);
        }
    });
    Object.defineProperty(vrcxAndroid, 'config', { enumerable: true, value: Object.freeze(Object.assign({}, config)) });
    defineConst(window, '__vrcxAndroid', vrcxAndroid);

    // ---------------------------------------------------------------------------------------------------------------
    // 8. speechSynthesis / SpeechSynthesisUtterance (missing in Android System WebView)
    // ---------------------------------------------------------------------------------------------------------------

    guard('speechSynthesis', function () {
        var voices = [];
        var voicesRequested = false;
        var utterances = new Map();
        var queue = [];
        var nextUtteranceId = 1;

        function makeVoice(v) {
            return Object.freeze({
                voiceURI: String(v.voiceURI || v.name || ''),
                name: String(v.name || v.voiceURI || ''),
                lang: String(v.lang || ''),
                localService: v.localService !== false,
                default: v.default === true
            });
        }

        // UI index i must address the same voice in the LINUX-filtered list and here; native already
        // sends this order, so this is a no-op then.
        function orderVoices(list) {
            var seen = new Set();
            var firsts = [];
            list.forEach(function (v) {
                if (!seen.has(v.lang)) {
                    seen.add(v.lang);
                    if (v.lang.indexOf('en') === 0) {
                        firsts.push(v);
                    }
                }
            });
            return firsts.concat(
                list.filter(function (v) {
                    return firsts.indexOf(v) < 0;
                })
            );
        }

        function fire(target, type, extra) {
            var event;
            try {
                event = new Event(type);
            } catch (e) {
                return;
            }
            if (extra) {
                Object.keys(extra).forEach(function (key) {
                    try {
                        Object.defineProperty(event, key, { value: extra[key], enumerable: true });
                    } catch (e) {
                        /* ignore */
                    }
                });
            }
            try {
                target.dispatchEvent(event);
            } catch (e) {
                warn('dispatch ' + type, e);
            }
            var handler = target['on' + type];
            if (typeof handler === 'function') {
                try {
                    handler.call(target, event);
                } catch (e) {
                    warn('on' + type + ' threw', e);
                }
            }
        }

        class SpeechSynthesisUtterance extends EventTarget {
            constructor(text) {
                super();
                this.text = text === undefined ? '' : String(text);
                this.lang = '';
                this.voice = null;
                this.volume = 1;
                this.rate = 1;
                this.pitch = 1;
                this.onstart = null;
                this.onend = null;
                this.onerror = null;
                this.onpause = null;
                this.onresume = null;
                this.onmark = null;
                this.onboundary = null;
            }
        }

        class SpeechSynthesis extends EventTarget {
            constructor() {
                super();
                this.onvoiceschanged = null;
            }

            get speaking() {
                return queue.some(function (entry) {
                    return entry.started;
                });
            }

            get pending() {
                return queue.some(function (entry) {
                    return !entry.started;
                });
            }

            get paused() {
                return false;
            }

            getVoices() {
                if (voices.length === 0 && !voicesRequested) {
                    voicesRequested = true;
                    hostCall('TtsGetVoices')
                        .then(setVoices)
                        .catch(function (e) {
                            warn('TtsGetVoices failed', e);
                        });
                }
                return voices.slice();
            }

            speak(utterance) {
                if (!utterance || typeof utterance !== 'object') {
                    throw new TypeError("Failed to execute 'speak' on 'SpeechSynthesis': parameter 1 is not of type 'SpeechSynthesisUtterance'.");
                }
                var id = nextUtteranceId++;
                var entry = { id: id, utterance: utterance, started: false };
                utterances.set(id, entry);
                queue.push(entry);
                var voice = utterance.voice;
                ttsCall('TtsSpeak', [
                    {
                        id: id,
                        text: String(utterance.text == null ? '' : utterance.text),
                        lang: String(utterance.lang || (voice && voice.lang) || ''),
                        voiceURI: voice ? String(voice.voiceURI || voice.name || '') : null,
                        rate: Number(utterance.rate) || 1,
                        pitch: utterance.pitch == null ? 1 : Number(utterance.pitch),
                        volume: utterance.volume == null ? 1 : Number(utterance.volume)
                    }
                ]).catch(function (e) {
                    finish(id, 'error', { error: 'synthesis-failed' });
                    warn('TtsSpeak failed', e);
                });
            }

            cancel() {
                var dropped = queue.slice();
                queue.length = 0;
                utterances.clear();
                ttsCall('TtsCancel', []).catch(function (e) {
                    warn('TtsCancel failed', e);
                });
                dropped.forEach(function (entry) {
                    setTimeout(function () {
                        fire(entry.utterance, 'error', {
                            utterance: entry.utterance,
                            error: entry.started ? 'interrupted' : 'canceled',
                            charIndex: 0,
                            elapsedTime: 0
                        });
                    }, 0);
                });
            }

            pause() {}

            resume() {}
        }

        var synth = new SpeechSynthesis();

        function setVoices(list) {
            if (!Array.isArray(list)) {
                return;
            }
            voices = orderVoices(
                list
                    .filter(function (v) {
                        return v && typeof v === 'object';
                    })
                    .map(makeVoice)
            );
            fire(synth, 'voiceschanged');
        }

        function finish(id, type, extra) {
            var entry = utterances.get(id);
            if (!entry) {
                return;
            }
            utterances.delete(id);
            var index = queue.indexOf(entry);
            if (index >= 0) {
                queue.splice(index, 1);
            }
            fire(entry.utterance, type, Object.assign({ utterance: entry.utterance, charIndex: 0, elapsedTime: 0 }, extra || {}));
        }

        internalHandlers['tts-voices'] = setVoices;
        internalHandlers['tts-event'] = function (data) {
            if (!data || typeof data !== 'object') {
                return;
            }
            var id = Number(data.id);
            if (data.type === 'start') {
                var entry = utterances.get(id);
                if (entry && !entry.started) {
                    entry.started = true;
                    fire(entry.utterance, 'start', { utterance: entry.utterance, charIndex: 0, elapsedTime: 0 });
                }
            } else if (data.type === 'end') {
                finish(id, 'end');
            } else if (data.type === 'error') {
                finish(id, 'error', { error: data.error || 'synthesis-failed' });
            }
        };

        Object.defineProperty(window, 'speechSynthesis', { configurable: true, enumerable: true, get: function () { return synth; } });
        Object.defineProperty(window, 'SpeechSynthesisUtterance', { configurable: true, writable: true, value: SpeechSynthesisUtterance });
        Object.defineProperty(window, 'SpeechSynthesis', { configurable: true, writable: true, value: SpeechSynthesis });
    });

    // ---------------------------------------------------------------------------------------------------------------
    // 9. Binary helpers
    // ---------------------------------------------------------------------------------------------------------------

    function bytesToBase64(bytes) {
        var binary = '';
        var chunk = 0x8000;
        for (var i = 0; i < bytes.length; i += chunk) {
            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        }
        return btoa(binary);
    }

    function blobToBase64(blob) {
        if (blob && typeof blob.arrayBuffer === 'function') {
            return blob.arrayBuffer().then(function (buffer) {
                return bytesToBase64(new Uint8Array(buffer));
            });
        }
        return new Promise(function (resolve, reject) {
            var reader = new FileReader();
            reader.onload = function () {
                var result = String(reader.result || '');
                resolve(result.substring(result.indexOf(',') + 1));
            };
            reader.onerror = function () {
                reject(reader.error || new Error('FileReader failed'));
            };
            reader.readAsDataURL(blob);
        });
    }

    /** data: URL → {mime, base64}. */
    function parseDataUrl(url) {
        var comma = url.indexOf(',');
        if (comma < 0) {
            return null;
        }
        var header = url.substring(5, comma);
        var payload = url.substring(comma + 1);
        var parts = header.split(';');
        var mime = parts[0] || 'text/plain';
        var isBase64 = parts.indexOf('base64') >= 0;
        if (isBase64) {
            return { mime: mime, base64: payload.replace(/\s/g, '') };
        }
        var text;
        try {
            text = decodeURIComponent(payload);
        } catch (e) {
            text = payload;
        }
        return { mime: mime, base64: bytesToBase64(new TextEncoder().encode(text)) };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 10. Clipboard: deterministic, backed by AndroidHost
    // ---------------------------------------------------------------------------------------------------------------

    guard('clipboard', function () {
        if (typeof window.ClipboardItem === 'undefined') {
            class ClipboardItem {
                constructor(items) {
                    this._items = Object.assign({}, items);
                    this.types = Object.freeze(Object.keys(this._items));
                }

                getType(type) {
                    var value = this._items[type];
                    if (value === undefined) {
                        return Promise.reject(new DOMException('The type was not found', 'NotFoundError'));
                    }
                    return Promise.resolve(value).then(function (v) {
                        return v instanceof Blob ? v : new Blob([v], { type: type });
                    });
                }
            }
            Object.defineProperty(window, 'ClipboardItem', { configurable: true, writable: true, value: ClipboardItem });
        }

        var clipboardApi = {
            writeText: function (text) {
                return hostCall('CopyText', [String(text == null ? '' : text)]).then(function () {
                    return undefined;
                });
            },
            readText: function () {
                return hostCall('ReadClipboardText').then(function (text) {
                    return typeof text === 'string' ? text : '';
                });
            },
            write: function (items) {
                var list = Array.isArray(items) ? items : Array.from(items || []);
                var item = list[0];
                if (!item || !item.types) {
                    return Promise.reject(new DOMException('No clipboard items', 'DataError'));
                }
                var types = Array.from(item.types);
                if (types.indexOf('image/png') >= 0) {
                    return item
                        .getType('image/png')
                        .then(blobToBase64)
                        .then(function (base64) {
                            return hostCall('CopyImage', [base64]);
                        })
                        .then(function () {
                            return undefined;
                        });
                }
                if (types.indexOf('text/plain') >= 0) {
                    return item
                        .getType('text/plain')
                        .then(function (blob) {
                            return blob.text();
                        })
                        .then(clipboardApi.writeText);
                }
                return Promise.reject(new DOMException('Type not supported on write', 'NotAllowedError'));
            }
        };

        var proto = typeof Clipboard !== 'undefined' ? Clipboard.prototype : null;
        if (proto) {
            ['writeText', 'readText', 'write'].forEach(function (name) {
                Object.defineProperty(proto, name, {
                    configurable: true,
                    writable: true,
                    value: function () {
                        return clipboardApi[name].apply(null, arguments);
                    }
                });
            });
        }
        if (!navigator.clipboard) {
            Object.defineProperty(navigator, 'clipboard', { configurable: true, value: clipboardApi });
        }
    });

    // ---------------------------------------------------------------------------------------------------------------
    // 11. Downloads: <a download> with data:/blob: → AndroidHost.SaveFile
    // ---------------------------------------------------------------------------------------------------------------

    guard('downloads', function () {
        var blobs = new Map();
        var originalCreate = URL.createObjectURL;
        var originalRevoke = URL.revokeObjectURL;
        var REVOKE_DELAY_MS = 60000;

        if (typeof originalCreate === 'function') {
            URL.createObjectURL = function (object) {
                var url = originalCreate.apply(URL, arguments);
                try {
                    if (typeof Blob !== 'undefined' && object instanceof Blob) {
                        blobs.set(url, object);
                    }
                } catch (e) {
                    /* ignore */
                }
                return url;
            };
        }
        if (typeof originalRevoke === 'function') {
            URL.revokeObjectURL = function (url) {
                // Keep the URL alive for a moment: pages revoke right after click(), before the save has read it.
                setTimeout(function () {
                    blobs.delete(url);
                    try {
                        originalRevoke.call(URL, url);
                    } catch (e) {
                        /* ignore */
                    }
                }, REVOKE_DELAY_MS);
            };
        }

        function downloadHref(anchor) {
            if (!anchor || !anchor.hasAttribute || !anchor.hasAttribute('download')) {
                return null;
            }
            var href = String(anchor.href || anchor.getAttribute('href') || '');
            return href.indexOf('data:') === 0 || href.indexOf('blob:') === 0 ? href : null;
        }

        function extensionFor(mime) {
            var map = {
                'application/json': '.json',
                'text/plain': '.txt',
                'text/csv': '.csv',
                'text/calendar': '.ics',
                'image/png': '.png',
                'image/jpeg': '.jpg',
                'image/webp': '.webp',
                'image/gif': '.gif'
            };
            return map[mime] || '';
        }

        function save(anchor, href) {
            var suggested = String(anchor.getAttribute('download') || '').trim();
            var payload;
            if (href.indexOf('data:') === 0) {
                var parsed = parseDataUrl(href);
                if (!parsed) {
                    return;
                }
                payload = Promise.resolve(parsed);
            } else {
                var blob = blobs.get(href);
                var blobPromise = blob
                    ? Promise.resolve(blob)
                    : fetch(href).then(function (response) {
                          return response.blob();
                      });
                payload = blobPromise.then(function (b) {
                    return blobToBase64(b).then(function (base64) {
                        return { mime: b.type || 'application/octet-stream', base64: base64 };
                    });
                });
            }
            payload
                .then(function (data) {
                    var mime = String(data.mime || 'application/octet-stream').split(';')[0];
                    var name = suggested || 'download' + extensionFor(mime);
                    return hostCall('SaveFile', [name, mime, data.base64]);
                })
                .catch(function (e) {
                    warn('saving a download failed', e);
                });
        }

        function intercept(anchor) {
            var href = downloadHref(anchor);
            if (!href) {
                return false;
            }
            save(anchor, href);
            return true;
        }

        if (typeof HTMLAnchorElement !== 'undefined') {
            var originalClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function () {
                try {
                    if (intercept(this)) {
                        return;
                    }
                } catch (e) {
                    warn('download interception failed', e);
                }
                return originalClick.apply(this, arguments);
            };
        }

        window.addEventListener(
            'click',
            function (event) {
                try {
                    var target = event.target;
                    var anchor = target && typeof target.closest === 'function' ? target.closest('a[download]') : null;
                    if (!anchor || event.defaultPrevented || !downloadHref(anchor)) {
                        return;
                    }
                    event.preventDefault();
                    intercept(anchor);
                } catch (e) {
                    warn('download click failed', e);
                }
            },
            true
        );
    });

    // ---------------------------------------------------------------------------------------------------------------
    // 12. WebSocket wrapper: kick the VRChat pipeline socket (§4.4 item 5)
    // ---------------------------------------------------------------------------------------------------------------

    var pipeline = { socket: null, lastMessageAt: 0 };

    guard('WebSocket', function () {
        var NativeWebSocket = window.WebSocket;
        if (typeof NativeWebSocket !== 'function') {
            return;
        }

        function isPipelineUrl(url) {
            var text = String(url || '');
            return /^wss?:\/\/pipeline\./i.test(text) || /[?&]auth=/.test(text);
        }

        class VRCXWebSocket extends NativeWebSocket {
            constructor(url, protocols) {
                if (protocols === undefined) {
                    super(url);
                } else {
                    super(url, protocols);
                }
                try {
                    if (isPipelineUrl(url)) {
                        var socket = this;
                        pipeline.socket = socket;
                        pipeline.lastMessageAt = now();
                        socket.addEventListener('message', function () {
                            if (pipeline.socket === socket) {
                                pipeline.lastMessageAt = now();
                            }
                        });
                        socket.addEventListener('close', function () {
                            if (pipeline.socket === socket) {
                                pipeline.socket = null;
                            }
                        });
                    }
                } catch (e) {
                    warn('tracking a WebSocket failed', e);
                }
            }
        }

        Object.defineProperty(window, 'WebSocket', { configurable: true, writable: true, value: VRCXWebSocket });
    });

    /**
     * Force-closes the pipeline socket through the page's own close handler with a non-graceful code, so
     * services/websocket.js reconnects with a fresh token and resyncs friends and notifications.
     */
    function kickPipeline(reason) {
        var socket = pipeline.socket;
        if (!socket || socket.readyState > 1) {
            return false;
        }
        pipeline.socket = null;
        var onclose = socket.onclose;
        socket.onclose = null;
        socket.onerror = null;
        socket.onmessage = null;
        socket.onopen = null;
        try {
            if (typeof onclose === 'function') {
                onclose.call(socket, new CloseEvent('close', { code: 4000, reason: reason, wasClean: false }));
            }
        } catch (e) {
            warn('pipeline onclose threw', e);
        }
        try {
            socket.close();
        } catch (e) {
            /* ignore */
        }
        return true;
    }

    var BACKGROUND_KICK_MS = 60000;
    var hiddenAt = 0;

    internalHandlers['network-changed'] = function (data) {
        if (data && data.available === true) {
            kickPipeline('network changed');
        }
    };
    internalHandlers.visibility = function (data) {
        if (!data || typeof data !== 'object') {
            return;
        }
        if (data.visible === false) {
            if (!hiddenAt) {
                hiddenAt = now();
            }
            return;
        }
        var since = hiddenAt;
        hiddenAt = 0;
        if (!since || now() - since <= BACKGROUND_KICK_MS) {
            return;
        }
        // Visible again after more than 60 s. Kick unless the socket demonstrably worked in the meantime (a message
        // arrived while hidden and the WebView was not paused), to avoid a full resync on every return to the app.
        if (data.paused === true || pipeline.lastMessageAt < since) {
            kickPipeline('resumed after background');
        }
    };

    Object.defineProperty(vrcxAndroid, 'kickPipeline', {
        enumerable: false,
        value: function (reason) {
            return kickPipeline(String(reason || 'manual'));
        }
    });

    // ---------------------------------------------------------------------------------------------------------------
    // 13. Hello: native replays the current state events (insets, voices, companion state, ...)
    // ---------------------------------------------------------------------------------------------------------------

    guard('hello', function () {
        post(JSON.stringify({ t: 'hello', href: String(location.href) }));
    });
})();

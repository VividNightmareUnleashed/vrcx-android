// Preview-harness mocks for these AndroidHost methods (docs/ARCHITECTURE.md §5.1): session
// state, launch commands from other apps, custom.css/custom.js import, start on boot and the VRChat photos folder.
// Dev only (see ../README.md).
//
// Query parameters:
//   external=<command>  a launch command from another app waits for AndroidHost.TakeExternalLaunchCommand, as after a
//                       cold start from a vrcx:// link (for example external=switchavatar/avtr_...)
//   photos=<name>       a VRChat photos folder is already chosen (AndroidHost.GetPhotosFolder answers <name>); without
//                       it none is chosen and the Screenshot Manager shows its hint
//   boot=1              "Start when the device boots" starts switched on
//
// Console helpers (window.__vrcxDev):
//   external(command)   sends an external-launch-command event, as when another app opens a vrcx:// link
//   hostState           the fake host state below
const params = new URLSearchParams(globalThis.location?.search ?? '');

export const hostState = {
    sessionActive: null,
    pendingExternal: params.get('external') ?? '',
    startOnBoot: params.get('boot') === '1',
    customFiles: { css: false, js: false },
    photosFolder: params.get('photos') ?? ''
};

/** How long the fake folder picker stays open before it "returns" with a folder. */
const PICKER_DELAY_MS = 1200;

function emit(name, payload) {
    globalThis.window?.__vrcxAndroid?.emit?.(name, payload);
}

/**
 * Answers the AndroidHost methods of this area, or `undefined` to fall through.
 *
 * @param {string} method
 * @param {unknown[]} args
 * @param {typeof hostState} [state]
 * @returns {unknown}
 */
export function androidHost(method, args, state = hostState) {
    switch (method) {
        case 'SetSessionActive':
            state.sessionActive = args[0] === true;
            console.info('[preview] SetSessionActive', state.sessionActive);
            return null;
        case 'TakeExternalLaunchCommand': {
            const command = state.pendingExternal;
            state.pendingExternal = '';
            return command;
        }
        case 'GetStartOnBoot':
            return state.startOnBoot;
        case 'SetStartOnBoot':
            state.startOnBoot = args[0] === true;
            return state.startOnBoot;
        case 'ImportCustomFile': {
            const type = args[0] === 'js' ? 'js' : 'css';
            state.customFiles[type] = true;
            return { ok: true, name: `preview-theme.${type}` };
        }
        case 'RemoveCustomFile': {
            const type = args[0] === 'js' ? 'js' : 'css';
            const existed = state.customFiles[type];
            state.customFiles[type] = false;
            return existed;
        }
        case 'GetPhotosFolder':
            return state.photosFolder;
        default:
            return undefined;
    }
}

/**
 * AppApiElectron.OpenVrcPhotosFolder: opens the chosen folder, or starts the (fake) folder picker and answers false;
 * the picked folder arrives when the app is back in the foreground.
 *
 * @param {string} method
 * @returns {unknown}
 */
export function appApi(method) {
    if (method !== 'OpenVrcPhotosFolder') return undefined;
    if (hostState.photosFolder) return true;
    setTimeout(() => {
        hostState.photosFolder = 'VRChat';
        emit('focus', null);
    }, PICKER_DELAY_MS);
    return false;
}

/**
 * Wraps the fake interop API so this area's AndroidHost methods answer before the defaults.
 *
 * @param {{ callDotNetMethod: Function }} api
 * @returns {{ callDotNetMethod: Function }}
 */
export function wrapInteropForHost(api) {
    const previous = api.callDotNetMethod.bind(api);
    return {
        ...api,
        async callDotNetMethod(className, methodName, args) {
            if (className === 'AndroidHost') {
                const value = androidHost(methodName, Array.isArray(args) ? args : []);
                if (value !== undefined) return value;
            }
            return previous(className, methodName, args);
        }
    };
}

// mockBridge assigns window.interopApi after the mocks are evaluated (and dialogs-login.js may replace it with a
// wrapper), so this wraps whatever is there once it exists, without reassigning the global.
if (typeof window !== 'undefined') {
    let attempts = 0;
    const timer = setInterval(() => {
        const api = window.interopApi;
        if (!api?.callDotNetMethod) {
            // Not the harness page (unit tests import the mocks without a fake host).
            if (++attempts > 1000) clearInterval(timer);
            return;
        }
        clearInterval(timer);
        api.callDotNetMethod = wrapInteropForHost(api).callDotNetMethod;
        window.__vrcxDev = Object.assign(window.__vrcxDev ?? {}, {
            hostState,
            external(command) {
                emit('external-launch-command', command);
            }
        });
    }, 0);
}

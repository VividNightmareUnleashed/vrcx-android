import { afterAll, describe, expect, test, vi } from 'vitest';

// The preview harness's fake AndroidHost methods for session state, launch commands, custom files, start on boot and
// the photos folder (mocks/audit.js).
// Fake timers before the import: the module waits for window.interopApi with an interval.
vi.useFakeTimers();
window.interopApi = { callDotNetMethod: vi.fn(async () => null) };
const { androidHost, appApi, hostState, wrapInteropForAudit } = await import('../audit.js');
vi.advanceTimersByTime(1);

afterAll(() => {
    vi.useRealTimers();
});

function freshState() {
    return {
        sessionActive: null,
        pendingExternal: 'switchavatar/avtr_x',
        startOnBoot: false,
        customFiles: { css: false, js: false },
        photosFolder: ''
    };
}

describe('harness AndroidHost (audit)', () => {
    test('keeps the session flag and hands out a held launch command once', () => {
        const state = freshState();
        const info = vi.spyOn(console, 'info').mockImplementation(() => {});
        androidHost('SetSessionActive', [true], state);
        expect(state.sessionActive).toBe(true);
        info.mockRestore();
        expect(androidHost('TakeExternalLaunchCommand', [], state)).toBe('switchavatar/avtr_x');
        expect(androidHost('TakeExternalLaunchCommand', [], state)).toBe('');
    });

    test('start on boot, custom files and the photos folder', () => {
        const state = freshState();
        expect(androidHost('SetStartOnBoot', [true], state)).toBe(true);
        expect(androidHost('GetStartOnBoot', [], state)).toBe(true);
        expect(androidHost('RemoveCustomFile', ['css'], state)).toBe(false);
        expect(androidHost('ImportCustomFile', ['css'], state)).toEqual({ ok: true, name: 'preview-theme.css' });
        expect(androidHost('RemoveCustomFile', ['css'], state)).toBe(true);
        expect(androidHost('GetPhotosFolder', [], state)).toBe('');
        expect(androidHost('CompanionGetState', [], state)).toBeUndefined();
    });

    test('OpenVrcPhotosFolder "picks" a folder when none is chosen', () => {
        hostState.photosFolder = '';
        expect(appApi('OpenVrcPhotosFolder')).toBe(false);
        vi.advanceTimersByTime(2000);
        expect(hostState.photosFolder).toBe('VRChat');
        expect(appApi('OpenVrcPhotosFolder')).toBe(true);
        expect(appApi('IsGameRunning')).toBeUndefined();
    });

    test('the harness wraps the installed interop API and adds the console helpers', async () => {
        hostState.startOnBoot = false;
        await expect(window.interopApi.callDotNetMethod('AndroidHost', 'GetStartOnBoot', [])).resolves.toBe(false);
        expect(typeof window.__vrcxDev.external).toBe('function');
    });

    test('the interop wrapper answers AndroidHost first and passes everything else on', async () => {
        const api = { callDotNetMethod: vi.fn(async () => 'default') };
        const wrapped = wrapInteropForAudit(api);
        hostState.startOnBoot = true;
        await expect(wrapped.callDotNetMethod('AndroidHost', 'GetStartOnBoot', [])).resolves.toBe(true);
        await expect(wrapped.callDotNetMethod('AndroidHost', 'CompanionGetState', [])).resolves.toBe('default');
        await expect(wrapped.callDotNetMethod('SQLite', 'ExecuteJson', [])).resolves.toBe('default');
        expect(api.callDotNetMethod).toHaveBeenCalledTimes(2);
    });
});

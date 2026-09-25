import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

import {
    COMPANION_DEFAULT_PORT,
    COMPANION_SETTINGS_ROUTE,
    companionStatusKey,
    describeCompanionError,
    formatCompanionEndpoint,
    formatCompanionTimeZone,
    formatUtcOffset,
    isCancelledError,
    isValidPairingCode,
    normalizePairingCode,
    parseCompanionAddress,
    resolveCompanionEmptyMode,
    useCompanionStore
} from '../companionStore.js';

const connectedState = {
    status: 'connected',
    activeId: 'pc-1',
    paired: [{ id: 'pc-1', name: 'DESKTOP', hosts: ['192.168.1.20'], port: 49460, fp: 'fp', pairedAt: 1, lastSeen: 2 }],
    machineName: 'DESKTOP',
    tz: { windowsId: 'W. Europe Standard Time', ianaId: 'Europe/Berlin', currentUtcOffsetMin: 120 },
    vrchatRunning: true,
    steamVrRunning: false,
    syncing: true,
    lastError: null
};

describe('pairing code helpers', () => {
    test('normalizes like the companion (case, separators, O/I/L)', () => {
        expect(normalizePairingCode(' abcde-fghjk ')).toBe('ABCDEFGHJK');
        expect(normalizePairingCode('o0il1-23456')).toBe('00111' + '23456');
        expect(normalizePairingCode(null)).toBe('');
    });

    test('accepts 10 Crockford base32 characters only', () => {
        expect(isValidPairingCode('ABCDE-12345')).toBe(true);
        expect(isValidPairingCode('abcde 1234o')).toBe(true);
        expect(isValidPairingCode('ABCDE-1234')).toBe(false);
        expect(isValidPairingCode('ABCDE-1234U')).toBe(false);
        expect(isValidPairingCode('ABCDE-12345-6')).toBe(false);
    });
});

describe('parseCompanionAddress', () => {
    test('uses the default port when none is given', () => {
        expect(parseCompanionAddress('192.168.1.20')).toEqual({ host: '192.168.1.20', port: COMPANION_DEFAULT_PORT });
        expect(parseCompanionAddress('  desktop.local ')).toEqual({ host: 'desktop.local', port: 49460 });
    });

    test('reads host:port', () => {
        expect(parseCompanionAddress('192.168.1.20:5000')).toEqual({ host: '192.168.1.20', port: 5000 });
    });

    test('handles IPv6 with and without brackets', () => {
        expect(parseCompanionAddress('[fe80::1]:49460')).toEqual({ host: 'fe80::1', port: 49460 });
        expect(parseCompanionAddress('[fe80::1]')).toEqual({ host: 'fe80::1', port: 49460 });
        expect(parseCompanionAddress('fe80::1')).toEqual({ host: 'fe80::1', port: 49460 });
    });

    test('strips a scheme and path', () => {
        expect(parseCompanionAddress('vrcxc://10.0.0.5:49460/pair')).toEqual({ host: '10.0.0.5', port: 49460 });
    });

    test('rejects empty input, bad ports and bad characters', () => {
        expect(parseCompanionAddress('')).toBeNull();
        expect(parseCompanionAddress('10.0.0.5:0')).toBeNull();
        expect(parseCompanionAddress('10.0.0.5:70000')).toBeNull();
        expect(parseCompanionAddress('10.0.0.5:port')).toBeNull();
        expect(parseCompanionAddress('my pc')).toBeNull();
        expect(parseCompanionAddress(':49460')).toBeNull();
    });
});

describe('error and status helpers', () => {
    test('maps pairing failures to their messages', () => {
        expect(describeCompanionError(new Error('PairingException: code'))).toEqual({
            key: 'android.companion.errors.code',
            detail: 'code'
        });
        expect(describeCompanionError(new Error('PairingException: not-local')).key).toBe(
            'android.companion.errors.not_local'
        );
        expect(describeCompanionError(new Error('PairingException: fingerprint')).key).toBe(
            'android.companion.errors.fingerprint'
        );
    });

    test('falls back to the generic message with the native detail', () => {
        expect(describeCompanionError(new Error('IOException: Network is unreachable'))).toEqual({
            key: 'android.companion.errors.generic',
            detail: 'Network is unreachable'
        });
        expect(describeCompanionError(new Error('PairingException: weird')).key).toBe(
            'android.companion.errors.generic'
        );
    });

    test('recognizes cancelled native flows', () => {
        expect(isCancelledError(new Error('OperationCanceledException: scan cancelled'))).toBe(true);
        expect(isCancelledError(new Error('PairingException: code'))).toBe(false);
    });

    test('maps every status to a key', () => {
        expect(companionStatusKey({ status: 'connected' })).toBe('android.companion.status.connected');
        expect(companionStatusKey({ status: 'connecting' })).toBe('android.companion.status.connecting');
        expect(companionStatusKey({ status: 'searching' })).toBe('android.companion.status.searching');
        expect(companionStatusKey({ status: 'error' })).toBe('android.companion.status.error');
        expect(companionStatusKey({ status: 'idle' })).toBe('android.companion.status.idle');
        expect(companionStatusKey({ status: 'unpaired' })).toBe('android.companion.status.unpaired');
        expect(companionStatusKey(null)).toBe('android.companion.status.unpaired');
    });
});

describe('formatting helpers', () => {
    test('formats UTC offsets', () => {
        expect(formatUtcOffset(120)).toBe('UTC+02:00');
        expect(formatUtcOffset(-210)).toBe('UTC-03:30');
        expect(formatUtcOffset(0)).toBe('UTC+00:00');
        expect(formatUtcOffset(undefined)).toBe('');
    });

    test('formats the PC time zone, preferring the IANA id and the current offset', () => {
        expect(formatCompanionTimeZone(connectedState.tz)).toBe('Europe/Berlin (UTC+02:00)');
        expect(formatCompanionTimeZone({ windowsId: 'Tokyo Standard Time', baseUtcOffsetMin: 540 })).toBe(
            'Tokyo Standard Time (UTC+09:00)'
        );
        expect(formatCompanionTimeZone(null)).toBe('');
    });

    test('formats endpoints', () => {
        expect(formatCompanionEndpoint({ hosts: ['192.168.1.20', '10.0.0.2'], port: 49460 })).toBe(
            '192.168.1.20:49460'
        );
        expect(formatCompanionEndpoint({ host: 'fe80::1', port: 1 })).toBe('[fe80::1]:1');
        expect(formatCompanionEndpoint({ hosts: [] })).toBe('');
    });

    test('settings route opens the companion tab', () => {
        expect(COMPANION_SETTINGS_ROUTE).toEqual({ name: 'settings', query: { tab: 'companion' } });
    });
});

describe('resolveCompanionEmptyMode', () => {
    test('shows nothing while loading', () => {
        expect(resolveCompanionEmptyMode({ loading: true })).toBe('none');
    });

    test('keeps the normal empty state when connected or filtered', () => {
        expect(resolveCompanionEmptyMode({ isConnected: true, isPaired: true })).toBe('fallback');
        expect(resolveCompanionEmptyMode({ filtered: true })).toBe('fallback');
    });

    test('asks to set up or reconnect the companion otherwise', () => {
        expect(resolveCompanionEmptyMode({ isPaired: false })).toBe('unpaired');
        expect(resolveCompanionEmptyMode({ isPaired: true, isConnected: false })).toBe('disconnected');
    });
});

describe('useCompanionStore', () => {
    let host;

    beforeEach(() => {
        setActivePinia(createPinia());
        host = {
            CompanionGetState: vi.fn().mockResolvedValue(connectedState),
            CompanionScanQr: vi.fn().mockResolvedValue(connectedState),
            CompanionDiscover: vi.fn().mockResolvedValue([{ id: 'pc-2', host: '10.0.0.3', port: 49460 }]),
            CompanionPair: vi.fn().mockResolvedValue(connectedState),
            CompanionForget: vi.fn().mockResolvedValue({ ...connectedState, paired: [], status: 'unpaired' }),
            CompanionSetActive: vi.fn().mockResolvedValue(connectedState)
        };
        window.AndroidHost = host;
        vi.spyOn(console, 'error').mockImplementation(() => {});
    });

    afterEach(() => {
        delete window.AndroidHost;
        vi.restoreAllMocks();
    });

    test('starts unpaired', () => {
        const store = useCompanionStore();
        expect(store.status).toBe('unpaired');
        expect(store.isPaired).toBe(false);
        expect(store.isConnected).toBe(false);
        expect(store.stateVersion).toBe(0);
    });

    test('applyState copies the native state and derives flags', () => {
        const store = useCompanionStore();
        store.applyState(connectedState);
        expect(store.isConnected).toBe(true);
        expect(store.isPaired).toBe(true);
        expect(store.activePc.name).toBe('DESKTOP');
        expect(store.vrchatRunning).toBe(true);
        expect(store.syncing).toBe(true);
        expect(store.stateVersion).toBe(1);
        store.applyState(null);
        expect(store.stateVersion).toBe(1);
    });

    test('applyGameState updates only the process flags', () => {
        const store = useCompanionStore();
        store.applyState(connectedState);
        store.applyGameState({ isGameRunning: false, isSteamVRRunning: true });
        expect(store.vrchatRunning).toBe(false);
        expect(store.steamVrRunning).toBe(true);
        expect(store.status).toBe('connected');
    });

    test('refresh loads the state from AndroidHost', async () => {
        const store = useCompanionStore();
        await store.refresh();
        expect(host.CompanionGetState).toHaveBeenCalled();
        expect(store.machineName).toBe('DESKTOP');
    });

    test('scanQr applies the new state and clears pending', async () => {
        const store = useCompanionStore();
        const outcome = await store.scanQr();
        expect(outcome.ok).toBe(true);
        expect(store.isConnected).toBe(true);
        expect(store.pending).toBeNull();
    });

    test('a cancelled scan is reported as cancelled, not as an error', async () => {
        host.CompanionScanQr.mockRejectedValue(new Error('OperationCanceledException: Scan cancelled'));
        const store = useCompanionStore();
        const outcome = await store.scanQr();
        expect(outcome).toEqual({ ok: false, cancelled: true });
    });

    test('pair sends the normalized code and only the known target fields', async () => {
        const store = useCompanionStore();
        const outcome = await store.pair({ host: '10.0.0.3', port: 49460, fp: 'abc', extra: 'x' }, 'abcde-1234o');
        expect(outcome.ok).toBe(true);
        expect(host.CompanionPair).toHaveBeenCalledWith({ host: '10.0.0.3', port: 49460, fp: 'abc' }, 'ABCDE12340');
    });

    test('pair failures come back as a message key', async () => {
        host.CompanionPair.mockRejectedValue(new Error('PairingException: expired'));
        const store = useCompanionStore();
        const outcome = await store.pair({ host: '10.0.0.3', port: 49460 }, 'ABCDE-12345');
        expect(outcome.ok).toBe(false);
        expect(outcome.error.key).toBe('android.companion.errors.expired');
    });

    test('discover returns the list of PCs', async () => {
        const store = useCompanionStore();
        const outcome = await store.discover(1000);
        expect(host.CompanionDiscover).toHaveBeenCalledWith(1000);
        expect(outcome.result).toEqual([{ id: 'pc-2', host: '10.0.0.3', port: 49460 }]);
    });

    test('forget and setActive apply the returned state', async () => {
        const store = useCompanionStore();
        store.applyState(connectedState);
        await store.forget('pc-1');
        expect(host.CompanionForget).toHaveBeenCalledWith('pc-1');
        expect(store.isPaired).toBe(false);
        await store.setActive('pc-1');
        expect(host.CompanionSetActive).toHaveBeenCalledWith('pc-1');
        expect(store.isPaired).toBe(true);
    });

    test('reports a generic error when AndroidHost is missing', async () => {
        delete window.AndroidHost;
        const store = useCompanionStore();
        const outcome = await store.scanQr();
        expect(outcome.ok).toBe(false);
        expect(outcome.error.key).toBe('android.companion.errors.generic');
        await expect(store.refresh()).resolves.toBeUndefined();
    });
});

// PC companion state mirrored from native (AndroidHost.CompanionGetState + `companion-state` events).
// The phone shell only reads `isPaired`, `isConnected`, `vrchatRunning`.
// See docs/ARCHITECTURE.md §5.1 for the state object and docs/PROTOCOL.md for pairing.
import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { getAndroidHost, parseBridgeError } from '../../shared/utils/platform.js';

/** Default TCP port of the Windows companion (docs/PROTOCOL.md §2). */
export const COMPANION_DEFAULT_PORT = 49460;

/** How long "Find PCs on this network" listens for replies. */
export const COMPANION_DISCOVERY_TIMEOUT_MS = 4000;

/** Route that opens Settings on the PC companion tab (used by empty states and the phone shell). */
export const COMPANION_SETTINGS_ROUTE = Object.freeze({ name: 'settings', query: { tab: 'companion' } });

const PAIRING_CODE_LENGTH = 10;
// Crockford base32 without I, L, O, U.
const PAIRING_CODE_PATTERN = /^[0-9A-HJKMNP-TV-Z]{10}$/;

/**
 * Normalizes a pairing code the way the companion does (docs/PROTOCOL.md §5.2): uppercase, drop `-` and spaces,
 * map `O` → `0` and `I`/`L` → `1`.
 *
 * @param {string} input
 * @returns {string}
 */
export function normalizePairingCode(input) {
    return String(input ?? '')
        .toUpperCase()
        .replace(/[\s-]+/g, '')
        .replace(/O/g, '0')
        .replace(/[IL]/g, '1');
}

/**
 * @param {string} input Raw user input
 * @returns {boolean} Whether the normalized code has the right length and alphabet
 */
export function isValidPairingCode(input) {
    const code = normalizePairingCode(input);
    return code.length === PAIRING_CODE_LENGTH && PAIRING_CODE_PATTERN.test(code);
}

/**
 * Parses a manual `host[:port]` entry. Accepts IPv4, host names, bare IPv6 and `[IPv6]:port`.
 * Whether the address is on the local network is decided natively (`PairingException: not-local`).
 *
 * @param {string} input
 * @param {number} [defaultPort]
 * @returns {{ host: string; port: number } | null}
 */
export function parseCompanionAddress(input, defaultPort = COMPANION_DEFAULT_PORT) {
    let text = String(input ?? '').trim();
    if (!text) {
        return null;
    }
    text = text.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '').replace(/\/.*$/, '');
    let host = text;
    let portText = '';

    const bracketed = text.match(/^\[([^\]]+)\](?::(\d*))?$/);
    if (bracketed) {
        host = bracketed[1];
        portText = bracketed[2] ?? '';
    } else if ((text.match(/:/g) || []).length === 1) {
        [host, portText] = text.split(':');
    } else if (text.includes(':')) {
        // Bare IPv6 without a port.
        host = text;
    }

    host = host.trim();
    if (!host || /\s/.test(host) || !/^[0-9A-Za-z.:%_-]+$/.test(host)) {
        return null;
    }
    let port = defaultPort;
    if (portText !== '') {
        if (!/^\d{1,5}$/.test(portText)) {
            return null;
        }
        port = Number(portText);
        if (port < 1 || port > 65535) {
            return null;
        }
    }
    return { host, port };
}

/**
 * @param {unknown} error
 * @returns {boolean} Whether the user cancelled a native flow (QR scanner, file picker)
 */
export function isCancelledError(error) {
    const { type } = parseBridgeError(error);
    return type === 'OperationCanceledException' || type === 'TaskCanceledException';
}

const PAIRING_ERROR_KEYS = {
    code: 'android.companion.errors.code',
    expired: 'android.companion.errors.expired',
    closed: 'android.companion.errors.closed',
    unreachable: 'android.companion.errors.unreachable',
    'not-local': 'android.companion.errors.not_local',
    fingerprint: 'android.companion.errors.fingerprint'
};

/**
 * Maps a companion bridge rejection to an i18n key plus the raw detail (shown for unknown errors).
 *
 * @param {unknown} error
 * @returns {{ key: string; detail: string }}
 */
export function describeCompanionError(error) {
    const { type, detail } = parseBridgeError(error);
    if (type === 'PairingException') {
        const reason = detail.split(/\s/)[0];
        if (PAIRING_ERROR_KEYS[reason]) {
            return { key: PAIRING_ERROR_KEYS[reason], detail };
        }
    }
    return { key: 'android.companion.errors.generic', detail: detail || type };
}

/**
 * @param {{ status?: string } | null | undefined} state
 * @returns {string} I18n key for the status line
 */
export function companionStatusKey(state) {
    switch (state?.status) {
        case 'connected':
            return 'android.companion.status.connected';
        case 'connecting':
            return 'android.companion.status.connecting';
        case 'searching':
            return 'android.companion.status.searching';
        case 'error':
            return 'android.companion.status.error';
        case 'idle':
            return 'android.companion.status.idle';
        default:
            return 'android.companion.status.unpaired';
    }
}

/**
 * @param {number} minutes Offset from UTC in minutes
 * @returns {string} `UTC+02:00` style label
 */
export function formatUtcOffset(minutes) {
    const value = Number(minutes);
    if (!Number.isFinite(value)) {
        return '';
    }
    const sign = value < 0 ? '-' : '+';
    const abs = Math.abs(Math.trunc(value));
    const hours = String(Math.floor(abs / 60)).padStart(2, '0');
    const mins = String(abs % 60).padStart(2, '0');
    return `UTC${sign}${hours}:${mins}`;
}

/**
 * @param {{ windowsId?: string; ianaId?: string; currentUtcOffsetMin?: number; baseUtcOffsetMin?: number } | null} tz
 * @returns {string} For example `Europe/Berlin (UTC+02:00)`, or `''` when unknown
 */
export function formatCompanionTimeZone(tz) {
    if (!tz || typeof tz !== 'object') {
        return '';
    }
    const name = tz.ianaId || tz.windowsId || '';
    const offsetMinutes = Number.isFinite(Number(tz.currentUtcOffsetMin))
        ? tz.currentUtcOffsetMin
        : tz.baseUtcOffsetMin;
    const offset = formatUtcOffset(offsetMinutes);
    if (name && offset) {
        return `${name} (${offset})`;
    }
    return name || offset;
}

/**
 * @param {{ hosts?: string[]; host?: string; port?: number } | null} pc paired PC (`hosts`) or discovery result (`host`)
 * @returns {string} `192.168.1.20:49460` style label (first host)
 */
export function formatCompanionEndpoint(pc) {
    const host = Array.isArray(pc?.hosts) ? pc.hosts.find(Boolean) : pc?.host;
    if (!host) {
        return '';
    }
    const shown = host.includes(':') ? `[${host}]` : host;
    return pc?.port ? `${shown}:${pc.port}` : shown;
}

/**
 * Decides what an empty Game Log / Player List / dashboard widget shows (docs/DESIGN.md §4).
 * The companion message only replaces the normal empty state when the list is really empty (not loading, not
 * narrowed by a filter) and no companion is connected.
 *
 * @param {object} input
 * @param {boolean} [input.loading]
 * @param {boolean} [input.filtered]
 * @param {boolean} [input.isPaired]
 * @param {boolean} [input.isConnected]
 * @returns {'none' | 'fallback' | 'unpaired' | 'disconnected'}
 */
export function resolveCompanionEmptyMode({
    loading = false,
    filtered = false,
    isPaired = false,
    isConnected = false
}) {
    if (loading) {
        return 'none';
    }
    if (filtered || isConnected) {
        return 'fallback';
    }
    return isPaired ? 'disconnected' : 'unpaired';
}

export const useCompanionStore = defineStore('androidCompanion', () => {
    /** @type {import('vue').Ref<'unpaired' | 'idle' | 'searching' | 'connecting' | 'connected' | 'error'>} */
    const status = ref('unpaired');
    const activeId = ref(null);
    /**
     * @type {import('vue').Ref<
     *     { id: string; name: string; hosts: string[]; port: number; fp: string; pairedAt: number; lastSeen: number }[]
     * >}
     */
    const paired = ref([]);
    const machineName = ref(null);
    const tz = ref(null);
    const vrchatRunning = ref(false);
    const steamVrRunning = ref(false);
    const syncing = ref(false);
    const lastError = ref(null);
    /** Number of native state snapshots applied (0 until the first one arrives). */
    const stateVersion = ref(0);
    /** @type {import('vue').Ref<null | 'scan' | 'discover' | 'pair' | 'forget' | 'setActive'>} */
    const pending = ref(null);

    const isPaired = computed(() => paired.value.length > 0);
    const isConnected = computed(() => status.value === 'connected');
    const activePc = computed(() => paired.value.find((pc) => pc.id === activeId.value) ?? null);

    /**
     * @param {object} state Native CompanionController.state() object
     */
    function applyState(state) {
        if (!state || typeof state !== 'object') return;
        status.value = state.status ?? 'unpaired';
        activeId.value = state.activeId ?? null;
        paired.value = Array.isArray(state.paired) ? state.paired : [];
        machineName.value = state.machineName ?? null;
        tz.value = state.tz ?? null;
        vrchatRunning.value = Boolean(state.vrchatRunning);
        steamVrRunning.value = Boolean(state.steamVrRunning);
        syncing.value = Boolean(state.syncing);
        lastError.value = state.lastError ?? null;
        stateVersion.value++;
    }

    /**
     * Applies a `game-state` event (process flags only).
     *
     * @param {{ isGameRunning?: boolean; isSteamVRRunning?: boolean }} gameState
     */
    function applyGameState(gameState) {
        if (!gameState || typeof gameState !== 'object') return;
        if ('isGameRunning' in gameState) {
            vrchatRunning.value = Boolean(gameState.isGameRunning);
        }
        if ('isSteamVRRunning' in gameState) {
            steamVrRunning.value = Boolean(gameState.isSteamVRRunning);
        }
    }

    function requireHost() {
        const host = getAndroidHost();
        if (!host) {
            throw new Error('InvalidOperationException: AndroidHost is not available');
        }
        return host;
    }

    /**
     * Runs one native companion call, applies the returned state and reports the outcome.
     *
     * @param {'scan' | 'discover' | 'pair' | 'forget' | 'setActive'} kind
     * @param {(host: any) => Promise<any>} call
     * @returns {Promise<{ ok: boolean; cancelled?: boolean; error?: { key: string; detail: string }; result?: any }>}
     */
    async function run(kind, call) {
        pending.value = kind;
        try {
            const result = await call(requireHost());
            return { ok: true, result };
        } catch (error) {
            if (isCancelledError(error)) {
                return { ok: false, cancelled: true };
            }
            console.error(`Companion ${kind} failed`, error);
            return { ok: false, error: describeCompanionError(error) };
        } finally {
            pending.value = null;
        }
    }

    /** Reloads the state from native. Never throws. */
    async function refresh() {
        const host = getAndroidHost();
        if (!host) return;
        try {
            applyState(await host.CompanionGetState());
        } catch (error) {
            console.error('CompanionGetState failed', error);
        }
    }

    function scanQr() {
        return run('scan', async (host) => {
            applyState(await host.CompanionScanQr());
        });
    }

    /**
     * @param {number} [timeoutMs]
     * @returns {Promise<{
     *     ok: boolean;
     *     result?: { id: string; name: string; host: string; port: number; fp: string; pairing: boolean }[];
     * }>}
     */
    function discover(timeoutMs = COMPANION_DISCOVERY_TIMEOUT_MS) {
        return run('discover', async (host) => {
            const found = await host.CompanionDiscover(timeoutMs);
            return Array.isArray(found) ? found : [];
        });
    }

    /**
     * @param {{ host: string; port: number; fp?: string; id?: string; name?: string }} target
     * @param {string} code Pairing code as typed; normalized before sending
     */
    function pair(target, code) {
        const payload = { host: target.host, port: target.port };
        for (const key of ['fp', 'id', 'name']) {
            if (target[key]) payload[key] = target[key];
        }
        return run('pair', async (host) => {
            applyState(await host.CompanionPair(payload, normalizePairingCode(code)));
        });
    }

    /** @param {string} id */
    function forget(id) {
        return run('forget', async (host) => {
            applyState(await host.CompanionForget(id));
        });
    }

    /** @param {string} id */
    function setActive(id) {
        return run('setActive', async (host) => {
            applyState(await host.CompanionSetActive(id));
        });
    }

    return {
        status,
        activeId,
        paired,
        machineName,
        tz,
        vrchatRunning,
        steamVrRunning,
        syncing,
        lastError,
        stateVersion,
        pending,
        isPaired,
        isConnected,
        activePc,
        applyState,
        applyGameState,
        refresh,
        scanQr,
        discover,
        pair,
        forget,
        setActive
    };
});

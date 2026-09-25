// Platform layer: native event listeners, companion store wiring, first-run defaults.
// Runs once from platform/android/index.js before the app mounts (docs/ARCHITECTURE.md §5.2).
// Everything here is event driven: no timers, no polling (docs/ARCHITECTURE.md §7).
import { watch } from 'vue';

import { getAndroidHost, onAndroidEvent } from '../../shared/utils/platform.js';
import { useCompanionStore } from './companionStore.js';
import { watchState } from '../../services/watchState.js';

import configRepository from '../../services/config.js';

/** Config key: the one-time notification permission request after the first login has happened. */
export const NOTIFICATION_PERMISSION_ASKED_KEY = 'VRCX_androidNotificationPermissionAsked';

/**
 * First-run defaults that only apply on Android. Must finish before the settings stores are created (App.vue).
 *
 * - `desktopToast` is seeded to `Always` when it was never set, so Android notifications work out of the box
 *   (docs/ARCHITECTURE.md §9). An existing value, including one imported from a PC, is kept.
 * - `launchAsDesktop` is cleared: "Start as desktop" has no meaning on a phone and its menu entry is hidden.
 *
 * @param {Pick<typeof configRepository, 'getString'|'setString'|'getBool'|'setBool'>} config
 */
export async function applyAndroidDefaults(config = configRepository) {
    try {
        const desktopToast = await config.getString('VRCX_desktopToast', null);
        if (desktopToast === null || desktopToast === '') {
            await config.setString('VRCX_desktopToast', 'Always');
        }
        if (await config.getBool('launchAsDesktop', false)) {
            await config.setBool('launchAsDesktop', false);
        }
    } catch (error) {
        console.error('Failed to apply Android defaults', error);
    }
}

/**
 * Keeps the companion store in sync with native: loads the current state once and then follows the
 * `companion-state` and `game-state` events. The initial load is not awaited, so startup never waits on it, and it
 * is dropped when an event arrived first (the event is newer).
 *
 * @param {ReturnType<typeof useCompanionStore>} companionStore
 * @param {object} [deps]
 * @param {typeof onAndroidEvent} [deps.on]
 * @param {typeof getAndroidHost} [deps.getHost]
 * @returns {{ ready: Promise<void>, dispose: () => void }}
 */
export function connectCompanionState(companionStore, { on = onAndroidEvent, getHost = getAndroidHost } = {}) {
    let eventSeen = false;
    const offState = on('companion-state', (state) => {
        eventSeen = true;
        companionStore.applyState(state);
    });
    const offGame = on('game-state', (gameState) => {
        companionStore.applyGameState(gameState);
    });

    const host = getHost();
    const ready = (async () => {
        if (!host) return;
        try {
            const state = await host.CompanionGetState();
            if (!eventSeen) {
                companionStore.applyState(state);
            }
        } catch (error) {
            console.error('CompanionGetState failed', error);
        }
    })();

    return {
        ready,
        dispose() {
            offState();
            offGame();
        }
    };
}

/**
 * Asks for the Android notification permission once, after the first successful login
 * (docs/ARCHITECTURE.md §6.11). Nothing happens when the permission was already granted or denied.
 *
 * @param {object} [deps]
 * @param {Pick<typeof configRepository, 'getBool'|'setBool'>} [deps.config]
 * @param {typeof getAndroidHost} [deps.getHost]
 * @returns {Promise<string|null>} the permission state after the request, or `null` when nothing was asked
 */
export async function requestNotificationPermissionOnce({ config = configRepository, getHost = getAndroidHost } = {}) {
    const host = getHost();
    if (!host) return null;
    try {
        if (await config.getBool(NOTIFICATION_PERMISSION_ASKED_KEY, false)) {
            return null;
        }
        await config.setBool(NOTIFICATION_PERMISSION_ASKED_KEY, true);
        const current = await host.GetNotificationPermission();
        if (current !== 'default') {
            return null;
        }
        return await host.RequestNotificationPermission();
    } catch (error) {
        console.error('Notification permission request failed', error);
        return null;
    }
}

/**
 * Calls `requestNotificationPermissionOnce` the first time `watchState.isLoggedIn` becomes true.
 *
 * @param {object} [deps]
 * @param {{ isLoggedIn: boolean }} [deps.state]
 * @param {() => Promise<unknown>} [deps.request]
 * @returns {() => void} stop function
 */
export function watchFirstLogin({ state = watchState, request = () => requestNotificationPermissionOnce() } = {}) {
    let done = false;
    const stop = watch(
        () => state.isLoggedIn,
        (isLoggedIn) => {
            if (!isLoggedIn || done) return;
            done = true;
            request();
            // `stop` is not yet assigned when the watcher fires immediately.
            queueMicrotask(() => stop?.());
        },
        { immediate: true }
    );
    return stop;
}

/**
 * @param {import('vue').App} app
 */
export async function initAndroidPlatform(app) {
    await applyAndroidDefaults();
    connectCompanionState(useCompanionStore());
    watchFirstLogin();
}

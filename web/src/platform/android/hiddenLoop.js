// The update loop while VRCX runs in the background on Android (docs/ARCHITECTURE.md §7).
//
// Upstream's updateLoop (stores/updateLoop.js) ticks every second and counts its timers in ticks. While the page is
// hidden that wakes the device every second for work that can wait, so on Android:
// - the loop sleeps HIDDEN_LOOP_INTERVAL_MS between ticks, and the next tick first advances every countdown by the
//   seconds it skipped, so a "refresh every 5 minutes" timer still fires after 5 minutes;
// - the game log is read when the host says there is something to read (`log-available`, `game-state`) instead of
//   on every tick;
// - as soon as the page is visible again, a sleeping tick runs at once and the loop is back to one tick per second.
import * as workerTimers from 'worker-timers';

import { onAndroidEvent } from '../../shared/utils/platform.js';

/** Tick interval of the update loop while the page is hidden. */
export const HIDDEN_LOOP_INTERVAL_MS = 15000;

/** Upstream tick interval (one tick counts one second). */
export const VISIBLE_LOOP_INTERVAL_MS = 1000;

/**
 * Runs `task` one call at a time. A call made while a run is in progress returns that run; with `again` it also
 * makes the task run once more afterwards (new log lines may have arrived after the running call read them).
 * A failing task rejects the returned promise, like a direct call would.
 *
 * @param {() => Promise<unknown>} task
 * @returns {(again?: boolean) => Promise<void>}
 */
export function createSerialRunner(task) {
    let running = null;
    let rerun = false;

    async function loop() {
        do {
            rerun = false;
            await task();
        } while (rerun);
    }

    return function run(again = false) {
        if (running) {
            if (again) rerun = true;
            return running;
        }
        running = loop().finally(() => {
            running = null;
            // A signal that came while the last pass was finishing (or failing) still gets its pass.
            if (rerun) {
                rerun = false;
                run().catch((error) => console.error(error));
            }
        });
        return running;
    };
}

/**
 * Subtracts `seconds` from every countdown in the loop state (all its numeric fields count down in seconds).
 *
 * @param {Record<string, unknown>} state
 * @param {number} seconds
 */
export function advanceCountdowns(state, seconds) {
    if (!(seconds > 0)) return;
    for (const [key, value] of Object.entries(state)) {
        if (typeof value === 'number') {
            state[key] = value - seconds;
        }
    }
}

/**
 * @param {object} deps
 * @param {() => Promise<unknown>} deps.runGameLogFlow Reads the game state and the new log lines
 * @param {() => boolean} deps.isLoggedIn
 * @param {Document} [deps.doc]
 * @param {{ setTimeout: (fn: () => void, delay: number) => number; clearTimeout?: (id: number) => void }} [deps.timers]
 * @param {() => number} [deps.now]
 * @param {typeof onAndroidEvent} [deps.on]
 * @param {number} [deps.hiddenIntervalMs]
 */
export function createHiddenLoopController({
    runGameLogFlow,
    isLoggedIn,
    doc = typeof document === 'undefined' ? null : document,
    timers = workerTimers,
    now = () => Date.now(),
    on = onAndroidEvent,
    hiddenIntervalMs = HIDDEN_LOOP_INTERVAL_MS
}) {
    /** @type {{ id: number; fn: () => void; scheduledAt: number; delay: number } | null} */
    let pending = null;
    let skippedSeconds = 0;

    const runGameLog = createSerialRunner(runGameLogFlow);

    function isHidden() {
        return Boolean(doc?.hidden);
    }

    function fire(entry) {
        if (pending !== entry) return;
        pending = null;
        if (entry.delay > VISIBLE_LOOP_INTERVAL_MS) {
            // The tick itself counts one second; add the others it slept (at most the planned interval, so a device
            // that slept for an hour does not fire every timer at once).
            const slept = Math.min(Math.max(now() - entry.scheduledAt, 0), entry.delay);
            skippedSeconds += Math.max(0, Math.round(slept / 1000) - 1);
        }
        entry.fn();
    }

    /**
     * Schedules the next tick: one second while visible, HIDDEN_LOOP_INTERVAL_MS while hidden.
     *
     * @param {() => void} fn
     */
    function schedule(fn) {
        const delay = isHidden() ? hiddenIntervalMs : VISIBLE_LOOP_INTERVAL_MS;
        const entry = { id: 0, fn, scheduledAt: now(), delay };
        pending = entry;
        entry.id = timers.setTimeout(() => fire(entry), delay);
    }

    /**
     * Advances the loop's countdowns by the seconds the last tick slept beyond one (only while logged in, when the
     * loop counts them down at all).
     *
     * @param {Record<string, unknown>} state
     * @param {boolean} loggedIn
     */
    function catchUp(state, loggedIn) {
        const seconds = skippedSeconds;
        skippedSeconds = 0;
        if (loggedIn) {
            advanceCountdowns(state, seconds);
        }
    }

    function onVisibilityChange() {
        if (isHidden() || !pending || pending.delay <= VISIBLE_LOOP_INTERVAL_MS) return;
        const entry = pending;
        try {
            timers.clearTimeout?.(entry.id);
        } catch {
            // Already fired.
        }
        fire(entry);
    }

    function onHostSignal() {
        // While visible the loop reads the log every tick; hidden, the host's signal is the only trigger.
        if (isHidden() && isLoggedIn()) {
            runGameLog(true).catch((error) => console.error(error));
        }
    }

    doc?.addEventListener?.('visibilitychange', onVisibilityChange);
    const subscribe = typeof on === 'function' ? on : () => () => {};
    const offLog = subscribe('log-available', onHostSignal);
    const offGame = subscribe('game-state', onHostSignal);

    return {
        schedule,
        catchUp,
        isHidden,
        runGameLog,
        dispose() {
            doc?.removeEventListener?.('visibilitychange', onVisibilityChange);
            offLog();
            offGame();
            if (pending) {
                try {
                    timers.clearTimeout?.(pending.id);
                } catch {
                    // Already fired.
                }
                pending = null;
            }
        }
    };
}

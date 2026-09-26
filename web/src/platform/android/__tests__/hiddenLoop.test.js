import { describe, expect, test, vi } from 'vitest';

// The update loop while VRCX runs in the background (docs/ARCHITECTURE.md §7).
vi.mock('worker-timers', () => ({ setTimeout: vi.fn(), clearTimeout: vi.fn() }));

import {
    HIDDEN_LOOP_INTERVAL_MS,
    VISIBLE_LOOP_INTERVAL_MS,
    advanceCountdowns,
    createHiddenLoopController,
    createSerialRunner
} from '../hiddenLoop.js';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function createDoc(hidden = false) {
    const listeners = new Set();
    return {
        hidden,
        addEventListener: (name, fn) => listeners.add(fn),
        removeEventListener: (name, fn) => listeners.delete(fn),
        setHidden(value) {
            this.hidden = value;
            listeners.forEach((fn) => fn());
        },
        listenerCount: () => listeners.size
    };
}

function createTimers() {
    let nextId = 1;
    const pending = new Map();
    return {
        setTimeout: vi.fn((fn, delay) => {
            const id = nextId++;
            pending.set(id, { fn, delay });
            return id;
        }),
        clearTimeout: vi.fn((id) => pending.delete(id)),
        fireLast() {
            const id = [...pending.keys()].at(-1);
            const { fn } = pending.get(id);
            pending.delete(id);
            fn();
        },
        pending
    };
}

function createEvents() {
    const handlers = new Map();
    return {
        on: (name, handler) => {
            handlers.set(name, handler);
            return () => handlers.delete(name);
        },
        emit: (name, payload) => handlers.get(name)?.(payload),
        has: (name) => handlers.has(name)
    };
}

describe('createSerialRunner', () => {
    test('runs one call at a time and repeats once for signals that came meanwhile', async () => {
        let release;
        const task = vi.fn(() => new Promise((resolve) => (release = resolve)));
        const run = createSerialRunner(task);

        const first = run(true);
        expect(run()).toBe(first);
        run(true);
        run(true);
        expect(task).toHaveBeenCalledTimes(1);

        release();
        await flush();
        expect(task).toHaveBeenCalledTimes(2);
        release();
        await first;
        await flush();
        expect(task).toHaveBeenCalledTimes(2);
    });

    test('a failing task rejects its callers and the next call runs again', async () => {
        const task = vi.fn().mockRejectedValueOnce(new Error('bridge')).mockResolvedValue(undefined);
        const run = createSerialRunner(task);
        await expect(run()).rejects.toThrow('bridge');
        await expect(run()).resolves.toBeUndefined();
        expect(task).toHaveBeenCalledTimes(2);
    });
});

describe('advanceCountdowns', () => {
    test('subtracts the seconds from every countdown', () => {
        const state = { nextCurrentUserRefresh: 300, nextGetLogCheck: 0.5, label: 'x' };
        advanceCountdowns(state, 14);
        expect(state).toEqual({ nextCurrentUserRefresh: 286, nextGetLogCheck: -13.5, label: 'x' });
        advanceCountdowns(state, 0);
        expect(state.nextCurrentUserRefresh).toBe(286);
    });
});

describe('createHiddenLoopController', () => {
    function setup({ hidden = false, loggedIn = true } = {}) {
        const doc = createDoc(hidden);
        const timers = createTimers();
        const events = createEvents();
        let time = 1_000_000;
        const runGameLogFlow = vi.fn().mockResolvedValue(undefined);
        const controller = createHiddenLoopController({
            runGameLogFlow,
            isLoggedIn: () => loggedIn,
            doc,
            timers,
            now: () => time,
            on: events.on
        });
        return {
            doc,
            timers,
            events,
            runGameLogFlow,
            controller,
            advance: (ms) => {
                time += ms;
            }
        };
    }

    test('ticks every second while visible, and every 15 s while hidden', () => {
        const { controller, timers, doc } = setup();
        controller.schedule(() => {});
        expect(timers.setTimeout).toHaveBeenLastCalledWith(expect.any(Function), VISIBLE_LOOP_INTERVAL_MS);
        doc.hidden = true;
        controller.schedule(() => {});
        expect(timers.setTimeout).toHaveBeenLastCalledWith(expect.any(Function), HIDDEN_LOOP_INTERVAL_MS);
    });

    test('a hidden tick counts the seconds it slept', () => {
        const { controller, timers, advance } = setup({ hidden: true });
        const tick = vi.fn();
        controller.schedule(tick);
        advance(HIDDEN_LOOP_INTERVAL_MS);
        timers.fireLast();
        expect(tick).toHaveBeenCalledTimes(1);

        const state = { nextCurrentUserRefresh: 300 };
        controller.catchUp(state, true);
        expect(state.nextCurrentUserRefresh).toBe(300 - 14);
        controller.catchUp(state, true);
        expect(state.nextCurrentUserRefresh).toBe(286);
    });

    test('a late hidden tick (the device slept) counts at most its interval', () => {
        const { controller, timers, advance } = setup({ hidden: true });
        controller.schedule(() => {});
        advance(60 * 60 * 1000);
        timers.fireLast();
        const state = { nextFriendsRefresh: 3600 };
        controller.catchUp(state, true);
        expect(state.nextFriendsRefresh).toBe(3600 - 14);
    });

    test('logged out, the skipped seconds are dropped (the loop counts nothing then)', () => {
        const { controller, timers, advance } = setup({ hidden: true });
        controller.schedule(() => {});
        advance(HIDDEN_LOOP_INTERVAL_MS);
        timers.fireLast();
        const state = { nextCurrentUserRefresh: 300 };
        controller.catchUp(state, false);
        controller.catchUp(state, true);
        expect(state.nextCurrentUserRefresh).toBe(300);
    });

    test('showing the app runs a sleeping tick at once, counting the time it slept', () => {
        const { controller, timers, doc, advance } = setup({ hidden: true });
        const tick = vi.fn();
        controller.schedule(tick);
        advance(6000);
        doc.setHidden(false);
        expect(timers.clearTimeout).toHaveBeenCalledTimes(1);
        expect(tick).toHaveBeenCalledTimes(1);
        const state = { nextAutoStateChange: 10 };
        controller.catchUp(state, true);
        expect(state.nextAutoStateChange).toBe(5);

        // Hiding does not touch a one-second tick.
        controller.schedule(tick);
        doc.setHidden(true);
        doc.setHidden(false);
        expect(tick).toHaveBeenCalledTimes(1);
    });

    test('while hidden and logged in, the host signals read the game log', async () => {
        const { events, runGameLogFlow, doc } = setup({ hidden: true });
        events.emit('log-available');
        events.emit('game-state', { isGameRunning: true });
        await flush();
        expect(runGameLogFlow).toHaveBeenCalledTimes(2);

        doc.hidden = false;
        events.emit('log-available');
        await flush();
        expect(runGameLogFlow).toHaveBeenCalledTimes(2);
    });

    test('logged out, the host signals are ignored', async () => {
        const { events, runGameLogFlow } = setup({ hidden: true, loggedIn: false });
        events.emit('log-available');
        await flush();
        expect(runGameLogFlow).not.toHaveBeenCalled();
    });

    test('dispose removes the listeners and the pending tick', () => {
        const { controller, events, doc, timers } = setup({ hidden: true });
        controller.schedule(() => {});
        controller.dispose();
        expect(events.has('log-available')).toBe(false);
        expect(events.has('game-state')).toBe(false);
        expect(doc.listenerCount()).toBe(0);
        expect(timers.pending.size).toBe(0);
    });
});

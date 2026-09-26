import { beforeEach, describe, expect, test, vi } from 'vitest';

vi.mock('../../../services/config', () => ({ default: {} }));

import {
    GAME_LOG_VIEW_MODE_KEY,
    applyAndroidGameLogViewDefault,
    resetGameLogViewDefaultForTests
} from '../gameLogViewDefaults';

function createRepository(initial = {}) {
    const values = new Map(Object.entries(initial));
    return {
        values,
        getString: vi.fn(async (key, fallback) => (values.has(key) ? values.get(key) : fallback)),
        setString: vi.fn(async (key, value) => {
            values.set(key, value);
        })
    };
}

function createStore(mode) {
    const store = {
        sessionsViewMode: mode,
        setSessionsViewMode: vi.fn(async (next) => {
            store.sessionsViewMode = next;
        })
    };
    return store;
}

describe('Android game log view default', () => {
    beforeEach(() => {
        resetGameLogViewDefaultForTests();
    });

    test('switches to sessions and saves it when no mode was ever chosen', async () => {
        const repository = createRepository();
        // The store fell back to upstream's `table` default.
        const store = createStore('table');

        await expect(applyAndroidGameLogViewDefault(store, repository)).resolves.toBe(true);

        expect(repository.values.get(GAME_LOG_VIEW_MODE_KEY)).toBe('sessions');
        expect(store.setSessionsViewMode).toHaveBeenCalledWith('sessions');
        expect(store.sessionsViewMode).toBe('sessions');
    });

    test('persists the default even when the store already shows sessions', async () => {
        const repository = createRepository();
        const store = createStore('sessions');

        await applyAndroidGameLogViewDefault(store, repository);

        expect(repository.values.get(GAME_LOG_VIEW_MODE_KEY)).toBe('sessions');
        expect(store.setSessionsViewMode).not.toHaveBeenCalled();
    });

    test('keeps a saved choice', async () => {
        const repository = createRepository({ [GAME_LOG_VIEW_MODE_KEY]: 'table' });
        const store = createStore('table');

        await expect(applyAndroidGameLogViewDefault(store, repository)).resolves.toBe(false);

        expect(repository.setString).not.toHaveBeenCalled();
        expect(store.setSessionsViewMode).not.toHaveBeenCalled();
        expect(store.sessionsViewMode).toBe('table');
    });

    test('runs once per app session', async () => {
        const repository = createRepository();
        const store = createStore('table');

        const first = applyAndroidGameLogViewDefault(store, repository);
        const second = applyAndroidGameLogViewDefault(store, repository);
        expect(second).toBe(first);
        await first;

        // The user switched back to the table later: the default must not run again.
        store.sessionsViewMode = 'table';
        await applyAndroidGameLogViewDefault(store, repository);
        expect(store.setSessionsViewMode).toHaveBeenCalledTimes(1);
        expect(repository.getString).toHaveBeenCalledTimes(1);
    });

    test('a failed read can be retried', async () => {
        const repository = createRepository();
        repository.getString.mockRejectedValueOnce(new Error('bridge down'));
        const store = createStore('table');
        const error = vi.spyOn(console, 'error').mockImplementation(() => {});

        await expect(applyAndroidGameLogViewDefault(store, repository)).resolves.toBe(false);
        await expect(applyAndroidGameLogViewDefault(store, repository)).resolves.toBe(true);

        expect(store.sessionsViewMode).toBe('sessions');
        error.mockRestore();
    });
});

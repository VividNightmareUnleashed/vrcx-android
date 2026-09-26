import { afterAll, describe, expect, test, vi } from 'vitest';

import { PREVIOUS_INSTANCE_LOCATION, lateGameStart, previousInstanceRows, sqlite } from '../fixes.js';

// mockBridge.js loads every mock; fake timers keep their start-up timers (views-a.js) from running here.
vi.useFakeTimers();
globalThis.VERSION = 'preview';
const { createAppApi } = await import('../../mockBridge.js');

afterAll(() => {
    vi.useRealTimers();
});

describe('harness appApi hook', () => {
    test('a hook value wins, undefined falls through to the Android safe values', async () => {
        const hook = vi.fn((method) => (method === 'IsGameRunning' ? false : undefined));
        const appApi = createAppApi('playing', {}, hook);

        expect(appApi.IsGameRunning()).toBe(false);
        expect(appApi.IsSteamVRRunning()).toBe(false);
        expect(appApi.GetZoom()).toBe(1);
        expect(appApi.GetColourBulk(['usr_a'])).toEqual([['usr_a', 0]]);
        expect(appApi.SomethingUnknown()).toBeNull();
        expect(hook).toHaveBeenCalledWith('GetColourBulk', [['usr_a']]);
    });

    test('without a hook answer the companion mode decides whether VRChat runs', () => {
        const none = () => undefined;
        expect(createAppApi('playing', {}, none).IsGameRunning()).toBe(true);
        expect(createAppApi('connected', {}, none).IsGameRunning()).toBe(false);
    });
});

describe('gamestart (late companion start)', () => {
    test('reports VRChat stopped until the delay passed', () => {
        expect(lateGameStart('IsGameRunning', 1000, 65000)).toBe(false);
        expect(lateGameStart('IsSteamVRRunning', 1000, 65000)).toBe(false);
        expect(lateGameStart('GetZoom', 1000, 65000)).toBeUndefined();
        expect(lateGameStart('IsGameRunning', 65000, 65000)).toBeUndefined();
        expect(lateGameStart('IsGameRunning', 0, 0)).toBeUndefined();
    });
});

describe('Previous Instances rows', () => {
    const at = (location) => new Map([['@location', location]]);

    test('answers the chart query with leave rows of the current instance', () => {
        const rows = sqlite(
            `SELECT created_at, display_name, user_id, time
             FROM gamelog_join_leave
             WHERE location = @location AND type = 'OnPlayerLeft'
             ORDER BY created_at ASC`,
            at(PREVIOUS_INSTANCE_LOCATION)
        );
        expect(rows.length).toBe(previousInstanceRows().length / 2);
        expect(rows.every((row) => row.length === 4 && row[3] > 0)).toBe(true);
    });

    test('answers the table query with joins and leaves', () => {
        const rows = sqlite(
            'SELECT created_at, display_name, user_id, time, type FROM gamelog_join_leave WHERE location = @location',
            at(PREVIOUS_INSTANCE_LOCATION)
        );
        expect(new Set(rows.map((row) => row[4]))).toEqual(new Set(['OnPlayerJoined', 'OnPlayerLeft']));
    });

    test('falls through for other instances and queries', () => {
        expect(
            sqlite(
                'SELECT created_at, display_name, user_id, time, type FROM gamelog_join_leave WHERE location = @location',
                at('wrld_other:1')
            )
        ).toBeUndefined();
        expect(sqlite('SELECT * FROM gamelog_location', new Map())).toBeUndefined();
    });
});

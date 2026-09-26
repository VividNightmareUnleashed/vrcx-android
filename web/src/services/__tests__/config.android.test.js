import { beforeEach, describe, expect, test, vi } from 'vitest';

// Android: ConfigRepository loads the configs table once and serves reads from memory, writing through
// (docs/ARCHITECTURE.md §7). Other platforms read SQLite every time, as upstream.
const mocks = vi.hoisted(() => ({
    android: true,
    rows: new Map(),
    execute: null,
    executeNonQuery: null
}));

vi.mock('../../shared/utils/platform.js', () => ({
    get isAndroid() {
        return mocks.android;
    }
}));
vi.mock('../sqlite.js', () => {
    mocks.execute = vi.fn(async (callback, sql, args) => {
        if (/^SELECT key, value FROM configs$/.test(sql)) {
            for (const entry of mocks.rows) callback(entry);
        } else if (/^SELECT value FROM configs WHERE key = @key/.test(sql)) {
            const key = args['@key'];
            if (mocks.rows.has(key)) callback([mocks.rows.get(key)]);
        }
    });
    mocks.executeNonQuery = vi.fn(async (sql, args) => {
        if (/^INSERT OR REPLACE INTO configs/.test(sql)) mocks.rows.set(args['@key'], args['@value']);
        if (/^DELETE FROM configs/.test(sql)) mocks.rows.delete(args['@key']);
        return 1;
    });
    return { default: { execute: mocks.execute, executeNonQuery: mocks.executeNonQuery } };
});

import { ConfigRepository } from '../config.js';

describe('ConfigRepository on Android', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mocks.android = true;
        mocks.rows = new Map([
            ['config:vrcx_thememode', 'dark'],
            ['config:vrcx_tablepagesize', '25'],
            ['config:vrcx_flag', 'true'],
            ['config:vrcx_broken', 'undefined']
        ]);
    });

    test('loads the table once at init and reads from memory', async () => {
        const config = new ConfigRepository();
        await config.init();
        expect(mocks.execute).toHaveBeenCalledTimes(1);

        await expect(config.getString('VRCX_themeMode')).resolves.toBe('dark');
        await expect(config.getInt('VRCX_tablePageSize', 10)).resolves.toBe(25);
        await expect(config.getBool('VRCX_flag', false)).resolves.toBe(true);
        await expect(config.getString('VRCX_missing', 'fallback')).resolves.toBe('fallback');
        await expect(config.getString('VRCX_broken', 'fallback')).resolves.toBe('fallback');
        expect(mocks.execute).toHaveBeenCalledTimes(1);
    });

    test('writes through: a read right after a write sees the new value, and SQLite gets it', async () => {
        const config = new ConfigRepository();
        await config.init();

        const write = config.setString('VRCX_themeMode', 'light');
        await expect(config.getString('VRCX_themeMode')).resolves.toBe('light');
        await write;
        expect(mocks.rows.get('config:vrcx_thememode')).toBe('light');

        await config.setBool('VRCX_new', true);
        await expect(config.getBool('VRCX_new')).resolves.toBe(true);

        await config.remove('VRCX_themeMode');
        await expect(config.getString('VRCX_themeMode', 'none')).resolves.toBe('none');
        expect(mocks.rows.has('config:vrcx_thememode')).toBe(false);
        expect(mocks.execute).toHaveBeenCalledTimes(1);
    });

    test('a failed write is undone in memory', async () => {
        const config = new ConfigRepository();
        await config.init();
        mocks.executeNonQuery.mockRejectedValueOnce(new Error('SQLiteException: disk full'));
        await expect(config.setString('VRCX_themeMode', 'light')).rejects.toThrow('disk full');
        await expect(config.getString('VRCX_themeMode')).resolves.toBe('dark');

        mocks.executeNonQuery.mockRejectedValueOnce(new Error('SQLiteException: disk full'));
        await expect(config.setString('VRCX_other', '1')).rejects.toThrow('disk full');
        await expect(config.getString('VRCX_other', 'none')).resolves.toBe('none');

        mocks.executeNonQuery.mockRejectedValueOnce(new Error('SQLiteException: locked'));
        await expect(config.remove('VRCX_themeMode')).rejects.toThrow('locked');
        await expect(config.getString('VRCX_themeMode')).resolves.toBe('dark');
    });

    test('when the table cannot be loaded, reads query SQLite one by one', async () => {
        const error = vi.spyOn(console, 'error').mockImplementation(() => {});
        mocks.execute.mockRejectedValueOnce(new Error('SQLiteException: busy'));
        const config = new ConfigRepository();
        await config.init();
        expect(config.cache).toBeNull();
        await expect(config.getString('VRCX_themeMode')).resolves.toBe('dark');
        expect(mocks.execute).toHaveBeenCalledTimes(2);
        error.mockRestore();
    });

    test('other platforms keep reading SQLite for every value', async () => {
        mocks.android = false;
        const config = new ConfigRepository();
        await config.init();
        expect(config.cache).toBeNull();
        expect(mocks.execute).not.toHaveBeenCalled();
        await config.getString('VRCX_themeMode');
        await config.getString('VRCX_themeMode');
        expect(mocks.execute).toHaveBeenCalledTimes(2);
    });
});

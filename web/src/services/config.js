import sqliteService from './sqlite.js';
import { isAndroid } from '../shared/utils/platform.js';

/**
 * @param key
 */
function transformKey(key) {
    return `config:${String(key).toLowerCase()}`;
}

class ConfigRepository {
    /**
     * Android: every config value, loaded once by init() and kept in step by the writes below, so reads do not
     * cross the bridge (about 180 reads at start-up; docs/ARCHITECTURE.md §7). Nothing else writes the table, and a
     * database import restarts the app. `null` elsewhere: every read queries SQLite, as upstream.
     *
     * @type {Map<string, string> | null}
     */
    cache = null;

    async init() {
        await sqliteService.executeNonQuery(
            'CREATE TABLE IF NOT EXISTS configs (`key` TEXT PRIMARY KEY, `value` TEXT)'
        );
        if (isAndroid) {
            await this.loadCache();
        }
    }

    async loadCache() {
        const cache = new Map();
        try {
            await sqliteService.execute((row) => {
                cache.set(row[0], row[1]);
            }, 'SELECT key, value FROM configs');
            this.cache = cache;
        } catch (error) {
            // Reads then query SQLite one by one, as upstream.
            console.error('Failed to load the config cache', error);
            this.cache = null;
        }
    }

    async remove(key) {
        const _key = transformKey(key);
        if (this.cache) {
            const previous = this.cache.get(_key);
            this.cache.delete(_key);
            try {
                await sqliteService.executeNonQuery(`DELETE FROM configs WHERE key = @key`, { '@key': _key });
            } catch (error) {
                // Undo, unless a later write already replaced the entry.
                if (previous !== undefined && !this.cache.has(_key)) this.cache.set(_key, previous);
                throw error;
            }
            return;
        }
        await sqliteService.executeNonQuery(`DELETE FROM configs WHERE key = @key`, {
            '@key': _key
        });
    }

    /**
     * @param {string} key
     * @param {string} defaultValue
     * @returns {Promise<string | null>}
     */
    async getString(key, defaultValue = null) {
        const _key = transformKey(key);
        let value = undefined;
        if (this.cache) {
            value = this.cache.get(_key);
            if (value === null || value === undefined || value === 'undefined') {
                return defaultValue;
            }
            return value;
        }
        await sqliteService.execute(
            (row) => {
                value = row[0];
            },
            `SELECT value FROM configs WHERE key = @key`,
            {
                '@key': _key
            }
        );

        if (value === null || value === undefined || value === 'undefined') {
            return defaultValue;
        }
        return value;
    }

    /**
     * @param {string} key
     * @param {string} value
     * @returns {Promise<void>}
     */
    async setString(key, value) {
        const _key = transformKey(key);
        const _value = String(value);
        if (this.cache) {
            // Written through: later reads see the value at once, as they would after the queued SQLite write.
            const previous = this.cache.get(_key);
            this.cache.set(_key, _value);
            try {
                await sqliteService.executeNonQuery(
                    `INSERT OR REPLACE INTO configs (key, value) VALUES (@key, @value)`,
                    { '@key': _key, '@value': _value }
                );
            } catch (error) {
                // Undo, unless a later write already replaced the value.
                if (this.cache.get(_key) === _value) {
                    if (previous === undefined) this.cache.delete(_key);
                    else this.cache.set(_key, previous);
                }
                throw error;
            }
            return;
        }
        await sqliteService.executeNonQuery(`INSERT OR REPLACE INTO configs (key, value) VALUES (@key, @value)`, {
            '@key': _key,
            '@value': _value
        });
    }

    /**
     * @param {string} key
     * @param {boolean} defaultValue
     * @returns {Promise<boolean | null>}
     */
    async getBool(key, defaultValue = null) {
        const value = await this.getString(key, null);
        if (value === null || value === undefined) {
            return defaultValue;
        }
        return value === 'true';
    }

    /**
     * @param {string} key
     * @param {boolean} value
     * @returns {Promise<void>}
     */
    async setBool(key, value) {
        await this.setString(key, value ? 'true' : 'false');
    }

    /**
     * @param {string} key
     * @param {number} defaultValue
     * @returns {Promise<number | null>}
     */
    async getInt(key, defaultValue = null) {
        let value = await this.getString(key, null);
        if (value === null || value === undefined) {
            return defaultValue;
        }
        value = parseInt(value, 10);
        if (isNaN(value) === true) {
            return defaultValue;
        }
        return value;
    }

    async setInt(key, value) {
        await this.setString(key, value);
    }

    async getFloat(key, defaultValue = null) {
        let value = await this.getString(key, null);
        if (value === null || value === undefined) {
            return defaultValue;
        }
        value = parseFloat(value);
        if (isNaN(value) === true) {
            return defaultValue;
        }
        return value;
    }

    async setFloat(key, value) {
        await this.setString(key, value);
    }

    async getObject(key, defaultValue = null) {
        let value = await this.getString(key, null);
        if (value === null || value === undefined) {
            return defaultValue;
        }
        try {
            value = JSON.parse(value);
        } catch {
            // ignore JSON parse errors
        }
        if (value !== Object(value)) {
            return defaultValue;
        }
        return value;
    }

    async setObject(key, value) {
        await this.setString(key, JSON.stringify(value));
    }

    /**
     * @param {string} key
     * @param {Array} defaultValue
     * @returns {Promise<Array | null>}
     */
    async getArray(key, defaultValue = null) {
        const value = await this.getObject(key, null);
        if (Array.isArray(value) === false) {
            return defaultValue;
        }
        return value;
    }

    async setArray(key, value) {
        await this.setObject(key, value);
    }
}

var self = new ConfigRepository();
window.configRepository = self;

export { self as default, ConfigRepository, transformKey };

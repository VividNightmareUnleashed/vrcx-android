import { describe, expect, test } from 'vitest';

import strings from '../../platform/android/i18n/en.dialogs-a.json';

import instanceActionBar from '../InstanceActionBar.vue?raw';
import userActionMenuButton from '../UserActionMenuButton.vue?raw';

// Android-only strings of the entity dialogs live in platform/android/i18n/en.dialogs-a.json.
const sources = { instanceActionBar, userActionMenuButton };

/**
 * @param {object} messages
 * @param {string} path
 * @returns {unknown}
 */
function lookup(messages, path) {
    return path.split('.').reduce((node, key) => (node && typeof node === 'object' ? node[key] : undefined), messages);
}

describe('entity dialog Android strings', () => {
    test('every android.entity_dialogs.* key used by the components exists', () => {
        const keys = new Set();
        for (const text of Object.values(sources)) {
            for (const [, key] of text.matchAll(/['"`](android\.entity_dialogs\.[a-z_]+)['"`]/g)) {
                keys.add(key);
            }
        }

        expect([...keys].sort()).toEqual([
            'android.entity_dialogs.instance_details',
            'android.entity_dialogs.more_actions'
        ]);
        for (const key of keys) {
            expect(typeof lookup(strings, key), key).toBe('string');
        }
    });

    test('only adds Android keys, never overrides upstream strings', () => {
        expect(Object.keys(strings)).toEqual(['android']);
        expect(Object.keys(strings.android)).toEqual(['entity_dialogs']);
    });
});

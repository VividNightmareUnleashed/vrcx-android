import { describe, expect, test } from 'vitest';

import enViewsA from '../../../platform/android/i18n/en.views-a.json';

// Every Android-only string used by the route views of this area must exist in the area's own string file.
const sources = import.meta.glob(
    [
        '../../{Feed,FriendsLocations,GameLog,PlayerList,Search,Dashboard,FriendList,FriendLog,Moderation,Notifications}/**/*.{vue,js,jsx}',
        '!../../**/__tests__/**'
    ],
    { eager: true, query: '?raw', import: 'default' }
);

function lookup(messages, path) {
    return path.split('.').reduce((node, key) => (node && typeof node === 'object' ? node[key] : undefined), messages);
}

describe('Android strings of the views-a area', () => {
    test('every android.views_a.* key used by the views exists', () => {
        const keys = new Set();
        for (const text of Object.values(sources)) {
            for (const [, key] of text.matchAll(/['"`](android\.views_a\.[a-z_]+(?:\.[a-z_]+)*)['"`]/g)) {
                keys.add(key);
            }
        }
        expect(keys.size).toBeGreaterThan(5);
        const missing = [...keys].filter((key) => typeof lookup(enViewsA, key) !== 'string');
        expect(missing).toEqual([]);
    });

    test('the views only use their own android.* namespace or the shared shell and platform ones', () => {
        const foreign = new Set();
        for (const text of Object.values(sources)) {
            for (const [, key] of text.matchAll(/['"`](android\.([a-z_]+)\.[a-z_.]+)['"`]/g)) {
                const area = key.split('.')[1];
                if (!['views_a', 'shell', 'empty'].includes(area)) foreign.add(key);
            }
        }
        expect([...foreign]).toEqual([]);
    });
});

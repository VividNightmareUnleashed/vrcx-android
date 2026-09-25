import { describe, expect, test } from 'vitest';

import enViewsA from '../../../platform/android/i18n/en.views-a.json';
import { PLAYER_ICON_LEGEND, getPlayerIconLegend } from '../playerIconLegend';

function lookup(messages, path) {
    return path.split('.').reduce((node, key) => (node && typeof node === 'object' ? node[key] : undefined), messages);
}

describe('player list icon legend', () => {
    test('lists only the icons that appear in the current list, in column order', () => {
        const rows = [
            { displayName: 'Aurora', isFriend: true },
            { displayName: 'Kestrel', isChatBoxMuted: true, isMaster: true },
            { displayName: 'Maple', timeoutTime: 0, ageVerified: true }
        ];
        expect(getPlayerIconLegend(rows).map((entry) => entry.key)).toEqual([
            'isMaster',
            'isFriend',
            'isChatBoxMuted',
            'ageVerified'
        ]);
    });

    test('is empty without rows or icons', () => {
        expect(getPlayerIconLegend([])).toEqual([]);
        expect(getPlayerIconLegend(null)).toEqual([]);
        expect(getPlayerIconLegend([{ displayName: 'Nimbus' }])).toEqual([]);
    });

    test('a running timeout counts as an icon', () => {
        expect(getPlayerIconLegend([{ timeoutTime: 12 }]).map((entry) => entry.key)).toEqual(['timeoutTime']);
    });

    test('every legend label has an Android string', () => {
        for (const entry of PLAYER_ICON_LEGEND) {
            expect(typeof lookup(enViewsA, entry.labelKey)).toBe('string');
        }
    });
});

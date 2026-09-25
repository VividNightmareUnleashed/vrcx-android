import { describe, expect, it } from 'vitest';

import en from '../../i18n/en.dialogs-b.json';
import { DOCK_LABEL_MAX_EM, estimateLabelEm, getDockLabel, shortenDockLabel } from '../shellNav';

// Dock labels (docs/DESIGN.md §2.1): long nav titles use a short label or end at a word boundary, never "Friends Lo…".
const labels = {
    'nav_tooltip.feed': 'Feed',
    'nav_tooltip.friends_locations': 'Friends Locations',
    'nav_tooltip.game_log': 'Game Log',
    'prompt.direct_access_omni.header': 'Direct Access',
    'view.charts.mutual_friend.tab_label': 'Mutual Friend Network'
};
const shortLabels = Object.fromEntries(
    Object.entries(en.android.shell.dock_short).map(([key, value]) => [`android.shell.dock_short.${key}`, value])
);
const messages = { ...labels, ...shortLabels };
const t = (key) => messages[key] ?? key;
const te = (key) => key in messages;

describe('estimateLabelEm', () => {
    it('counts Latin characters as half an em and CJK as a full em', () => {
        expect(estimateLabelEm('Game Log')).toBe(4);
        expect(estimateLabelEm('好友位置')).toBe(4);
        expect(estimateLabelEm('')).toBe(0);
    });
});

describe('shortenDockLabel', () => {
    it('keeps labels that fit', () => {
        expect(shortenDockLabel('Game Log')).toBe('Game Log');
        expect(shortenDockLabel('Direct Access')).toBe('Direct Access');
        expect(estimateLabelEm('Direct Access')).toBeLessThanOrEqual(DOCK_LABEL_MAX_EM);
    });

    it('cuts long labels at a word boundary', () => {
        expect(shortenDockLabel('Position des amis')).toBe('Position…');
        expect(shortenDockLabel('Lokalizacja znajomych')).toBe('Lokalizacja…');
        expect(shortenDockLabel('Friends Locations')).toBe('Friends…');
    });

    it('leaves single long words, unspaced scripts and one-word stubs to the CSS ellipsis', () => {
        expect(shortenDockLabel('Benachrichtigungseinstellungen')).toBe('Benachrichtigungseinstellungen');
        expect(shortenDockLabel('フレンドの現在地')).toBe('フレンドの現在地');
        expect(shortenDockLabel('My Favourite Places')).toBe('My Favourite Places');
    });

    it('normalises whitespace', () => {
        expect(shortenDockLabel('  Game   Log ')).toBe('Game Log');
        expect(shortenDockLabel(null)).toBe('');
    });
});

describe('getDockLabel', () => {
    it('uses the short dock label of built-in entries', () => {
        const entry = { index: 'friends-locations', title: 'nav_tooltip.friends_locations' };
        expect(getDockLabel(entry, t, te)).toBe('Locations');
        expect(getDockLabel({ index: 'charts-mutual', title: 'view.charts.mutual_friend.tab_label' }, t, te)).toBe(
            'Mutuals'
        );
    });

    it('falls back to the nav label, shortened at a word boundary, without a short label', () => {
        expect(getDockLabel({ index: 'feed', title: 'nav_tooltip.feed' }, t, te)).toBe('Feed');
        expect(getDockLabel({ index: 'friends-locations', title: 'nav_tooltip.friends_locations' }, t)).toBe(
            'Friends…'
        );
    });

    it("keeps the user's own names for folders and dashboards", () => {
        const folder = { index: 'friends-locations', title: 'Social Places', titleIsCustom: true };
        expect(getDockLabel(folder, t, te)).toBe('Social Places');
        const longFolder = { index: 'folder-1', title: 'Weekend Worlds Folder', titleIsCustom: true };
        expect(getDockLabel(longFolder, t, te)).toBe('Weekend…');
        expect(getDockLabel({ index: 'dashboard-1', title: 'Board', titleIsCustom: true }, t, te)).toBe('Board');
        expect(getDockLabel(null, t, te)).toBe('');
    });

    it('has short labels that fit a slot', () => {
        for (const value of Object.values(en.android.shell.dock_short)) {
            expect(estimateLabelEm(value)).toBeLessThanOrEqual(DOCK_LABEL_MAX_EM);
        }
    });
});

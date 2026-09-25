import { describe, expect, it } from 'vitest';

import { getDockEntries, getEntryLabel, getRouteNavKeys, isEntryActive, resolveRouteTitle } from '../shellNav';

const t = (key) => `t:${key}`;

const definitions = [
    { key: 'feed', icon: 'ri-rss-line', labelKey: 'nav_tooltip.feed', routeName: 'feed' },
    { key: 'game-log', icon: 'ri-history-line', labelKey: 'nav_tooltip.game_log', routeName: 'game-log' },
    { key: 'tools', icon: 'ri-tools-line', labelKey: 'nav_tooltip.tools', routeName: 'tools' },
    { key: 'tool-gallery', icon: 'ri-image-line', labelKey: 'view.tools.gallery', routeName: 'gallery' },
    {
        key: 'dashboard-abc',
        icon: 'ri-star-line',
        labelKey: 'My board',
        routeName: 'dashboard',
        routeParams: { id: 'abc' },
        isDashboard: true
    }
];

const menuItems = [
    { index: 'feed', icon: 'ri-rss-line', title: 'nav_tooltip.feed', titleIsCustom: false },
    {
        index: 'folder-1',
        icon: 'ri-star-line',
        title: 'Favorites',
        titleIsCustom: true,
        children: [
            { index: 'favorite-friends', label: 'nav_tooltip.favorite_friends', titleIsCustom: false },
            { index: 'favorite-worlds', label: 'nav_tooltip.favorite_worlds', titleIsCustom: false }
        ]
    },
    { index: 'dashboard-abc', icon: 'ri-star-line', title: 'My board', titleIsCustom: true },
    { index: 'game-log', icon: 'ri-history-line', title: 'nav_tooltip.game_log', titleIsCustom: false }
];

describe('dock entries', () => {
    it('takes the first three top-level entries of the nav layout, folders and dashboards included', () => {
        expect(getDockEntries(menuItems).map((entry) => entry.index)).toEqual(['feed', 'folder-1', 'dashboard-abc']);
        expect(getDockEntries(null)).toEqual([]);
    });

    it('marks a folder active when one of its children is active', () => {
        expect(isEntryActive(menuItems[1], 'favorite-worlds')).toBe(true);
        expect(isEntryActive(menuItems[1], 'feed')).toBe(false);
        expect(isEntryActive(menuItems[0], 'feed')).toBe(true);
        expect(isEntryActive(menuItems[0], '')).toBe(false);
    });

    it('translates built-in labels and keeps custom ones', () => {
        expect(getEntryLabel(menuItems[0], t)).toBe('t:nav_tooltip.feed');
        expect(getEntryLabel(menuItems[1], t)).toBe('Favorites');
        expect(getEntryLabel(menuItems[1].children[0], t)).toBe('t:nav_tooltip.favorite_friends');
    });
});

describe('app bar title', () => {
    it('uses the route nav entry icon and label', () => {
        expect(resolveRouteTitle({ name: 'game-log', meta: {} }, definitions, t)).toEqual({
            key: 'game-log',
            icon: 'ri-history-line',
            label: 't:nav_tooltip.game_log'
        });
    });

    it("uses a dashboard's own name", () => {
        const title = resolveRouteTitle(
            { name: 'dashboard', params: { id: 'abc' }, meta: { navKey: 'dashboard' } },
            definitions,
            t
        );
        expect(title).toEqual({ key: 'dashboard-abc', icon: 'ri-star-line', label: 'My board' });
    });

    it('prefers the most specific nav key (tools pinned to the nav)', () => {
        const route = { name: 'gallery', meta: { navKeys: ['tool-gallery', 'tools'] } };
        expect(getRouteNavKeys(route)).toEqual(['tool-gallery', 'tools']);
        expect(resolveRouteTitle(route, definitions, t).key).toBe('tool-gallery');
    });

    it('shows Settings for the settings route', () => {
        expect(resolveRouteTitle({ name: 'settings', meta: { navKey: 'manage' } }, definitions, t)).toEqual({
            key: 'settings',
            icon: 'ri-settings-3-line',
            label: 't:nav_tooltip.settings'
        });
    });

    it('falls back to the route name', () => {
        expect(resolveRouteTitle({ name: 'somewhere', meta: {} }, definitions, t).label).toBe('somewhere');
        expect(resolveRouteTitle(null, definitions, t)).toBeNull();
    });
});

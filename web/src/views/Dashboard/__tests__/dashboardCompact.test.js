import { describe, expect, test } from 'vitest';

import { getCompactPanelHeightClass, getPanelKey } from '../dashboardCompact';

describe('dashboard phone layout', () => {
    test('reads the key of string and object panels', () => {
        expect(getPanelKey('feed')).toBe('feed');
        expect(getPanelKey({ key: 'widget:game-log', config: {} })).toBe('widget:game-log');
        expect(getPanelKey({ config: {} })).toBeNull();
        expect(getPanelKey(null)).toBeNull();
    });

    test('widgets take about half a screen, pages most of one, empty panels little', () => {
        expect(getCompactPanelHeightClass({ key: 'widget:instance' })).toContain('h-[45dvh]');
        expect(getCompactPanelHeightClass('friends-locations')).toContain('h-[75dvh]');
        expect(getCompactPanelHeightClass(null)).toBe('h-24');
    });

    test('in landscape a page panel fits the scroll area instead of reaching past the screen', () => {
        const classes = getCompactPanelHeightClass('friends-locations');
        expect(classes).toContain('compact-landscape:h-[calc(100dvh-var(--app-chrome-h)-2rem)]');
        expect(classes).toContain('compact-landscape:min-h-0');
        expect(getCompactPanelHeightClass({ key: 'widget:feed' })).not.toContain('compact-landscape:');
    });
});

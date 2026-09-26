import { describe, expect, test } from 'vitest';

import {
    getSearchResultGridColumns,
    SEARCH_GRID_PC,
    SEARCH_GRID_PHONE_LANDSCAPE,
    SEARCH_GRID_PHONE_PORTRAIT
} from '../searchResultGrid';

describe('search result grid', () => {
    test('PC keeps the upstream 180px auto-fill grid', () => {
        expect(getSearchResultGridColumns(false, false)).toBe('repeat(auto-fill, minmax(180px, 1fr))');
        expect(SEARCH_GRID_PC).toBe('repeat(auto-fill, minmax(180px, 1fr))');
    });

    test('phones in portrait show two cards per row', () => {
        expect(getSearchResultGridColumns(true, false)).toBe(SEARCH_GRID_PHONE_PORTRAIT);
        expect(SEARCH_GRID_PHONE_PORTRAIT).toBe('repeat(2, minmax(0, 1fr))');
    });

    test('phones in landscape fit as many smaller cards as the width allows', () => {
        expect(getSearchResultGridColumns(true, true)).toBe(SEARCH_GRID_PHONE_LANDSCAPE);
        expect(SEARCH_GRID_PHONE_LANDSCAPE).toContain('auto-fill');
    });
});

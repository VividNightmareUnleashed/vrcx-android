import { describe, expect, test } from 'vitest';

// Page sizes the compact feed offers (docs/DESIGN.md §7).
import { COMPACT_MAX_PAGE_SIZE, capCompactPageSizes } from '../tablePageSizes.js';

describe('capCompactPageSizes', () => {
    test('keeps the sizes up to 50', () => {
        expect(COMPACT_MAX_PAGE_SIZE).toBe(50);
        expect(capCompactPageSizes([10, 15, 20, 25, 50, 100])).toEqual([10, 15, 20, 25, 50]);
        expect(capCompactPageSizes([20, 100])).toEqual([20]);
    });

    test('offers 50 when every size is larger', () => {
        expect(capCompactPageSizes([100, 200])).toEqual([50]);
        expect(capCompactPageSizes(undefined)).toEqual([50]);
    });
});

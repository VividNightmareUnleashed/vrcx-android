import { describe, expect, test } from 'vitest';

import {
    allToolDefinitions,
    getToolsByCategory,
    getVisibleToolCategories,
    toolCategories,
    toolDefinitions,
    toolNavDefinitions
} from '../tools';

describe('tools on desktop', () => {
    test('keeps every tool, including the PC-only ones', () => {
        expect(toolDefinitions).toBe(allToolDefinitions);
        expect(toolDefinitions.map((tool) => tool.key)).toContain('registry-backup');
        expect(toolNavDefinitions.map((item) => item.key)).toContain('tool-vrc-photos');
    });

    test('shows every category', () => {
        expect(getVisibleToolCategories()).toEqual(toolCategories);
        expect(getToolsByCategory('shortcuts')).toHaveLength(5);
    });
});

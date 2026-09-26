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

    test('keeps the upstream VRChat Photos shortcut', () => {
        const tool = toolDefinitions.find((item) => item.key === 'vrc-photos');
        expect(tool).toMatchObject({
            category: 'shortcuts',
            titleKey: 'view.tools.pictures.pictures.vrc_photos',
            action: { type: 'app-api', method: 'OpenVrcPhotosFolder' }
        });
    });

    test('shows every category', () => {
        expect(getVisibleToolCategories()).toEqual(toolCategories);
        expect(getToolsByCategory('shortcuts')).toHaveLength(5);
    });
});

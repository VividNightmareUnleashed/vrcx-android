import { describe, expect, test, vi } from 'vitest';

vi.mock('../../utils/platform', async (importOriginal) => ({ ...(await importOriginal()), isAndroid: true }));

import {
    allToolDefinitions,
    defaultHiddenToolNavKeys,
    filterToolsForPlatform,
    getToolsByCategory,
    getVisibleToolCategories,
    toolDefinitionMap,
    toolDefinitions,
    toolNavDefinitions
} from '../tools';

const PC_ONLY_KEYS = [
    'vrc-photos',
    'steam-screenshots',
    'vrcx-data',
    'vrchat-data',
    'crash-dumps',
    'vrchat-config',
    'launch-options',
    'registry-backup'
];

describe('tools on Android', () => {
    test('marks exactly the PC-only tools', () => {
        expect(allToolDefinitions.filter((tool) => tool.pcOnly).map((tool) => tool.key)).toEqual(PC_ONLY_KEYS);
    });

    test('hides PC-only tools from the Tools page and the tool map', () => {
        const keys = toolDefinitions.map((tool) => tool.key);
        for (const key of PC_ONLY_KEYS) {
            expect(keys).not.toContain(key);
            expect(toolDefinitionMap.has(key)).toBe(false);
        }
        expect(keys).toContain('screenshot-metadata');
        expect(keys).toContain('gallery');
        expect(keys).toContain('auto-change-status');
    });

    test('PC-only tools cannot be pinned to the nav', () => {
        const navKeys = toolNavDefinitions.map((item) => item.key);
        for (const key of PC_ONLY_KEYS) {
            expect(navKeys).not.toContain(`tool-${key}`);
            expect(defaultHiddenToolNavKeys).not.toContain(`tool-${key}`);
        }
    });

    test('drops the empty Shortcuts category', () => {
        expect(getToolsByCategory('shortcuts')).toEqual([]);
        expect(getVisibleToolCategories().map((category) => category.key)).toEqual([
            'image',
            'group',
            'system',
            'user',
            'other'
        ]);
        expect(getToolsByCategory('system').map((tool) => tool.key)).toEqual(['auto-change-status']);
    });

    test('filterToolsForPlatform keeps everything on desktop', () => {
        expect(filterToolsForPlatform(allToolDefinitions, false)).toHaveLength(allToolDefinitions.length);
    });
});

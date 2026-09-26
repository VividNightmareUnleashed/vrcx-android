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

// PC tools that Android keeps in an adapted form (their `android` entry).
const ANDROID_ADAPTED_KEYS = ['vrc-photos'];
const HIDDEN_KEYS = PC_ONLY_KEYS.filter((key) => !ANDROID_ADAPTED_KEYS.includes(key));

describe('tools on Android', () => {
    test('marks exactly the PC-only tools', () => {
        expect(allToolDefinitions.filter((tool) => tool.pcOnly).map((tool) => tool.key)).toEqual(PC_ONLY_KEYS);
        expect(allToolDefinitions.filter((tool) => tool.android).map((tool) => tool.key)).toEqual(ANDROID_ADAPTED_KEYS);
    });

    test('hides PC-only tools from the Tools page and the tool map', () => {
        const keys = toolDefinitions.map((tool) => tool.key);
        for (const key of HIDDEN_KEYS) {
            expect(keys).not.toContain(key);
            expect(toolDefinitionMap.has(key)).toBe(false);
        }
        expect(keys).toContain('screenshot-metadata');
        expect(keys).toContain('gallery');
        expect(keys).toContain('auto-change-status');
    });

    test('PC-only tools cannot be pinned to the nav', () => {
        const navKeys = toolNavDefinitions.map((item) => item.key);
        for (const key of HIDDEN_KEYS) {
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

    test('shows the VRChat photos folder as Photos folder with the picture tools', () => {
        const tool = toolDefinitionMap.get('vrc-photos');
        expect(tool).toMatchObject({
            category: 'image',
            titleKey: 'android.photos_folder.tool_title',
            descriptionKey: 'android.photos_folder.tool_description',
            action: { type: 'android', handler: 'photos-folder' }
        });
        expect(getToolsByCategory('image').map((item) => item.key)).toEqual([
            'screenshot-metadata',
            'gallery',
            'vrc-photos'
        ]);
        const navItem = toolNavDefinitions.find((item) => item.key === 'tool-vrc-photos');
        expect(navItem).toMatchObject({ labelKey: 'android.photos_folder.tool_title', routeName: null });
        expect(navItem.action).toEqual({ type: 'tool', toolKey: 'vrc-photos' });
    });

    test('filterToolsForPlatform keeps everything on desktop', () => {
        expect(filterToolsForPlatform(allToolDefinitions, false)).toHaveLength(allToolDefinitions.length);
    });
});

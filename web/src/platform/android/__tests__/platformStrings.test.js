import { describe, expect, test } from 'vitest';
import { createI18n } from 'vue-i18n';

import en from '../../../localization/en.json';
import enPlatform from '../i18n/en.platform.json';
import { getAndroidMessages } from '../i18n/index.js';

import androidCustomFilesSettings from '../components/settings/AndroidCustomFilesSettings.vue?raw';
import androidSystemSettings from '../components/settings/AndroidSystemSettings.vue?raw';
import companionEmptyState from '../components/CompanionEmptyState.vue?raw';
import companionSettingsTab from '../components/settings/CompanionSettingsTab.vue?raw';
import companionStore from '../companionStore.js?raw';
import launchStore from '../../../stores/launch.js?raw';
import advancedSettingsStore from '../../../stores/settings/advanced.js?raw';
import mediaTab from '../../../views/Settings/components/Tabs/MediaTab.vue?raw';
import settingsTabs from '../../../views/Settings/settingsTabs.js?raw';

const sources = {
    androidCustomFilesSettings,
    androidSystemSettings,
    companionEmptyState,
    companionSettingsTab,
    companionStore,
    launchStore,
    advancedSettingsStore,
    mediaTab,
    settingsTabs
};

/**
 * @param {object} messages
 * @param {string} path
 */
function lookup(messages, path) {
    return path.split('.').reduce((node, key) => (node && typeof node === 'object' ? node[key] : undefined), messages);
}

/** Every English Android file merged, as plugins/i18n.js does (en.platform.json plus later areas' files). */
const enAndroid = getAndroidMessages('en');

function usedAndroidKeys() {
    const keys = new Set();
    for (const text of Object.values(sources)) {
        for (const [, key] of text.matchAll(/['"`](android\.[a-z_]+(?:\.[a-z_]+)+)['"`]/g)) {
            keys.add(key);
        }
    }
    return [...keys].sort();
}

describe('Android platform strings', () => {
    test('every android.* key used by the platform layer exists', () => {
        const keys = usedAndroidKeys();
        expect(keys.length).toBeGreaterThan(50);
        const missing = keys.filter((key) => typeof lookup(enAndroid, key) !== 'string');
        expect(missing).toEqual([]);
    });

    test('the PC companion tab label exists', () => {
        expect(lookup(enPlatform, 'view.settings.category.companion')).toBe('PC companion');
    });

    test('the companion empty state uses the DESIGN.md §4 copy', () => {
        expect(lookup(enPlatform, 'android.empty.title')).toBe('Game log needs the VRCX Companion on your PC');
        expect(lookup(enPlatform, 'android.empty.action')).toBe('Set up PC companion');
    });

    test('overrides only replace keys that exist upstream', () => {
        const overrides = [];
        const walk = (node, prefix) => {
            for (const [key, value] of Object.entries(node)) {
                const path = prefix ? `${prefix}.${key}` : key;
                if (value && typeof value === 'object') {
                    walk(value, path);
                } else {
                    overrides.push(path);
                }
            }
        };
        walk({ view: enPlatform.view, dialog: enPlatform.dialog }, '');
        const added = new Set(['view.settings.category.companion']);
        const unknown = overrides.filter((path) => !added.has(path) && typeof lookup(en, path) !== 'string');
        expect(unknown).toEqual([]);
    });

    test('every string compiles and interpolates with vue-i18n', () => {
        const i18n = createI18n({ legacy: false, locale: 'en', messages: { en: structuredClone(en) } });
        i18n.global.mergeLocaleMessage('en', enAndroid);
        const params = {
            name: 'DESKTOP',
            detail: 'x',
            message: 'y',
            time: 'z',
            folder: 'Pictures/VRCX',
            file: 'custom.css'
        };
        for (const key of usedAndroidKeys()) {
            const text = i18n.global.t(key, params);
            expect(text, key).not.toBe(key);
            expect(text, key).not.toMatch(/\{\w+\}/);
        }
        expect(i18n.global.t('view.settings.general.vrcx_updater.updater_disabled')).toBe(
            'Updates come with new versions of the Android app.'
        );
    });
});

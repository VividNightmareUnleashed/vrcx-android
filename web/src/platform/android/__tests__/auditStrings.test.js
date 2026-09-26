import { describe, expect, test } from 'vitest';
import { createI18n } from 'vue-i18n';

import en from '../../../localization/en.json';
import enAudit from '../i18n/en.audit.json';
import { getAndroidMessages } from '../i18n/index.js';

import launchCommands from '../launchCommands.js?raw';
import photosFolder from '../photosFolder.js?raw';
import photosFolderHint from '../components/PhotosFolderHint.vue?raw';
import tools from '../../../shared/constants/tools.js?raw';

// Strings for launch commands, start on boot, custom files and the photos folder
// (web/src/platform/android/i18n/en.audit.json).
const sources = { launchCommands, photosFolder, photosFolderHint, tools };

function lookup(messages, path) {
    return path.split('.').reduce((node, key) => (node && typeof node === 'object' ? node[key] : undefined), messages);
}

function leaves(node, prefix = '') {
    return Object.entries(node).flatMap(([key, value]) => {
        const path = prefix ? `${prefix}.${key}` : key;
        return value && typeof value === 'object' ? leaves(value, path) : [path];
    });
}

const enAndroid = getAndroidMessages('en');

describe('launch command, boot, custom file and photos folder strings', () => {
    test('every android.* key the new modules use exists', () => {
        const keys = new Set();
        for (const text of Object.values(sources)) {
            for (const [, key] of text.matchAll(/['"`](android\.[a-z_]+(?:\.[a-z_]+)+)['"`]/g)) keys.add(key);
        }
        // The launch-command dialog builds its keys from the command.
        for (const key of [
            'switchavatar',
            'addavatardb',
            'favorite_world',
            'favorite_avatar',
            'import_avatar',
            'import_world',
            'import_friend'
        ]) {
            keys.add(`android.launch_command.${key}`);
        }
        expect(keys.size).toBeGreaterThan(15);
        expect([...keys].filter((key) => typeof lookup(enAndroid, key) !== 'string')).toEqual([]);
    });

    test('overrides only replace upstream strings, and are not overridden by another Android file', () => {
        const overrides = leaves({ view: enAudit.view });
        expect(overrides.length).toBeGreaterThan(0);
        for (const path of overrides) {
            expect(typeof lookup(en, path), path).toBe('string');
            expect(lookup(enAndroid, path), path).toBe(lookup(enAudit, path));
        }
    });

    test('the Android wording replaces the PC wording of the adapted rows', () => {
        const i18n = createI18n({ legacy: false, locale: 'en', messages: { en: structuredClone(en) } });
        i18n.global.mergeLocaleMessage('en', enAndroid);
        const t = i18n.global.t;
        expect(t('view.settings.appearance.appearance.show_notification_icon_dot')).not.toContain('Tray');
        for (const type of ['prints', 'stickers', 'emoji']) {
            const text = t(`view.settings.advanced.advanced.save_instance_${type}_to_file.description`);
            expect(text).toContain('content folder');
            expect(text).not.toContain('VRChat Pictures');
        }
    });

    test('every new string compiles and interpolates', () => {
        const i18n = createI18n({ legacy: false, locale: 'en', messages: { en: structuredClone(en) } });
        i18n.global.mergeLocaleMessage('en', enAndroid);
        const params = {
            avatar: 'avtr_x',
            url: 'https://example.invalid',
            id: 'wrld_x',
            group: 'Group',
            name: 'theme.css',
            file: 'custom.css',
            message: 'x'
        };
        for (const path of leaves(enAudit)) {
            const text = i18n.global.t(path, params);
            expect(text, path).not.toBe(path);
            expect(text, path).not.toMatch(/\{\w+\}/);
        }
    });
});

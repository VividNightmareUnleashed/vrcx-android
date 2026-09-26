import { describe, expect, test } from 'vitest';

import customNavDialog from '../../../components/dialogs/CustomNavDialog.vue?raw';
import chooseFavoriteGroupDialog from '../../../components/dialogs/ChooseFavoriteGroupDialog.vue?raw';
import sendInviteConfirmDialog from '../../../components/dialogs/InviteDialog/SendInviteConfirmDialog.vue?raw';
import spotlightDialog from '../../../components/onboarding/SpotlightDialog.vue?raw';
import whatsNewDialog from '../../../components/onboarding/WhatsNewDialog.vue?raw';

import { getAndroidMessages } from '../i18n';

// Android strings for the dialogs, onboarding, Login and the phone shell: new android.* keys plus overrides of
// upstream wording that assumes a mouse or a keyboard (docs/DESIGN.md §3.3).
const files = import.meta.glob('../i18n/*.dialogs-b.json', { eager: true, import: 'default' });
const upstream = import.meta.glob('../../../localization/*.json', { eager: true, import: 'default' });

function lookup(messages, path) {
    return path.split('.').reduce((node, key) => (node && typeof node === 'object' ? node[key] : undefined), messages);
}

function leafPaths(node, prefix = '') {
    return Object.entries(node).flatMap(([key, value]) => {
        const path = prefix ? `${prefix}.${key}` : key;
        return value && typeof value === 'object' ? leafPaths(value, path) : [path];
    });
}

function localeOf(file) {
    return file.split('/').pop().replace('.dialogs-b.json', '');
}

describe('dialogs-b Android strings', () => {
    test('every override replaces an existing upstream string of the same locale', () => {
        const stale = [];
        for (const [file, messages] of Object.entries(files)) {
            const locale = localeOf(file);
            const source = upstream[`../../../localization/${locale}.json`];
            expect(source, `upstream locale ${locale}`).toBeTruthy();
            for (const path of leafPaths(messages).filter((key) => !key.startsWith('android.'))) {
                if (typeof lookup(source, path) !== 'string') stale.push(`${locale}: ${path}`);
            }
        }
        expect(stale).toEqual([]);
    });

    test('the overrides drop mouse and keyboard wording', () => {
        for (const [file, messages] of Object.entries(files)) {
            for (const path of leafPaths(messages).filter((key) => key.startsWith('onboarding.'))) {
                const text = lookup(messages, path);
                expect(text, `${localeOf(file)}: ${path}`).not.toMatch(
                    /ctrl|⌘|right[- ]click|右クリック|右键|右鍵|klikk/i
                );
            }
        }
    });

    // Every locale, as the Android build shows it (upstream strings with the Android overrides merged over them): a
    // locale that translated a mouse or keyboard phrase needs its own override, or the phone shows the PC wording.
    // Chinese 点击/點擊 is the usual word for a tap on phones as well, so it is not PC wording.
    test("no locale shows mouse or keyboard wording in onboarding or What's New", () => {
        const pcWording = /ctrl\s*\+|⌘|right[- ]click|click|clique|botão direito|クリック|右键|右鍵|klikk|kattint|คลิก/i;
        const leftovers = [];
        for (const [file, messages] of Object.entries(upstream)) {
            if (!messages.onboarding) continue;
            const locale = file.split('/').pop().replace('.json', '');
            const android = getAndroidMessages(locale);
            for (const path of leafPaths(messages.onboarding, 'onboarding')) {
                const text = lookup(android, path) ?? lookup(messages, path);
                if (typeof text === 'string' && pcWording.test(text)) leftovers.push(`${locale}: ${path}: ${text}`);
            }
        }
        expect(leftovers).toEqual([]);
    });

    test('the English file has every android.* key the dialogs use', () => {
        const en = files['../i18n/en.dialogs-b.json'];
        const keys = [...customNavDialog.matchAll(/['"`](android\.[a-z_]+(?:\.[a-z_]+)+)['"`]/g)].map(([, key]) => key);
        expect(keys.length).toBeGreaterThan(0);
        for (const key of keys) {
            expect(typeof lookup(en, key), key).toBe('string');
        }
    });

    test('small confirmation dialogs stay cards on phones (DESIGN.md §3.2)', () => {
        for (const source of [chooseFavoriteGroupDialog, sendInviteConfirmDialog, spotlightDialog, whatsNewDialog]) {
            expect(source).toMatch(/<DialogContent[^>]*data-mobile="card"/);
        }
    });
});

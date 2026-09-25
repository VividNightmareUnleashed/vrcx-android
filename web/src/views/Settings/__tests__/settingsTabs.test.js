import { describe, expect, test } from 'vitest';

import { buildSettingsTabs, resolveSettingsTab } from '../settingsTabs';

const t = (key) => key;

describe('buildSettingsTabs', () => {
    test('desktop keeps the upstream tabs in order', () => {
        expect(buildSettingsTabs(t).map((tab) => tab.value)).toEqual([
            'system',
            'interface',
            'social',
            'notifications',
            'vr',
            'media',
            'integrations',
            'advanced'
        ]);
    });

    test('Android drops VR and adds PC companion after System', () => {
        expect(buildSettingsTabs(t, { android: true, vr: false }).map((tab) => tab.value)).toEqual([
            'system',
            'companion',
            'interface',
            'social',
            'notifications',
            'media',
            'integrations',
            'advanced'
        ]);
    });

    test('labels come from i18n', () => {
        const companion = buildSettingsTabs(t, { android: true, vr: false }).find((tab) => tab.value === 'companion');
        expect(companion.label).toBe('view.settings.category.companion');
    });
});

describe('resolveSettingsTab', () => {
    const tabs = buildSettingsTabs(t, { android: true, vr: false });

    test('returns the requested tab when it exists', () => {
        expect(resolveSettingsTab('companion', tabs)).toBe('companion');
        expect(resolveSettingsTab(['advanced'], tabs)).toBe('advanced');
    });

    test('falls back to the first tab', () => {
        expect(resolveSettingsTab('vr', tabs)).toBe('system');
        expect(resolveSettingsTab(undefined, tabs)).toBe('system');
        expect(resolveSettingsTab('companion', buildSettingsTabs(t))).toBe('system');
    });
});

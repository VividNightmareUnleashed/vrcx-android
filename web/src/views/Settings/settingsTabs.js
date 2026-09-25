import { hasVrOverlay, isAndroid } from '../../shared/utils/platform';

/**
 * Settings tabs for the current platform. Android drops the VR tab (no SteamVR overlay) and adds
 * "PC companion" right after System (docs/ARCHITECTURE.md §8, §9).
 *
 * @param {(key: string) => string} t
 * @param {object} [platform]
 * @param {boolean} [platform.android]
 * @param {boolean} [platform.vr]
 * @returns {{ value: string; label: string }[]}
 */
export function buildSettingsTabs(t, { android = isAndroid, vr = hasVrOverlay } = {}) {
    const tabs = [{ value: 'system', label: t('view.settings.category.system') }];
    if (android) {
        tabs.push({ value: 'companion', label: t('view.settings.category.companion') });
    }
    tabs.push(
        { value: 'interface', label: t('view.settings.category.interface') },
        { value: 'social', label: t('view.settings.category.social') },
        { value: 'notifications', label: t('view.settings.category.notifications') }
    );
    if (vr) {
        tabs.push({ value: 'vr', label: t('view.settings.category.vr') });
    }
    tabs.push(
        { value: 'media', label: t('view.settings.category.media') },
        { value: 'integrations', label: t('view.settings.category.integrations') },
        { value: 'advanced', label: t('view.settings.category.advanced') }
    );
    return tabs;
}

/**
 * @param {unknown} requested Value of the `tab` route query
 * @param {{ value: string }[]} tabs
 * @returns {string} The requested tab when it exists, otherwise the first tab
 */
export function resolveSettingsTab(requested, tabs) {
    const value = Array.isArray(requested) ? requested[0] : requested;
    if (typeof value === 'string' && tabs.some((tab) => tab.value === value)) {
        return value;
    }
    return tabs[0]?.value ?? 'system';
}

import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, test, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';

// Settings tab gates on Android (docs/ARCHITECTURE.md §9): PC-only groups and
// rows are removed, and the Android replacements appear. The desktop counterpart is SettingsTabs.desktop.test.js.

vi.mock('@/shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true,
    hasLocalGame: false,
    hasVrOverlay: false,
    hasDesktopShell: false,
    hasLocalVrchatFiles: false,
    hasDiscordPresence: false
}));
vi.mock('@/stores', async () => (await import('./settingsTabFixtures.js')).createStoresModule({ noUpdater: true }));
const companion = vi.hoisted(() => ({ isPaired: false }));
vi.mock('@/platform/android/companionStore', () => ({ useCompanionStore: () => companion }));
vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));
vi.mock('vue-i18n', async (importOriginal) => ({
    ...(await importOriginal()),
    useI18n: () => ({ t: (key) => key })
}));
vi.mock('@/api', () => ({ authRequest: { getConfig: vi.fn() }, queryRequest: { fetch: vi.fn() } }));
vi.mock('@/shared/utils', () => ({ openExternalLink: vi.fn() }));
vi.mock('@/shared/utils/androidCustomFiles', () => ({ reloadCustomCss: vi.fn(), reloadCustomScript: vi.fn() }));
vi.mock('@/components/ui/dialog', async () => (await import('./settingsTabFixtures.js')).dialogModule);
vi.mock('@/coordinators/gameLogCoordinator', () => ({ disableGameLogDialog: vi.fn() }));
vi.mock('@/coordinators/vrcxCoordinator', () => ({ clearVRCXCache: vi.fn() }));
vi.mock('../dialogs/FeedFiltersDialog.vue', async () =>
    (await import('./settingsTabFixtures.js')).markerStub('feed-filters-dialog')
);
vi.mock('../dialogs/AvatarProviderDialog.vue', async () =>
    (await import('./settingsTabFixtures.js')).markerStub('avatar-provider-dialog')
);
vi.mock('../dialogs/TranslationApiDialog.vue', async () =>
    (await import('./settingsTabFixtures.js')).markerStub('translation-api-dialog')
);
vi.mock('../dialogs/YouTubeApiDialog.vue', async () =>
    (await import('./settingsTabFixtures.js')).markerStub('youtube-api-dialog')
);
vi.mock('../components/PhotonSettings.vue', async () =>
    (await import('./settingsTabFixtures.js')).markerStub('photon-settings')
);
vi.mock('../../Tools/dialogs/RegistryBackupDialog.vue', async () =>
    (await import('./settingsTabFixtures.js')).markerStub('registry-backup-dialog')
);

import AdvancedTab from '../components/Tabs/AdvancedTab.vue';
import IntegrationsTab from '../components/Tabs/IntegrationsTab.vue';
import MediaTab from '../components/Tabs/MediaTab.vue';
import NotificationsTab from '../components/Tabs/NotificationsTab.vue';
import SystemTab from '../components/Tabs/SystemTab.vue';

enableAutoUnmount(afterEach);

/** Mounts a tab and waits for its async (Android-only) child components to load. */
async function mountTab(component) {
    const wrapper = mount(component);
    await vi.dynamicImportSettled();
    await flushPromises();
    return wrapper;
}

describe('Settings tabs on Android', () => {
    beforeAll(() => {
        // The shim's runtime globals on Android (docs/ARCHITECTURE.md §4.1).
        vi.stubGlobal('LINUX', true);
        vi.stubGlobal('WINDOWS', false);
    });

    afterAll(() => {
        vi.unstubAllGlobals();
    });

    beforeEach(() => {
        companion.isPaired = false;
        window.AndroidHost = {
            GetBackgroundMode: vi.fn().mockResolvedValue(true),
            SetBackgroundMode: vi.fn(),
            IsIgnoringBatteryOptimizations: vi.fn().mockResolvedValue(true),
            RequestIgnoreBatteryOptimizations: vi.fn(),
            GetNotificationPermission: vi.fn().mockResolvedValue('granted'),
            RequestNotificationPermission: vi.fn(),
            OpenNotificationSettings: vi.fn()
        };
    });

    afterEach(() => {
        delete window.AndroidHost;
    });

    test('System replaces the tray, start-up and GPU rows with the Android rows', async () => {
        const wrapper = await mountTab(SystemTab);
        const text = wrapper.text();

        expect(text).not.toContain('view.settings.general.application.tray');
        expect(text).not.toContain('view.settings.general.application.minimized');
        expect(text).not.toContain('view.settings.general.application.startup');
        expect(text).not.toContain('view.settings.general.application.disable_gpu_acceleration');
        expect(text).not.toContain('view.settings.general.application.disable_vr_overlay_gpu_acceleration');
        expect(text).not.toContain('view.settings.general.general.latest_app_version');
        expect(text).not.toContain('view.settings.general.vrcx_updater.change_build');

        expect(text).toContain('view.settings.general.vrcx_updater.updater_disabled');
        expect(text).toContain('view.settings.general.application.proxy');
        expect(text).toContain('android.system.background_label');
        expect(text).toContain('android.system.battery_label');
        expect(text).toContain('android.system.notification_label');
        expect(window.AndroidHost.GetBackgroundMode).toHaveBeenCalled();
    });

    test('Notifications offers Inside VR and Outside VR once a PC companion is paired, never the AFK row', async () => {
        companion.isPaired = true;
        const wrapper = await mountTab(NotificationsTab);
        const text = wrapper.text();

        // Desktop notifications: both VR conditions; text to speech: Inside VR, as upstream.
        expect(text.match(/conditions\.inside_vr(?!chat)/g)).toHaveLength(2);
        expect(text.match(/conditions\.outside_vr(?!chat)/g)).toHaveLength(1);
        expect(text).not.toContain(
            'view.settings.notifications.notifications.desktop_notifications.desktop_notification_while_afk'
        );
    });

    test('Notifications hides the VR conditions and the AFK row, and hosts the user-images switch', async () => {
        const wrapper = await mountTab(NotificationsTab);
        const text = wrapper.text();

        // `(?!chat)`: the "Inside VRChat" / "Outside VRChat" conditions stay.
        expect(text).not.toMatch(/conditions\.inside_vr(?!chat)/);
        expect(text).not.toMatch(/conditions\.outside_vr(?!chat)/);
        expect(text).not.toContain(
            'view.settings.notifications.notifications.desktop_notifications.desktop_notification_while_afk'
        );

        expect(text).toContain('view.settings.notifications.notifications.steamvr_notifications.user_images');
        expect(text).toContain('view.settings.notifications.notifications.conditions.inside_vrchat');
        expect(text).toContain('view.settings.notifications.notifications.conditions.always');
    });

    test('Advanced drops the VRChat, app launcher, console and registry parts and offers custom CSS/JS reload', async () => {
        const wrapper = await mountTab(AdvancedTab);
        const text = wrapper.text();

        expect(text).not.toContain('view.settings.advanced.advanced.vrchat_settings.header');
        expect(text).not.toContain('view.settings.advanced.advanced.relaunch_vrchat.header');
        expect(text).not.toContain('view.settings.advanced.advanced.vrchat_quit_fix.header');
        expect(text).not.toContain('view.settings.advanced.advanced.auto_cache_management.header');
        expect(text).not.toContain('view.settings.advanced.advanced.self_invite.header');
        expect(text).not.toContain('view.settings.advanced.advanced.app_launcher.header');
        expect(text).not.toContain('view.settings.advanced.advanced.cache_debug.show_console');
        expect(wrapper.find('[data-testid="registry-backup-dialog"]').exists()).toBe(false);

        expect(text).toContain('view.settings.advanced_groups.security.header');
        expect(text).toContain('view.settings.advanced.advanced.cache_debug.header');
        expect(text).toContain('android.custom_files.header');
        expect(wrapper.find('[data-testid="android-reload-custom-css"]').exists()).toBe(true);
        expect(wrapper.find('[data-testid="android-reload-custom-js"]').exists()).toBe(true);
    });

    test('Media hides the screenshot helper and names the Android save folder', async () => {
        const wrapper = await mountTab(MediaTab);
        const text = wrapper.text();

        expect(text).not.toContain('view.settings.advanced.advanced.screenshot_helper.header');
        expect(text).not.toContain('view.settings.advanced.advanced.delete_all_screenshot_metadata.button');

        expect(text).toContain('view.settings.pictures.pictures.auto_delete_old_prints');
        expect(text).toContain('view.settings.advanced.advanced.user_generated_content.header');
        expect(wrapper.find('[data-testid="ugc-folder-label"]').exists()).toBe(true);
    });

    test('Integrations hides Discord Rich Presence and keeps the API groups', async () => {
        const wrapper = await mountTab(IntegrationsTab);
        const text = wrapper.text();

        expect(text).not.toContain('view.settings.discord_presence.discord_presence.header');
        expect(text).not.toContain('view.settings.discord_presence.discord_presence.enable');

        expect(text).toContain('view.settings.advanced.advanced.translation_api.header');
        expect(text).toContain('view.settings.advanced.advanced.youtube_api.header');
        expect(text).toContain('view.settings.advanced.advanced.remote_database.header');
    });
});

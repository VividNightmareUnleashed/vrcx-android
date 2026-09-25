import { afterEach, describe, expect, test, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';

// Desktop counterpart of SettingsTabs.android.test.js: with the real platform helpers (ANDROID=false) every Android
// gate is a no-op, so the PC groups and rows are all still there and no Android row appears.

vi.mock('@/stores', async () => (await import('./settingsTabFixtures.js')).createStoresModule({ noUpdater: false }));
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

async function mountTab(component) {
    const wrapper = mount(component);
    await vi.dynamicImportSettled();
    await flushPromises();
    return wrapper;
}

describe('Settings tabs on desktop (Windows)', () => {
    test('System keeps the tray, start-up, GPU and updater rows', async () => {
        const wrapper = await mountTab(SystemTab);
        const text = wrapper.text();

        expect(text).toContain('view.settings.general.application.tray');
        expect(text).toContain('view.settings.general.application.minimized');
        expect(text).toContain('view.settings.general.application.startup');
        expect(text).toContain('view.settings.general.application.disable_gpu_acceleration');
        expect(text).toContain('view.settings.general.general.latest_app_version');
        expect(text).toContain('view.settings.general.vrcx_updater.change_build');
        expect(text).not.toContain('android.system.');
    });

    test('Notifications keeps the VR conditions and the AFK row', async () => {
        const wrapper = await mountTab(NotificationsTab);
        const text = wrapper.text();

        expect(text).toMatch(/conditions\.inside_vr(?!chat)/);
        expect(text).toMatch(/conditions\.outside_vr(?!chat)/);
        expect(text).toContain(
            'view.settings.notifications.notifications.desktop_notifications.desktop_notification_while_afk'
        );
        expect(text).not.toContain('view.settings.notifications.notifications.steamvr_notifications.user_images');
    });

    test('Advanced keeps the VRChat, app launcher, console and registry parts', async () => {
        const wrapper = await mountTab(AdvancedTab);
        const text = wrapper.text();

        expect(text).toContain('view.settings.advanced.advanced.vrchat_settings.header');
        expect(text).toContain('view.settings.advanced.advanced.self_invite.header');
        expect(text).toContain('view.settings.advanced.advanced.app_launcher.header');
        expect(text).toContain('view.settings.advanced.advanced.cache_debug.show_console');
        expect(wrapper.find('[data-testid="registry-backup-dialog"]').exists()).toBe(true);
        expect(text).not.toContain('android.custom_files.');
    });

    test('Media keeps the screenshot helper', async () => {
        const wrapper = await mountTab(MediaTab);

        expect(wrapper.text()).toContain('view.settings.advanced.advanced.screenshot_helper.header');
        expect(wrapper.find('[data-testid="ugc-folder-label"]').exists()).toBe(false);
    });

    test('Integrations keeps Discord Rich Presence', async () => {
        const wrapper = await mountTab(IntegrationsTab);

        expect(wrapper.text()).toContain('view.settings.discord_presence.discord_presence.header');
    });
});

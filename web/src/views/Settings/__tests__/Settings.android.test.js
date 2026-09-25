import { beforeEach, describe, expect, test, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick, reactive } from 'vue';

const mocks = vi.hoisted(() => ({
    route: null,
    replace: vi.fn(),
    // `__esModule` lets defineAsyncComponent unwrap the mocked module's default export.
    stub: (name) => ({ __esModule: true, default: { name, template: `<div data-testid="${name}" />` } })
}));

vi.mock('../../../shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true,
    hasVrOverlay: false
}));
vi.mock('vue-router', async (importOriginal) => ({
    ...(await importOriginal()),
    useRoute: () => mocks.route,
    useRouter: () => ({ replace: mocks.replace })
}));
vi.mock('vue-i18n', async (importOriginal) => ({
    ...(await importOriginal()),
    useI18n: () => ({ t: (key) => key })
}));

vi.mock('../components/Tabs/AdvancedTab.vue', () => mocks.stub('advanced-tab'));
vi.mock('../components/Tabs/InterfaceTab.vue', () => mocks.stub('interface-tab'));
vi.mock('../components/Tabs/IntegrationsTab.vue', () => mocks.stub('integrations-tab'));
vi.mock('../components/Tabs/MediaTab.vue', () => mocks.stub('media-tab'));
vi.mock('../components/Tabs/NotificationsTab.vue', () => mocks.stub('notifications-tab'));
vi.mock('../components/Tabs/SocialTab.vue', () => mocks.stub('social-tab'));
vi.mock('../components/Tabs/SystemTab.vue', () => mocks.stub('system-tab'));
vi.mock('../components/Tabs/VrTab.vue', () => mocks.stub('vr-tab'));
vi.mock('../../../platform/android/components/settings/CompanionSettingsTab.vue', () => mocks.stub('companion-tab'));

import Settings from '../Settings.vue';

function activeTab(wrapper) {
    return wrapper.find('[role="tab"][data-state="active"]').text();
}

describe('Settings on Android', () => {
    beforeEach(() => {
        mocks.route = reactive({ query: {} });
        mocks.replace.mockClear();
    });

    test('has a PC companion tab after System and no VR tab', () => {
        const wrapper = mount(Settings);
        const tabs = wrapper.findAll('[role="tab"]').map((tab) => tab.text());
        expect(tabs.slice(0, 2)).toEqual(['view.settings.category.system', 'view.settings.category.companion']);
        expect(tabs).not.toContain('view.settings.category.vr');
        expect(activeTab(wrapper)).toBe('view.settings.category.system');
    });

    test('opens the tab named in ?tab=', async () => {
        mocks.route.query = { tab: 'companion' };
        const wrapper = mount(Settings);
        await flushPromises();
        expect(activeTab(wrapper)).toBe('view.settings.category.companion');
    });

    test('follows ?tab= changes while the page stays alive', async () => {
        const wrapper = mount(Settings);
        mocks.route.query = { tab: 'companion' };
        await nextTick();
        await flushPromises();
        expect(activeTab(wrapper)).toBe('view.settings.category.companion');
    });

    test('ignores unknown tabs', async () => {
        mocks.route.query = { tab: 'vr' };
        const wrapper = mount(Settings);
        await flushPromises();
        expect(activeTab(wrapper)).toBe('view.settings.category.system');
    });
});

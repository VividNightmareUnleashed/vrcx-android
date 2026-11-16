import { afterEach, describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { mount } from '@vue/test-utils';

const mocks = vi.hoisted(() => ({
    notification: null,
    markAllAsSeen: vi.fn()
}));

vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));
vi.mock('vue-router', () => ({ useRoute: () => ({ name: 'feed', meta: {} }) }));
vi.mock('../../../../stores', () => ({ useNotificationStore: () => mocks.notification }));
vi.mock('../../../../stores/quickSearch', () => ({ useQuickSearchStore: () => ({ open: vi.fn() }) }));
vi.mock('../../../../components/nav-menu/navMenuModel', () => ({ useNavMenuModel: () => ref(null) }));
vi.mock('../../../../composables/useCompactLayout', () => ({
    useCompactLayout: () => ({ isCompactLandscape: ref(false) })
}));
vi.mock('../shellNav', () => ({ resolveRouteTitle: () => ({ icon: 'ri-rss-line', label: 'Feed' }) }));
vi.mock('../GameIndicator.vue', () => ({ default: { template: '<span />' } }));
vi.mock('../../../../components/ui/button', () => ({
    Button: { emits: ['click'], template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' }
}));
vi.mock('../../../../components/ui/context-menu', () => {
    const slot = { template: '<div><slot /></div>' };
    return {
        ContextMenu: slot,
        ContextMenuTrigger: slot,
        ContextMenuContent: { template: '<div data-testid="menu"><slot /></div>' },
        ContextMenuItem: {
            emits: ['click'],
            template: '<div data-testid="mark-all" @click="$emit(\'click\')"><slot /></div>'
        }
    };
});
vi.mock('lucide-vue-next', () => ({ Bell: { template: '<i />' }, Search: { template: '<i />' } }));

import PhoneAppBar from '../PhoneAppBar.vue';

function mountBar({ unseen }) {
    mocks.notification = {
        isNotificationCenterOpen: ref(false),
        hasUnseenNotifications: ref(unseen),
        markAllAsSeen: mocks.markAllAsSeen
    };
    return mount(PhoneAppBar);
}

function bell(wrapper) {
    return wrapper.find('button[aria-label="side_panel.notification_center.title"]');
}

describe('PhoneAppBar notification bell', () => {
    afterEach(() => mocks.markAllAsSeen.mockClear());

    it('opens and closes the notification center, as on PC', async () => {
        const wrapper = mountBar({ unseen: false });
        await bell(wrapper).trigger('click');
        expect(mocks.notification.isNotificationCenterOpen.value).toBe(true);
        await bell(wrapper).trigger('click');
        expect(mocks.notification.isNotificationCenterOpen.value).toBe(false);
    });

    it('marks unseen notifications with a dot and offers Mark all read', async () => {
        const wrapper = mountBar({ unseen: true });
        expect(bell(wrapper).find('span.bg-red-500').exists()).toBe(true);
        await wrapper.find('[data-testid="mark-all"]').trigger('click');
        expect(mocks.markAllAsSeen).toHaveBeenCalledTimes(1);
    });

    it('shows no dot and no menu without unseen notifications', () => {
        const wrapper = mountBar({ unseen: false });
        expect(bell(wrapper).find('span.bg-red-500').exists()).toBe(false);
        expect(wrapper.find('[data-testid="menu"]').exists()).toBe(false);
    });
});

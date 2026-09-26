// Android: the Hot Worlds friends sheet is teleported to <body>, so it must close when the page is left.
import { describe, expect, test, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { KeepAlive, defineComponent, h, nextTick, ref } from 'vue';

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));

vi.mock('@/shared/utils/platform', () => ({ isAndroid: true }));

vi.mock('@/composables/useCompactLayout', () => ({
    useCompactLayout: () => ({ isCompact: ref(true), isCompactLandscape: ref(false), isCoarsePointer: ref(true) })
}));

vi.mock('@/stores', () => ({
    useAppearanceSettingsStore: () => ({ isDarkMode: ref(false) })
}));

vi.mock('@/coordinators/userCoordinator', () => ({ showUserDialog: vi.fn() }));
vi.mock('@/coordinators/worldCoordinator', () => ({ showWorldDialog: vi.fn() }));

vi.mock('@/services/database', () => ({
    database: {
        getHotWorlds: vi.fn(async () => [
            {
                worldId: 'wrld_00000000-0000-4000-8000-000000000001',
                worldName: 'Moss Library',
                uniqueFriends: 4,
                visitCount: 9,
                trend: 'rising'
            }
        ]),
        getHotWorldFriendDetail: vi.fn(async () => [])
    }
}));

vi.mock('@/components/BackToTop.vue', () => ({ default: { template: '<div />' } }));

const { passthrough } = vi.hoisted(() => ({ passthrough: { template: '<div><slot /></div>' } }));

vi.mock('@/components/ui/sheet', () => ({
    Sheet: {
        props: ['open'],
        emits: ['update:open'],
        template: '<div data-testid="sheet" :data-open="String(open)"><slot v-if="open" /></div>'
    },
    SheetContent: passthrough,
    SheetHeader: passthrough,
    SheetTitle: passthrough
}));
vi.mock('@/components/ui/hover-card', () => ({
    HoverCard: passthrough,
    HoverCardTrigger: passthrough,
    HoverCardContent: passthrough
}));
vi.mock('@/components/ui/toggle-group', () => ({ ToggleGroup: passthrough, ToggleGroupItem: passthrough }));
vi.mock('@/components/ui/separator', () => ({ Separator: { template: '<hr />' } }));
vi.mock('@/components/ui/data-table', () => ({ DataTableEmpty: { template: '<div />' } }));

import HotWorlds from '../HotWorlds.vue';

describe('HotWorlds on Android', () => {
    test('closes the friends sheet when the page is deactivated', async () => {
        const visible = ref(true);
        const Host = defineComponent({
            setup: () => () => h(KeepAlive, null, [visible.value ? h(HotWorlds) : h('div', 'other page')])
        });
        const wrapper = mount(Host);
        await flushPromises();

        await wrapper.get('button[type="button"]').trigger('click');
        await flushPromises();
        expect(wrapper.get('[data-testid="sheet"]').attributes('data-open')).toBe('true');

        visible.value = false;
        await nextTick();
        visible.value = true;
        await nextTick();
        expect(wrapper.get('[data-testid="sheet"]').attributes('data-open')).toBe('false');
        wrapper.unmount();
    });
});

import { describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';

const platform = vi.hoisted(() => ({ android: true }));
vi.mock('@/shared/utils/platform', () => ({
    get isAndroid() {
        return platform.android;
    }
}));

import SidebarMenuButton from '../SidebarMenuButton.vue';
import SidebarProvider from '../SidebarProvider.vue';

function mountButton({ mobile, open }) {
    const Host = defineComponent({
        setup: () => () =>
            h(SidebarProvider, { mobile, open }, () => h(SidebarMenuButton, { tooltip: 'Manage' }, () => 'Manage'))
    });
    return mount(Host, { attachTo: document.body });
}

describe('SidebarMenuButton tooltip', () => {
    it('renders no tooltip in the phone nav sheet on Android (it could never show)', () => {
        platform.android = true;
        const wrapper = mountButton({ mobile: true, open: true });
        expect(wrapper.find('[data-slot="sidebar-menu-button"]').exists()).toBe(true);
        expect(wrapper.find('[data-slot="tooltip-trigger"]').exists()).toBe(false);
        wrapper.unmount();
    });

    it('renders no tooltip for the expanded nav on Android', () => {
        platform.android = true;
        const wrapper = mountButton({ mobile: false, open: true });
        expect(wrapper.find('[data-slot="tooltip-trigger"]').exists()).toBe(false);
        wrapper.unmount();
    });

    it('keeps the tooltip of the collapsed (icon) nav on Android tablets', () => {
        platform.android = true;
        const wrapper = mountButton({ mobile: false, open: false });
        expect(wrapper.find('[data-slot="tooltip-trigger"]').exists()).toBe(true);
        wrapper.unmount();
    });

    it('keeps the upstream behaviour on desktop', () => {
        platform.android = false;
        const wrapper = mountButton({ mobile: false, open: true });
        expect(wrapper.find('[data-slot="tooltip-trigger"]').exists()).toBe(true);
        wrapper.unmount();
    });
});

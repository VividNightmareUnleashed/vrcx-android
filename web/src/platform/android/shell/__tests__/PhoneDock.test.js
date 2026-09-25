import { afterEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';

import { i18n } from '../../../../plugins/i18n';
import { SidebarProvider, useSidebar } from '../../../../components/ui/sidebar';
import { publishNavMenuModel } from '../../../../components/nav-menu/navMenuModel';
import { closeShellPanels, shellState } from '../shellState';
import PhoneDock from '../PhoneDock.vue';

const menuItems = [
    { index: 'feed', icon: 'ri-rss-line', title: 'nav_tooltip.feed' },
    {
        index: 'default-folder-favorites',
        icon: 'ri-star-line',
        title: 'Favorites',
        titleIsCustom: true,
        children: [
            { index: 'favorite-friends', label: 'Favorite Friends', titleIsCustom: true, icon: 'ri-user-heart-line' },
            { index: 'favorite-worlds', label: 'Favorite Worlds', titleIsCustom: true, icon: 'ri-earth-line' }
        ]
    },
    { index: 'dashboard-1', icon: 'ri-dashboard-line', title: 'My board', titleIsCustom: true },
    { index: 'notification', icon: 'ri-notification-2-line', title: 'nav_tooltip.notification' }
];

function mountDock({ activeIndex = 'feed', notified = [], rail = false } = {}) {
    const triggerNavAction = vi.fn();
    const unpublish = publishNavMenuModel({
        menuItems: ref(menuItems),
        activeMenuIndex: ref(activeIndex),
        isNavItemNotified: (entry) => notified.includes(entry.index),
        isEntryNotified: (entry) => notified.includes(entry.index),
        triggerNavAction
    });
    let sidebar;
    const Probe = defineComponent({
        setup() {
            sidebar = useSidebar();
            return () => null;
        }
    });
    const Host = defineComponent({
        setup: () => () => h(SidebarProvider, { mobile: true }, () => [h(Probe), h(PhoneDock, { rail })])
    });
    const wrapper = mount(Host, { global: { plugins: [i18n] }, attachTo: document.body });
    return { wrapper, triggerNavAction, getSidebar: () => sidebar, unpublish };
}

describe('PhoneDock (DESIGN.md §2.1)', () => {
    let mounted;

    afterEach(() => {
        mounted?.wrapper.unmount();
        mounted?.unpublish();
        mounted = null;
        closeShellPanels();
        document.body.innerHTML = '';
    });

    it('shows Friends, the first three entries of the nav layout and Menu', () => {
        mounted = mountDock();
        const slots = mounted.wrapper.findAll('.vrcx-dock-slot');
        expect(slots).toHaveLength(5);
        expect(slots.map((slot) => slot.attributes('aria-label'))).toEqual([
            'Friends',
            'Feed',
            'Favorites',
            'My board',
            expect.any(String)
        ]);
        expect(mounted.wrapper.text()).not.toContain('Notification');
    });

    it('marks the active entry, and only Friends while the friends panel covers the page', async () => {
        mounted = mountDock({ activeIndex: 'favorite-worlds' });
        const slots = () => mounted.wrapper.findAll('.vrcx-dock-slot');
        expect(slots()[2].classes()).toContain('is-active');
        expect(slots()[1].classes()).not.toContain('is-active');

        await slots()[0].trigger('click');
        expect(shellState.friendsPanelOpen).toBe(true);
        expect(slots()[0].classes()).toContain('is-active');
        expect(slots()[2].classes()).not.toContain('is-active');
    });

    it('navigates through the NavMenu action and closes the friends panel', async () => {
        mounted = mountDock();
        shellState.friendsPanelOpen = true;
        await nextTick();
        await mounted.wrapper.findAll('.vrcx-dock-slot')[3].trigger('click');
        expect(mounted.triggerNavAction).toHaveBeenCalledWith(menuItems[2]);
        expect(shellState.friendsPanelOpen).toBe(false);
    });

    it('opens a folder as a dropdown of its children', async () => {
        mounted = mountDock();
        const folder = mounted.wrapper.findAll('.vrcx-dock-slot')[2];
        await folder.trigger('keydown', { key: 'Enter' });
        await flushPromises();
        const items = [...document.body.querySelectorAll('[data-slot="dropdown-menu-item"]')];
        expect(items.map((item) => item.textContent.trim())).toEqual(['Favorite Friends', 'Favorite Worlds']);
    });

    it('opens the nav sheet from Menu, and puts dots of hidden entries on it', async () => {
        mounted = mountDock({ notified: ['notification'] });
        const menu = mounted.wrapper.findAll('.vrcx-dock-slot')[4];
        expect(menu.find('.vrcx-dock-dot').exists()).toBe(true);
        await menu.trigger('click');
        expect(mounted.getSidebar().openMobile.value).toBe(true);
    });

    it('drops the labels in the landscape rail', () => {
        mounted = mountDock({ rail: true });
        expect(mounted.wrapper.find('.vrcx-dock--rail').exists()).toBe(true);
        expect(mounted.wrapper.find('.vrcx-dock-label').exists()).toBe(false);
    });
});

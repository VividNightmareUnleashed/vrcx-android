import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));

vi.mock('@/components/ui/button', () => ({
    Button: {
        inheritAttrs: false,
        emits: ['click'],
        template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>'
    }
}));
vi.mock('@/components/ui/switch', () => ({
    Switch: { props: ['modelValue', 'disabled'], template: '<input type="checkbox" />' }
}));

import FavoritesContentHeader from '../FavoritesContentHeader.vue';
import FavoritesToolbar from '../FavoritesToolbar.vue';

const passthrough = { template: '<div><slot /></div>' };
const toolbarStubs = {
    Select: passthrough,
    SelectTrigger: passthrough,
    SelectValue: { template: '<span />' },
    SelectContent: passthrough,
    SelectGroup: passthrough,
    SelectItem: passthrough,
    InputGroupSearch: { template: '<input />' },
    DropdownMenu: passthrough,
    DropdownMenuTrigger: passthrough,
    DropdownMenuContent: passthrough,
    DropdownMenuItem: passthrough,
    DropdownMenuSeparator: { template: '<hr />' },
    ToggleGroup: passthrough,
    ToggleGroupItem: passthrough,
    Slider: { template: '<div />' }
};

describe('FavoritesContentHeader on phones', () => {
    it('renders the title as a button that opens the group sheet', async () => {
        const wrapper = mount(FavoritesContentHeader, {
            props: { titleClickable: true },
            slots: { title: '<span>Close friends</span>' }
        });
        const title = wrapper.get('button[type="button"]');
        expect(title.text()).toContain('Close friends');
        await title.trigger('click');
        expect(wrapper.emitted('title-click')).toHaveLength(1);
    });

    it('keeps the PC title as plain text', () => {
        const wrapper = mount(FavoritesContentHeader, {
            slots: { title: '<span>Close friends</span>' }
        });
        expect(wrapper.find('button[type="button"]').exists()).toBe(false);
        expect(wrapper.text()).toContain('Close friends');
    });
});

describe('FavoritesToolbar on phones', () => {
    it('shows the group sheet button only when asked', async () => {
        const phone = mount(FavoritesToolbar, {
            props: { groupsButtonVisible: true },
            global: { stubs: toolbarStubs }
        });
        const button = phone.get('button[aria-label="android.favorites.groups"]');
        await button.trigger('click');
        expect(phone.emitted('open-groups')).toHaveLength(1);

        const pc = mount(FavoritesToolbar, { global: { stubs: toolbarStubs } });
        expect(pc.find('button[aria-label="android.favorites.groups"]').exists()).toBe(false);
    });
});

import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

// The touch kebab for user rows (docs/DESIGN.md §3.3), with the real reka dropdown: a tap opens the menu and never
// reaches the row underneath (whose click opens the user dialog and whose pointerdown starts the long-press menu).

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));
vi.mock('../UserActionMenuItems.vue', () => ({
    default: {
        props: ['userId', 'variant'],
        template: '<div data-testid="user-action-items" :data-user="userId" :data-variant="variant" />'
    }
}));

import UserActionMenuButton from '../UserActionMenuButton.vue';

const ONLINE = { userId: 'usr_00000000-0000-4000-8000-000000000101', state: 'online', location: 'wrld_a:1' };

function mountRow() {
    const onRowClick = vi.fn();
    const onRowPointerdown = vi.fn();
    const wrapper = mount(
        {
            components: { UserActionMenuButton },
            template:
                '<div data-testid="row" @click="onRowClick" @pointerdown="onRowPointerdown">' +
                '<span>Aurora</span><UserActionMenuButton v-bind="props" /></div>',
            setup: () => ({ onRowClick, onRowPointerdown, props: ONLINE })
        },
        { attachTo: document.body }
    );
    return { wrapper, onRowClick, onRowPointerdown };
}

describe('UserActionMenuButton.vue (real dropdown)', () => {
    afterEach(() => {
        document.body.innerHTML = '';
    });

    it('opens the user actions on tap without triggering the row', async () => {
        const { wrapper, onRowClick, onRowPointerdown } = mountRow();
        const kebab = wrapper.find('[data-slot="user-action-menu-button"]');
        expect(kebab.attributes('aria-expanded')).toBe('false');

        await kebab.trigger('pointerdown');
        await kebab.trigger('click');
        await flushPromises();

        expect(kebab.attributes('aria-expanded')).toBe('true');
        const items = document.body.querySelector('[data-testid="user-action-items"]');
        expect(items).not.toBeNull();
        expect(items.getAttribute('data-variant')).toBe('dropdown');
        expect(items.getAttribute('data-user')).toBe(ONLINE.userId);
        expect(onRowClick).not.toHaveBeenCalled();
        expect(onRowPointerdown).not.toHaveBeenCalled();
        wrapper.unmount();
    });

    it('still lets a tap on the rest of the row open the row', async () => {
        const { wrapper, onRowClick } = mountRow();

        await wrapper.find('span').trigger('click');

        expect(onRowClick).toHaveBeenCalledTimes(1);
        expect(document.body.querySelector('[data-testid="user-action-items"]')).toBeNull();
        wrapper.unmount();
    });
});

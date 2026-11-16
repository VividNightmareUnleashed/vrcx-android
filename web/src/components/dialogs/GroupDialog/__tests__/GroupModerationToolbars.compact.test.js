import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';

// Phones: no moderation toolbar or action row may be wider than the screen (the dialog page would scroll sideways
// and hide Kick/Ban/Unban or the Bans search). The PC keeps its single rows: every change is a compact: or
// pointer-coarse: variant.

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));
vi.mock('@/shared/utils', () => ({ hasGroupPermission: () => true }));
vi.mock('@/lib/table/useVrcxVueTable', () => ({
    useVrcxVueTable: () => ({ table: { getFilteredRowModel: () => ({ rows: [] }) } })
}));
vi.mock('@/components/ui/data-table', () => ({ DataTableLayout: { template: '<div data-stub="table" />' } }));
vi.mock('../groupMemberModerationBansColumns.jsx', () => ({ createColumns: () => [] }));
vi.mock('@/components/ui/input-group', () => ({
    InputGroupField: { props: ['modelValue'], template: '<input data-stub="search" />' },
    InputGroupTextareaField: { template: '<textarea />' }
}));

import GroupModerationBansTab from '../GroupModerationBansTab.vue';
import GroupModerationBulkActions from '../GroupModerationBulkActions.vue';

function phoneOnly(classes, base) {
    return classes.filter((name) => !base.includes(name));
}

describe('GroupModerationBansTab.vue toolbar on phones', () => {
    const wrapper = mount(GroupModerationBansTab, {
        props: {
            tableData: { data: [{ userId: 'usr_1' }], filters: [{ value: '' }] },
            groupRef: {},
            pageSizes: [15],
            columnContext: {},
            handlePageChange: vi.fn()
        }
    });

    it('wraps both toolbar rows and gives the search field the full width', () => {
        const toolbar = wrapper.find('div > div.justify-between');
        expect(toolbar.classes()).toContain('compact:flex-wrap');
        const [, actions] = toolbar.findAll(':scope > div');
        expect(actions.classes()).toEqual(expect.arrayContaining(['compact:flex-wrap', 'compact:w-full']));
        expect(actions.text()).toContain('dialog.group_member_moderation.import_bans');

        const search = wrapper.find('[data-stub="search"]');
        expect(search.classes()).toEqual(expect.arrayContaining(['w-80', 'compact:w-full', 'compact:basis-full']));
    });

    it('only adds phone and touch variants to the PC classes', () => {
        const toolbar = wrapper.find('div > div.justify-between');
        expect(phoneOnly(toolbar.classes(), ['flex', 'justify-between'])).toEqual([
            'compact:flex-wrap',
            'compact:gap-2',
            'pointer-coarse:flex-wrap',
            'pointer-coarse:gap-2'
        ]);
    });
});

describe('GroupModerationBulkActions.vue on phones', () => {
    const wrapper = mount(GroupModerationBulkActions, {
        props: {
            selectedUsersArray: [
                { id: 'usr_1', userId: 'usr_1', membershipStatus: 'member', user: { displayName: 'A' } }
            ],
            groupRef: { roles: [] }
        },
        global: { stubs: { TooltipWrapper: { template: '<span><slot /></span>' } } }
    });

    it('keeps Kick, Ban and Unban on screen in a fixed three-column grid', () => {
        // Equal columns share the width, so no action is pushed past the edge of a phone-wide panel.
        const actions = wrapper
            .findAll('div.grid')
            .find((el) => el.text().includes('dialog.group_member_moderation.unban'));
        expect(actions.classes()).toEqual(expect.arrayContaining(['grid', 'grid-cols-3']));
        expect(actions.findAll(':scope > button').map((button) => button.text())).toEqual([
            'dialog.group_member_moderation.kick',
            'dialog.group_member_moderation.ban',
            'dialog.group_member_moderation.unban'
        ]);

        const roles = wrapper
            .findAll('div.grid')
            .find((el) => el.text().includes('dialog.group_member_moderation.add_roles'));
        expect(roles.classes()).toEqual(expect.arrayContaining(['grid', 'grid-cols-2']));
        expect(roles.text()).toContain('dialog.group_member_moderation.remove_roles');
    });

    it('gives the remove-user button of a selected user a touch-sized hit area', () => {
        const row = wrapper.findAll('div.flex.items-center').find((el) => el.text() === 'A');
        const remove = row.find('button[type="button"]');
        expect(remove.exists()).toBe(true);
        // 32px on touch, pulled into the row's padding so the row keeps its height.
        expect(remove.classes()).toEqual(
            expect.arrayContaining(['pointer-coarse:size-8', 'pointer-coarse:-my-2', 'pointer-coarse:-mr-2'])
        );
    });

    it('gives the clear-all and select-user icon buttons a 40px touch target', () => {
        const buttons = wrapper.findAll('button').filter((el) => el.classes().includes('pointer-coarse:size-10'));
        expect(buttons).toHaveLength(2);
        // Clear all sits beside the selected-users count, select user beside the user ID field.
        expect(buttons[0].classes()).toContain('rounded-full');
        expect(buttons[0].element.closest('div').textContent).toContain(
            'dialog.group_member_moderation.selected_users'
        );
        expect(buttons[1].classes()).not.toContain('rounded-full');
    });
});

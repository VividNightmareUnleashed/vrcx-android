import { beforeEach, describe, expect, test, vi } from 'vitest';
import { defineComponent, h, ref } from 'vue';
import { mount } from '@vue/test-utils';

const mocks = vi.hoisted(() => ({ isCompact: { value: true } }));

vi.mock('../../../plugins', () => ({ i18n: { global: { t: (key) => key } } }));
vi.mock('../../../composables/useCompactLayout', () => ({
    useCompactLayout: () => ({ isCompact: mocks.isCompact })
}));
vi.mock('../../../shared/utils', () => ({
    formatDateFilter: (value, format) => (value ? `${format}:${value}` : '-'),
    getFaviconUrl: (url) => url,
    languageClass: (key) => `flag-${key}`,
    openExternalLink: vi.fn(),
    sortStatus: () => 0,
    statusClass: () => '',
    timeToText: (ms) => `${Math.round(ms / 60000)}m`
}));
vi.mock('../../../components/ui/avatar', () => ({
    Avatar: 'Avatar',
    AvatarFallback: 'AvatarFallback',
    AvatarImage: 'AvatarImage'
}));
vi.mock('../../../components/ui/button', () => ({
    Button: {
        name: 'Button',
        render() {
            return h('button', { type: 'button' }, this.$slots.default?.());
        }
    }
}));
vi.mock('../../../components/ui/checkbox', () => ({
    Checkbox: {
        name: 'Checkbox',
        props: ['modelValue'],
        emits: ['update:modelValue'],
        render() {
            return h('button', {
                role: 'checkbox',
                'aria-checked': String(Boolean(this.modelValue)),
                onClick: () => this.$emit('update:modelValue', !this.modelValue)
            });
        }
    }
}));
vi.mock('../../../components/ui/tooltip', () => ({
    TooltipWrapper: {
        name: 'TooltipWrapper',
        render() {
            return h('div', this.$slots.default?.());
        }
    }
}));

import { createColumns } from '../columns.jsx';
import { isRowTapSelection } from '../friendListCompact';

const FRIEND = 'usr_00000000-0000-4000-8000-000000000001';

function mountCell(columns, id, original) {
    const column = columns.find((col) => (col.id ?? col.accessorKey) === id);
    const Cell = defineComponent({
        render: () =>
            h('div', { 'data-cell': id }, [
                column.cell({ row: { original, getValue: (key) => original[key], getIsSelected: () => false } })
            ])
    });
    return mount(Cell);
}

describe('friend list cards in the phone layout', () => {
    let onToggleFriendSelection;
    let selectedFriends;
    let columns;

    beforeEach(() => {
        mocks.isCompact.value = true;
        onToggleFriendSelection = vi.fn();
        selectedFriends = ref(new Set());
        columns = createColumns({
            randomUserColours: ref(false),
            selectedFriends,
            onToggleFriendSelection,
            onConfirmDeleteFriend: vi.fn(),
            userImage: () => ''
        });
    });

    test('bulk select: a 40px target around the checkbox; a tap anywhere on it toggles once', async () => {
        const wrapper = mountCell(columns, 'bulkSelect', { id: FRIEND });
        const target = wrapper.find('[data-testid="friend-bulk-select"]');
        expect(target.classes()).toEqual(expect.arrayContaining(['size-10', 'flex', 'items-center', 'justify-center']));

        await target.trigger('click');
        expect(onToggleFriendSelection).toHaveBeenCalledTimes(1);
        expect(onToggleFriendSelection).toHaveBeenLastCalledWith(FRIEND);

        // The checkbox itself toggles through its own update event only (no double toggle).
        await wrapper.find('[role="checkbox"]').trigger('click');
        expect(onToggleFriendSelection).toHaveBeenCalledTimes(2);
    });

    test('bulk select does not let the tap reach the card (which would open the user)', async () => {
        const cardClick = vi.fn();
        const column = columns.find((col) => col.id === 'bulkSelect');
        const Card = defineComponent({
            render: () => h('div', { onClick: cardClick }, [column.cell({ row: { original: { id: FRIEND } } })])
        });
        const wrapper = mount(Card);
        await wrapper.find('[data-testid="friend-bulk-select"]').trigger('click');
        expect(cardClick).not.toHaveBeenCalled();
    });

    test('PC keeps the plain checkbox cell', () => {
        mocks.isCompact.value = false;
        const wrapper = mountCell(columns, 'bulkSelect', { id: FRIEND });
        expect(wrapper.find('[data-testid="friend-bulk-select"]').exists()).toBe(false);
        expect(wrapper.find('[role="checkbox"]').exists()).toBe(true);
    });

    test('dates the card cannot expand to show are labelled footer stats', () => {
        const friend = {
            id: FRIEND,
            last_activity: '2026-09-25T10:00:00Z',
            last_login: '2026-09-24T09:00:00Z',
            date_joined: '2022-05-01'
        };
        const slotOf = (id) => columns.find((col) => col.id === id).meta.mobile.slot;
        for (const id of ['lastActivity', 'lastLogin', 'dateJoined']) {
            expect(slotOf(id)).toBe('footer');
        }
        expect(mountCell(columns, 'lastActivity', friend).text()).toBe(
            'table.friendList.lastActivity: long:2026-09-25T10:00:00Z'
        );
        expect(mountCell(columns, 'lastLogin', friend).text()).toBe(
            'table.friendList.lastLogin: long:2026-09-24T09:00:00Z'
        );
        expect(mountCell(columns, 'dateJoined', friend).text()).toBe('table.friendList.dateJoined: 2022-05-01');
    });

    test('empty stats render nothing, so their footer entry collapses', () => {
        const friend = { id: FRIEND };
        for (const id of ['lastActivity', 'lastLogin', 'dateJoined', 'joinCount', 'timeTogether', 'lastSeen']) {
            const wrapper = mountCell(columns, id, friend);
            expect(wrapper.find(`[data-cell="${id}"]`).text()).toBe('');
            expect(wrapper.find(`[data-cell="${id}"] span`).exists()).toBe(false);
        }
        for (const id of ['lastActivity', 'lastLogin', 'dateJoined', 'joinCount', 'timeTogether', 'lastSeen']) {
            const footerClass = columns.find((col) => col.id === id).meta.mobile.class;
            expect(footerClass).toContain('has-[>div:empty]:hidden');
            // Long stats wrap between label and value instead of being cut off.
            expect(footerClass).toContain('[&>div]:whitespace-normal');
        }
    });

    test('PC date cells are unchanged', () => {
        mocks.isCompact.value = false;
        const friend = { id: FRIEND, last_activity: '2026-09-25T10:00:00Z', date_joined: '2022-05-01' };
        expect(mountCell(columns, 'lastActivity', friend).text()).toBe('long:2026-09-25T10:00:00Z');
        expect(mountCell(columns, 'dateJoined', friend).text()).toBe('2022-05-01');
        expect(mountCell(columns, 'lastLogin', { id: FRIEND }).text()).toBe('-');
    });
});

describe('friend list row taps', () => {
    test('phones in bulk unfriend mode select the friend', () => {
        expect(isRowTapSelection({ isCompact: true, bulkMode: true, friendId: FRIEND })).toBe(true);
    });

    test('otherwise a tap opens the user, as on PC', () => {
        expect(isRowTapSelection({ isCompact: false, bulkMode: true, friendId: FRIEND })).toBe(false);
        expect(isRowTapSelection({ isCompact: true, bulkMode: false, friendId: FRIEND })).toBe(false);
        expect(isRowTapSelection({ isCompact: true, bulkMode: true, friendId: null })).toBe(false);
    });
});

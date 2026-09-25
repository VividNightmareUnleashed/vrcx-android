import { describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, ref } from 'vue';
import { mount } from '@vue/test-utils';
import { getCoreRowModel, getExpandedRowModel, getPaginationRowModel, useVueTable } from '@tanstack/vue-table';

vi.mock('@/stores/', () => ({
    useAppearanceSettingsStore: () => ({ isDataTableStriped: ref(true) }),
    useUiStore: () => ({ shiftHeld: false })
}));
vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));

import { i18n } from '../../../../plugins/i18n';
import DataTableCardList from '../DataTableCardList.vue';
import DataTableLayout from '../DataTableLayout.vue';

const rows = [
    { id: 'a', name: 'Alice', type: 'GPS', time: '14:02', detail: 'World A', note: 'first', secret: 'x' },
    { id: 'b', name: 'Bob', type: 'Avatar', time: '13:58', detail: 'Avatar B', note: 'second', secret: 'y' },
    { id: 'c', name: 'Cleo', type: 'Online', time: '13:40', detail: 'World C', note: 'third', secret: 'z' }
];

const columns = [
    {
        id: 'expander',
        header: () => null,
        meta: {
            mobile: 'leading',
            expandedRow: ({ row }) => h('div', { class: 'expanded-content' }, `more ${row.id}`)
        },
        cell: ({ row }) =>
            h(
                'button',
                {
                    class: 'expander',
                    onClick: (event) => {
                        event.stopPropagation();
                        row.toggleExpanded();
                    }
                },
                '>'
            )
    },
    {
        accessorKey: 'time',
        meta: { label: 'Date', mobile: { slot: 'trailing' } },
        cell: ({ row }) => h('span', { class: 'cell-time' }, row.original.time)
    },
    {
        accessorKey: 'type',
        meta: { label: 'Type', mobile: { slot: 'badge' } },
        cell: ({ row }) => h('span', { class: 'cell-type' }, row.original.type)
    },
    {
        accessorKey: 'name',
        meta: { label: 'User', mobile: { slot: 'title' } },
        cell: ({ row }) => h('span', { class: 'cell-name cursor-pointer' }, row.original.name)
    },
    {
        accessorKey: 'detail',
        meta: { label: 'Detail', mobile: 'body' },
        cell: ({ row }) => h('span', { class: 'cell-detail' }, row.original.detail)
    },
    {
        accessorKey: 'note',
        meta: { label: 'Note' },
        cell: ({ row }) => h('span', { class: 'cell-note' }, row.original.note)
    },
    {
        accessorKey: 'secret',
        meta: { label: 'Secret', mobile: { slot: 'hidden' } },
        cell: ({ row }) => h('span', { class: 'cell-secret' }, row.original.secret)
    },
    {
        id: 'extra',
        meta: { label: 'Extra', mobile: { slot: 'detail' } },
        cell: ({ row }) => h('span', { class: 'cell-extra' }, `extra ${row.id}`)
    }
];

/**
 * Mounts a component with a real TanStack table built in setup.
 *
 * @param {object} options
 */
function mountWithTable({ component = DataTableCardList, props = {}, tableOptions = {}, slots = {} } = {}) {
    let table;
    const Host = defineComponent({
        setup() {
            table = useVueTable({
                data: rows,
                columns,
                getRowId: (row) => row.id,
                getCoreRowModel: getCoreRowModel(),
                getExpandedRowModel: getExpandedRowModel(),
                getRowCanExpand: () => true,
                ...tableOptions
            });
            const expandedRenderer = columns[0].meta.expandedRow;
            return () =>
                h(
                    component,
                    {
                        table,
                        ...(component === DataTableCardList ? { expandedRenderer } : {}),
                        ...props
                    },
                    slots
                );
        }
    });
    const wrapper = mount(Host, { global: { plugins: [i18n] }, attachTo: document.body });
    return { wrapper, getTable: () => table };
}

describe('DataTableCardList (card mode, DESIGN.md §3.1)', () => {
    it('places each visible cell in the slot its column declares', () => {
        const { wrapper } = mountWithTable();
        const cards = wrapper.findAll('[data-card-row]');
        expect(cards).toHaveLength(3);

        const first = cards[0];
        expect(first.find('.font-medium .cell-name').text()).toBe('Alice');
        expect(first.find('.tabular-nums .cell-time').text()).toBe('14:02');
        expect(first.find('.flex-wrap .cell-type').text()).toBe('GPS');
        expect(first.find('.line-clamp-2 .cell-detail').text()).toBe('World A');
        // No hint: footer, prefixed with the column label.
        expect(first.find('.text-\\[11px\\]').text()).toContain('Note:');
        expect(first.find('.cell-note').text()).toBe('first');
        // `hidden` never renders; `detail` only while expanded.
        expect(first.find('.cell-secret').exists()).toBe(false);
        expect(first.find('.cell-extra').exists()).toBe(false);
        wrapper.unmount();
    });

    it('respects column visibility', async () => {
        const { wrapper, getTable } = mountWithTable();
        getTable().getColumn('type').toggleVisibility(false);
        await nextTick();
        expect(wrapper.find('.cell-type').exists()).toBe(false);
        expect(wrapper.find('.cell-name').exists()).toBe(true);
        wrapper.unmount();
    });

    it('calls onRowClick with the row when the table has a row handler', async () => {
        const onRowClick = vi.fn();
        const { wrapper } = mountWithTable({ props: { onRowClick } });
        await wrapper.findAll('[data-card-row]')[1].trigger('click');
        expect(onRowClick).toHaveBeenCalledTimes(1);
        expect(onRowClick.mock.calls[0][0].id).toBe('b');
        wrapper.unmount();
    });

    it('expands a row on tap and shows the expanded renderer and detail cells', async () => {
        const { wrapper } = mountWithTable();
        const card = () => wrapper.findAll('[data-card-row]')[0];

        await card().trigger('click');
        expect(card().attributes('data-state')).toBe('expanded');
        expect(card().find('.expanded-content').text()).toBe('more a');
        expect(card().find('.cell-extra').text()).toBe('extra a');

        await card().trigger('click');
        expect(card().attributes('data-state')).toBe('collapsed');
        expect(card().find('.expanded-content').exists()).toBe(false);
        wrapper.unmount();
    });

    it('leaves taps on clickable content to that content', async () => {
        const { wrapper } = mountWithTable();
        await wrapper.findAll('.cell-name')[0].trigger('click');
        expect(wrapper.findAll('[data-card-row]')[0].attributes('data-state')).toBe('collapsed');
        wrapper.unmount();
    });

    it('stripes every other card like the PC table', () => {
        const { wrapper } = mountWithTable({ props: { striped: true } });
        const cards = wrapper.findAll('[data-card-row]');
        expect(cards[0].classes()).not.toContain('bg-muted/20');
        expect(cards[1].classes()).toContain('bg-muted/20');
        wrapper.unmount();
    });

    it('wraps cards in the row context menu when the slot is used', () => {
        const { wrapper } = mountWithTable({
            slots: { 'row-context-menu': () => h('div', { class: 'menu-content' }) }
        });
        const cards = wrapper.findAll('[data-card-row]');
        expect(cards).toHaveLength(3);
        for (const card of cards) {
            expect(card.attributes('data-slot')).toBe('context-menu-trigger');
        }
        wrapper.unmount();
    });

    it('renders only the current page of rows', () => {
        const { wrapper } = mountWithTable({
            tableOptions: {
                getPaginationRowModel: getPaginationRowModel(),
                initialState: { pagination: { pageIndex: 1, pageSize: 2 } }
            }
        });
        const names = wrapper.findAll('.cell-name').map((node) => node.text());
        expect(names).toEqual(['Cleo']);
        wrapper.unmount();
    });

    it('shows the empty slot without rows', () => {
        let table;
        const Host = defineComponent({
            setup() {
                table = useVueTable({ data: [], columns, getCoreRowModel: getCoreRowModel() });
                return () => h(DataTableCardList, { table }, { empty: () => h('p', { class: 'empty' }, 'nothing') });
            }
        });
        const wrapper = mount(Host, { global: { plugins: [i18n] } });
        expect(wrapper.find('.empty').text()).toBe('nothing');
        wrapper.unmount();
    });
});

describe('DataTableLayout mobileMode', () => {
    it('keeps the PC table by default outside the phone layout', () => {
        const { wrapper } = mountWithTable({ component: DataTableLayout, props: { showPagination: false } });
        expect(wrapper.find('table').exists()).toBe(true);
        expect(wrapper.find('[data-slot="data-table-cards"]').exists()).toBe(false);
        wrapper.unmount();
    });

    it('renders cards when forced, with a View options button instead of the header menu', () => {
        const { wrapper } = mountWithTable({
            component: DataTableLayout,
            props: { mobileMode: 'cards', showPagination: false, pageSizes: [10, 20] }
        });
        expect(wrapper.find('table').exists()).toBe(false);
        expect(wrapper.findAll('[data-card-row]')).toHaveLength(3);
        expect(wrapper.find('[data-slot="data-table-view-options"]').exists()).toBe(true);
        wrapper.unmount();
    });
});

// Card slots honour columnDef.meta.mobile.class in every slot (merged over the slot's own classes), and an actions
// group can wrap under the card body instead of squeezing the footer (docs/DESIGN.md §3.1).
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, ref } from 'vue';
import { mount } from '@vue/test-utils';
import { getCoreRowModel, useVueTable } from '@tanstack/vue-table';

vi.mock('@/stores/', () => ({
    useAppearanceSettingsStore: () => ({ isDataTableStriped: ref(false) }),
    useUiStore: () => ({ shiftHeld: false })
}));
vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));

import { i18n } from '../../../../plugins/i18n';
import DataTableCardList from '../DataTableCardList.vue';

const rows = [{ id: 'a', icon: 'I', name: 'Alice', time: '14:02', detail: 'World A', source: 'PC' }];

function buildColumns({ actions = 1 } = {}) {
    return [
        {
            id: 'icon',
            meta: { mobile: { slot: 'leading', class: 'lead-class self-center' } },
            cell: () => h('span', { class: 'cell-icon' }, 'I')
        },
        {
            accessorKey: 'name',
            meta: { label: 'User', mobile: { slot: 'title', class: 'title-class' } },
            cell: ({ row }) => h('span', { class: 'cell-name' }, row.original.name)
        },
        {
            accessorKey: 'time',
            meta: { label: 'Date', mobile: { slot: 'trailing', class: 'text-sm' } },
            cell: ({ row }) => h('span', { class: 'cell-time' }, row.original.time)
        },
        {
            accessorKey: 'detail',
            meta: { label: 'Detail', mobile: { slot: 'body', class: 'line-clamp-none' } },
            cell: ({ row }) => h('span', { class: 'cell-detail' }, row.original.detail)
        },
        {
            accessorKey: 'source',
            meta: { label: 'Source', mobile: { slot: 'footer', label: true } },
            cell: ({ row }) => h('span', { class: 'cell-source' }, row.original.source)
        },
        {
            id: 'actions',
            meta: { mobile: { slot: 'actions' } },
            cell: () =>
                h(
                    'div',
                    { class: 'inline-flex gap-1' },
                    Array.from({ length: actions }, (_, index) => h('button', { class: 'action' }, String(index)))
                )
        }
    ];
}

function mountCards(options) {
    const columns = buildColumns(options);
    const Host = defineComponent({
        setup() {
            const table = useVueTable({
                data: rows,
                columns,
                getRowId: (row) => row.id,
                getCoreRowModel: getCoreRowModel()
            });
            return () => h(DataTableCardList, { table });
        }
    });
    return mount(Host, { global: { plugins: [i18n] }, attachTo: document.body });
}

describe('DataTableCardRow slots', () => {
    afterEach(() => {
        document.body.innerHTML = '';
    });

    it('applies the hint class to leading and title cells too', () => {
        const wrapper = mountCards();
        const leading = wrapper.find('[data-card-slot="leading"]');
        expect(leading.classes()).toEqual(expect.arrayContaining(['lead-class', 'self-center']));
        expect(leading.find('.cell-icon').exists()).toBe(true);

        const title = wrapper.find('[data-card-slot="title"]');
        expect(title.classes()).toContain('title-class');
        expect(title.find('.cell-name').text()).toBe('Alice');
        wrapper.unmount();
    });

    it('leaves cells without a hint class as direct children of their slot box', () => {
        const columns = buildColumns();
        delete columns[0].meta.mobile.class;
        const Host = defineComponent({
            setup() {
                const table = useVueTable({ data: rows, columns, getCoreRowModel: getCoreRowModel() });
                return () => h(DataTableCardList, { table });
            }
        });
        const wrapper = mount(Host, { global: { plugins: [i18n] } });
        expect(wrapper.find('[data-card-slot="leading"]').classes()).toEqual(['contents']);
        wrapper.unmount();
    });

    it('lets the hint class override the slot defaults', () => {
        const wrapper = mountCards();
        const body = wrapper.find('.cell-detail').element.parentElement;
        expect(body.classList.contains('line-clamp-none')).toBe(true);
        expect(body.classList.contains('line-clamp-2')).toBe(false);

        const trailing = wrapper.find('.cell-time').element.parentElement;
        expect(trailing.classList.contains('text-sm')).toBe(true);
        expect(trailing.classList.contains('text-xs')).toBe(false);
        wrapper.unmount();
    });

    it('lets an actions group wrap under the body instead of squeezing the footer', () => {
        const wrapper = mountCards({ actions: 3 });
        const actions = wrapper.find('[data-card-slot="actions"]');
        expect(actions.findAll('.action')).toHaveLength(3);
        expect(actions.classes()).toEqual(expect.arrayContaining(['flex-wrap', 'max-w-full', 'justify-end']));

        const row = actions.element.parentElement;
        expect(row.classList.contains('flex-wrap')).toBe(true);
        // The footer keeps a base width, so a wide actions group moves to its own line on narrow cards.
        const footer = wrapper.find('.cell-source').element.closest('.basis-40');
        expect(footer).not.toBeNull();
        expect(footer.parentElement).toBe(row);
        wrapper.unmount();
    });
});

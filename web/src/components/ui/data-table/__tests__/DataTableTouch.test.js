// DataTableLayout on phones and touch screens (docs/DESIGN.md §3.1, §3.3): auto card mode, View options, compact
// pagination, no resize handles on coarse pointers, and the quick-actions (Shift) toggle.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, reactive, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { getCoreRowModel, getPaginationRowModel, getSortedRowModel, useVueTable } from '@tanstack/vue-table';

const layout = vi.hoisted(() => ({ compact: null, coarse: null }));
vi.mock('@/composables/useCompactLayout', async () => {
    const { ref: vueRef } = await import('vue');
    layout.compact = vueRef(true);
    layout.coarse = vueRef(true);
    return {
        useCompactLayout: () => ({
            isCompact: layout.compact,
            isCoarsePointer: layout.coarse,
            isCompactLandscape: vueRef(false)
        })
    };
});

const ui = vi.hoisted(() => ({ store: null }));
vi.mock('@/stores/', () => ({
    useAppearanceSettingsStore: () => ({ isDataTableStriped: ref(false) }),
    useUiStore: () => ui.store
}));
vi.mock('@/stores', () => ({
    useAppearanceSettingsStore: () => ({ isDataTableStriped: ref(false) }),
    useUiStore: () => ui.store
}));
vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));
vi.mock('@/shared/utils/platform', () => ({ isAndroid: true }));

import { i18n } from '../../../../plugins/i18n';
import { TooltipProvider } from '../../tooltip';
import DataTableLayout from '../DataTableLayout.vue';

const rows = [
    { id: 'a', name: 'Alice', type: 'GPS' },
    { id: 'b', name: 'Bob', type: 'Avatar' },
    { id: 'c', name: 'Cleo', type: 'Online' }
];

function buildColumns({ hints = true, quickActions = false } = {}) {
    return [
        {
            accessorKey: 'name',
            enableSorting: true,
            meta: { label: 'User', ...(hints ? { mobile: 'title' } : {}) },
            cell: ({ row }) => h('span', { class: 'cell-name' }, row.original.name)
        },
        {
            accessorKey: 'type',
            enableSorting: true,
            meta: { label: 'Type', ...(hints ? { mobile: 'badge' } : {}), ...(quickActions ? { quickActions } : {}) },
            cell: ({ row }) => h('span', { class: 'cell-type' }, row.original.type)
        }
    ];
}

function mountLayout({ columns = buildColumns(), props = {}, tableOptions = {}, meta = {} } = {}) {
    let table;
    const Host = defineComponent({
        setup() {
            table = useVueTable({
                data: rows,
                columns,
                getRowId: (row) => row.id,
                getCoreRowModel: getCoreRowModel(),
                getSortedRowModel: getSortedRowModel(),
                getPaginationRowModel: getPaginationRowModel(),
                meta,
                ...tableOptions
            });
            // App.vue provides the tooltip context in the app.
            return () =>
                h(TooltipProvider, null, () =>
                    h(DataTableLayout, { table, pageSizes: [10, 20], totalItems: rows.length, ...props })
                );
        }
    });
    const wrapper = mount(Host, { global: { plugins: [i18n] }, attachTo: document.body });
    return { wrapper, getTable: () => table };
}

async function openViewOptions(wrapper) {
    await wrapper.find('[data-slot="data-table-view-options"]').trigger('click');
    await flushPromises();
    await nextTick();
    return document.body.querySelector('[data-slot="sheet-content"]');
}

describe('DataTableLayout on phones', () => {
    beforeEach(() => {
        layout.compact.value = true;
        layout.coarse.value = true;
        ui.store = reactive({ shiftHeld: false });
    });

    afterEach(() => {
        document.body.innerHTML = '';
    });

    it('switches to cards on its own when the columns declare card hints', () => {
        const { wrapper } = mountLayout();
        expect(wrapper.find('table').exists()).toBe(false);
        expect(wrapper.findAll('[data-card-row]')).toHaveLength(3);
        wrapper.unmount();
    });

    it('keeps tables without hints as tables, and the PC layout outside compact', async () => {
        const { wrapper } = mountLayout({ columns: buildColumns({ hints: false }) });
        expect(wrapper.find('table').exists()).toBe(true);
        wrapper.unmount();

        layout.compact.value = false;
        const desktop = mountLayout();
        expect(desktop.wrapper.find('table').exists()).toBe(true);
        expect(desktop.wrapper.find('[data-slot="data-table-view-options"]').exists()).toBe(false);
        expect(desktop.wrapper.find('.dt-pagination-sizes').exists()).toBe(true);
        desktop.wrapper.unmount();
    });

    it('moves the page-size selector into View options and drops sibling pages', () => {
        const { wrapper } = mountLayout();
        expect(wrapper.find('.dt-pagination').exists()).toBe(true);
        expect(wrapper.find('.dt-pagination-sizes').exists()).toBe(false);
        expect(wrapper.find('.dt-pagination-spacer').exists()).toBe(false);
        expect(wrapper.find('[data-slot="data-table-view-options"]').exists()).toBe(true);
        wrapper.unmount();
    });

    it('does not render column resize handles on coarse pointers', () => {
        const options = { tableOptions: { enableColumnResizing: true, columnResizeMode: 'onChange' } };
        const columns = buildColumns({ hints: false });

        const touch = mountLayout({ columns, ...options });
        expect(touch.wrapper.find('table').exists()).toBe(true);
        expect(touch.wrapper.findAll('.cursor-col-resize')).toHaveLength(0);
        touch.wrapper.unmount();

        layout.coarse.value = false;
        const mouse = mountLayout({ columns, ...options });
        expect(mouse.wrapper.findAll('.cursor-col-resize').length).toBeGreaterThan(0);
        mouse.wrapper.unmount();
    });

    it('View options toggles columns, flips the sort direction and resets', async () => {
        const resetAll = vi.fn();
        const { wrapper, getTable } = mountLayout({ meta: { resetAll } });
        getTable().setSorting([{ id: 'name', desc: false }]);
        await nextTick();

        const sheet = await openViewOptions(wrapper);
        expect(sheet).not.toBeNull();
        expect(sheet.textContent).toContain('User');
        expect(sheet.textContent).toContain('Type');

        const checkboxes = sheet.querySelectorAll('[role="checkbox"]');
        expect(checkboxes).toHaveLength(2);
        checkboxes[1].click();
        await nextTick();
        expect(getTable().getColumn('type').getIsVisible()).toBe(false);
        expect(wrapper.find('.cell-type').exists()).toBe(false);

        const direction = [...sheet.querySelectorAll('button')].find((b) =>
            /ascending/i.test(b.getAttribute('aria-label') ?? '')
        );
        direction.click();
        await nextTick();
        expect(getTable().getState().sorting).toEqual([{ id: 'name', desc: true }]);
        expect(wrapper.findAll('.cell-name').map((n) => n.text())).toEqual(['Cleo', 'Bob', 'Alice']);

        const reset = [...sheet.querySelectorAll('button')].find((b) => b.textContent.includes('Reset'));
        reset.click();
        await flushPromises();
        expect(resetAll).toHaveBeenCalledTimes(1);
        wrapper.unmount();
    });

    it('shows the quick-actions toggle for tables with Shift actions and releases it when leaving', async () => {
        const plain = mountLayout();
        expect(plain.wrapper.find('[data-slot="quick-actions-toggle"]').exists()).toBe(false);
        plain.wrapper.unmount();

        const { wrapper } = mountLayout({ columns: buildColumns({ quickActions: true }) });
        const toggle = wrapper.find('[data-slot="quick-actions-toggle"]');
        expect(toggle.exists()).toBe(true);

        await toggle.trigger('click');
        expect(ui.store.shiftHeld).toBe(true);
        expect(toggle.attributes('data-state')).toBe('on');

        await toggle.trigger('click');
        expect(ui.store.shiftHeld).toBe(false);

        await toggle.trigger('click');
        expect(ui.store.shiftHeld).toBe(true);
        wrapper.unmount();
        expect(ui.store.shiftHeld).toBe(false);
    });
});

// DataTableLayout's phone toolbar (docs/DESIGN.md §3.1, §2.4): the toolbar content and the touch tools are separate
// slots that mobile.css arranges, and in phone landscape a page-filling table moves its pagination into the toolbar
// row (data-pagination="inline").
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, reactive, ref } from 'vue';
import { mount } from '@vue/test-utils';
import { getCoreRowModel, getPaginationRowModel, useVueTable } from '@tanstack/vue-table';

const layout = vi.hoisted(() => ({ compact: null, landscape: null, coarse: null }));
vi.mock('@/composables/useCompactLayout', async () => {
    const { ref: vueRef } = await import('vue');
    layout.compact = vueRef(true);
    layout.landscape = vueRef(false);
    layout.coarse = vueRef(true);
    return {
        useCompactLayout: () => ({
            isCompact: layout.compact,
            isCompactLandscape: layout.landscape,
            isCoarsePointer: layout.coarse
        })
    };
});

// jsdom has no layout: the table width comes from here.
const size = vi.hoisted(() => ({ width: null }));
vi.mock('@vueuse/core', async (importOriginal) => {
    const { ref: vueRef } = await import('vue');
    size.width = vueRef(766);
    return { ...(await importOriginal()), useElementSize: () => ({ width: size.width, height: vueRef(0) }) };
});

vi.mock('@/stores/', () => ({
    useAppearanceSettingsStore: () => ({ isDataTableStriped: ref(false) }),
    useUiStore: () => reactive({ shiftHeld: false })
}));
vi.mock('@/stores', () => ({
    useAppearanceSettingsStore: () => ({ isDataTableStriped: ref(false) }),
    useUiStore: () => reactive({ shiftHeld: false })
}));
vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));
vi.mock('@/shared/utils/platform', () => ({ isAndroid: true }));

import { i18n } from '../../../../plugins/i18n';
import { TooltipProvider } from '../../tooltip';
import DataTableLayout from '../DataTableLayout.vue';
import {
    estimateCompactPaginationWidth,
    INLINE_PAGINATION_TOOLBAR_MIN_PX,
    shouldInlinePagination
} from '../dataTableHelpers.js';

const rows = Array.from({ length: 30 }, (_, index) => ({ id: `r${index}`, name: `Row ${index}` }));
const columns = [
    {
        accessorKey: 'name',
        enableSorting: true,
        meta: { label: 'Name', mobile: 'title' },
        cell: ({ row }) => h('span', { class: 'cell-name' }, row.original.name)
    }
];

function mountLayout(props = {}, slots) {
    const Host = defineComponent({
        setup() {
            const table = useVueTable({
                data: rows,
                columns,
                getRowId: (row) => row.id,
                getCoreRowModel: getCoreRowModel(),
                getPaginationRowModel: getPaginationRowModel(),
                initialState: { pagination: { pageIndex: 0, pageSize: 10 } }
            });
            return () =>
                h(TooltipProvider, null, () =>
                    h(
                        DataTableLayout,
                        { table, pageSizes: [10, 20], totalItems: rows.length, autoHeight: true, ...props },
                        slots
                    )
                );
        }
    });
    return mount(Host, { global: { plugins: [i18n] }, attachTo: document.body });
}

const stackedToolbar = {
    toolbar: () =>
        h('div', { class: 'flex flex-col gap-2', 'data-testid': 'view-toolbar' }, [
            h('input', { class: 'search' }),
            h('div', { class: 'filters' }, 'Filters')
        ])
};

describe('DataTableLayout phone toolbar', () => {
    beforeEach(() => {
        layout.compact.value = true;
        layout.landscape.value = false;
        layout.coarse.value = true;
        size.width.value = 766;
    });

    afterEach(() => {
        document.body.innerHTML = '';
    });

    it('keeps the view toolbar and the touch tools in their own slots of one toolbar', () => {
        const wrapper = mountLayout({ quickActions: true }, stackedToolbar);
        const toolbar = wrapper.find('[data-slot="data-table-toolbar"]');
        const content = toolbar.find('[data-slot="data-table-toolbar-content"]');
        const tools = toolbar.find('[data-slot="data-table-toolbar-tools"]');

        expect(content.find('[data-testid="view-toolbar"]').exists()).toBe(true);
        expect(tools.find('[data-slot="quick-actions-toggle"]').exists()).toBe(true);
        expect(tools.find('[data-slot="data-table-view-options"]').exists()).toBe(true);
        // The content comes first, so the tools share its first row (mobile.css).
        expect(toolbar.element.firstElementChild).toBe(content.element);
        wrapper.unmount();
    });

    it('keeps the pagination below the list in portrait', () => {
        const wrapper = mountLayout({}, stackedToolbar);
        expect(wrapper.find('.data-table').attributes('data-pagination')).toBeUndefined();
        wrapper.unmount();
    });

    it('moves the pagination into the toolbar row of a page-filling table in phone landscape', async () => {
        layout.landscape.value = true;
        const wrapper = mountLayout({}, stackedToolbar);
        expect(wrapper.find('.data-table').attributes('data-pagination')).toBe('inline');

        layout.landscape.value = false;
        await nextTick();
        expect(wrapper.find('.data-table').attributes('data-pagination')).toBeUndefined();
        wrapper.unmount();
    });

    it('keeps the pagination below the list when it would leave the toolbar too little room', async () => {
        layout.landscape.value = true;
        // 406px: the table beside the open friends panel at 844 x 390. Three pages (five pagination items) and View
        // options leave the toolbar less than INLINE_PAGINATION_TOOLBAR_MIN_PX.
        size.width.value = 406;
        const wrapper = mountLayout({}, stackedToolbar);
        expect(wrapper.find('.data-table').attributes('data-pagination')).toBeUndefined();

        size.width.value = 766;
        await nextTick();
        expect(wrapper.find('.data-table').attributes('data-pagination')).toBe('inline');
        wrapper.unmount();
    });

    it('inlines a short pagination in narrower landscape tables (small phones, the open friends panel)', () => {
        layout.landscape.value = true;
        size.width.value = 406;
        const wrapper = mountLayout({ totalItems: 8 }, stackedToolbar);
        expect(wrapper.find('.data-table').attributes('data-pagination')).toBe('inline');
        wrapper.unmount();

        // 640 x 360: a 562px table fits the three-page pagination next to the toolbar.
        size.width.value = 562;
        const small = mountLayout({}, stackedToolbar);
        expect(small.find('.data-table').attributes('data-pagination')).toBe('inline');
        small.unmount();
    });

    it('inlines the pagination next to the tools of a table without its own toolbar (My Avatars)', () => {
        layout.landscape.value = true;
        size.width.value = 406;
        const wrapper = mountLayout({});
        expect(wrapper.find('[data-slot="data-table-toolbar-content"]').exists()).toBe(false);
        expect(wrapper.find('.data-table').attributes('data-pagination')).toBe('inline');
        wrapper.unmount();
    });

    it('keeps the pagination in place for tables that scroll with their page, and without pagination', () => {
        layout.landscape.value = true;
        const scrolling = mountLayout({ autoHeight: false }, stackedToolbar);
        expect(scrolling.find('.data-table').attributes('data-pagination')).toBeUndefined();
        scrolling.unmount();

        const noPagination = mountLayout({ showPagination: false }, stackedToolbar);
        expect(noPagination.find('.data-table').attributes('data-pagination')).toBeUndefined();
        noPagination.unmount();
    });

    it('never inlines the pagination outside the phone layout', () => {
        layout.compact.value = false;
        layout.landscape.value = false;
        const wrapper = mountLayout({ quickActions: true }, stackedToolbar);
        expect(wrapper.find('.data-table').attributes('data-pagination')).toBeUndefined();
        wrapper.unmount();
    });

    it('lets a PC toolbar scroll sideways in the touch tablet frame, and leaves it alone on desktop', () => {
        layout.compact.value = false;
        const tablet = mountLayout({}, stackedToolbar);
        const scroller = tablet.find('[data-slot="data-table-toolbar-scroller"]');
        expect(tablet.find('[data-slot="data-table-toolbar"]').exists()).toBe(false);
        expect(scroller.classes()).toEqual(expect.arrayContaining(['mb-2', 'overflow-x-auto', 'overflow-y-hidden']));
        expect(scroller.find('.search').exists()).toBe(true);
        tablet.unmount();

        layout.coarse.value = false;
        const desktop = mountLayout({}, stackedToolbar);
        const wrapperDiv = desktop.find('.search').element.closest('.data-table > div');
        expect(wrapperDiv.className).toBe('mb-2');
        expect(wrapperDiv.hasAttribute('data-slot')).toBe(false);
        desktop.unmount();
    });

    it('shows icon-only Previous and Next on phones', () => {
        const wrapper = mountLayout({}, stackedToolbar);
        const previous = wrapper.find('[data-slot="pagination-previous"]');
        const next = wrapper.find('[data-slot="pagination-next"]');
        expect(previous.classes()).toContain('[&>span]:hidden');
        expect(next.classes()).toContain('[&>span]:hidden');
        wrapper.unmount();

        layout.compact.value = false;
        const desktop = mountLayout({}, stackedToolbar);
        expect(desktop.find('[data-slot="pagination-previous"]').classes()).not.toContain('[&>span]:hidden');
        desktop.unmount();
    });
});

describe('shouldInlinePagination', () => {
    it('sizes the compact pagination from the page count (up to five items plus Previous and Next)', () => {
        expect(estimateCompactPaginationWidth(0)).toBe(3 * 32 + 2 * 4);
        expect(estimateCompactPaginationWidth(1)).toBe(3 * 32 + 2 * 4);
        expect(estimateCompactPaginationWidth(3)).toBe(5 * 32 + 4 * 4);
        expect(estimateCompactPaginationWidth(200)).toBe(7 * 32 + 6 * 4);
    });

    it('inlines while the toolbar keeps its minimum next to the tools and the pagination', () => {
        const base = { pageCount: 20, toolCount: 1, hasToolbar: true };
        // 844 x 390 and 640 x 360 phones, the widest pagination.
        expect(shouldInlinePagination({ ...base, tableWidth: 766 })).toBe(true);
        expect(shouldInlinePagination({ ...base, tableWidth: 562 })).toBe(true);
        // Beside the open friends panel (406px) only a short pagination fits.
        expect(shouldInlinePagination({ ...base, tableWidth: 406 })).toBe(false);
        expect(shouldInlinePagination({ ...base, tableWidth: 406, pageCount: 1 })).toBe(true);
        // The exact edge: toolbar = width - pagination - gap - tools - gap.
        const edge = INLINE_PAGINATION_TOOLBAR_MIN_PX + estimateCompactPaginationWidth(20) + 8 + 32 + 8;
        expect(shouldInlinePagination({ ...base, tableWidth: edge })).toBe(true);
        expect(shouldInlinePagination({ ...base, tableWidth: edge - 1 })).toBe(false);
        // Quick actions take their share.
        expect(shouldInlinePagination({ ...base, tableWidth: edge, toolCount: 2 })).toBe(false);
    });

    it('needs no toolbar room when the view passes no toolbar, and a measured width', () => {
        expect(shouldInlinePagination({ tableWidth: 300, pageCount: 20, toolCount: 1, hasToolbar: false })).toBe(true);
        expect(shouldInlinePagination({ tableWidth: 0, pageCount: 1, toolCount: 1, hasToolbar: true })).toBe(false);
        expect(shouldInlinePagination({ tableWidth: Number.NaN, pageCount: 1, toolCount: 1, hasToolbar: false })).toBe(
            false
        );
    });
});

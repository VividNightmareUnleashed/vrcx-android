// platform/android/mobile.css against the DOM DataTableLayout really renders (docs/DESIGN.md §2.4, §3.1, §3.3): the
// portrait toolbar grid, the inline pagination in phone landscape, the 40px hit areas of the table chrome and the
// overflow margin that lets them reach past the table. jsdom has no layout, so this checks which rules apply to which
// element (the cascade and the selectors), not pixel sizes; the harness screenshots cover those.
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, reactive, ref } from 'vue';
import { mount } from '@vue/test-utils';
import { getCoreRowModel, getPaginationRowModel, useVueTable } from '@tanstack/vue-table';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

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
vi.mock('@vueuse/core', async (importOriginal) => {
    const { ref: vueRef } = await import('vue');
    return { ...(await importOriginal()), useElementSize: () => ({ width: vueRef(766), height: vueRef(0) }) };
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

const MOBILE_CSS = readFileSync(resolve(import.meta.dirname, '../../../../platform/android/mobile.css'), 'utf8');

/**
 * Loads mobile.css. jsdom never matches `(pointer: coarse)`, so the rules of that block are added a second time
 * without the media query: the document then behaves like a touch screen.
 */
function loadMobileCss() {
    // The few Tailwind utilities these checks depend on (the app has them in @layer utilities, below mobile.css).
    const utilities = document.createElement('style');
    utilities.textContent = '.-m-1 { margin: -4px } .p-1 { padding: 4px } .overflow-hidden { overflow: hidden }';
    document.head.appendChild(utilities);
    const style = document.createElement('style');
    style.textContent = MOBILE_CSS;
    document.head.appendChild(style);
    const coarse = [...style.sheet.cssRules].filter((rule) => rule.media?.mediaText === '(pointer: coarse)');
    expect(coarse).toHaveLength(1);
    const touch = document.createElement('style');
    touch.textContent = [...coarse[0].cssRules].map((rule) => rule.cssText).join('\n');
    document.head.appendChild(touch);
}

const rows = Array.from({ length: 30 }, (_, index) => ({ id: `r${index}`, name: `Row ${index}` }));
const columns = [
    {
        accessorKey: 'name',
        enableSorting: true,
        meta: { label: 'Name', mobile: 'title' },
        cell: ({ row }) => h('span', row.original.name)
    }
];

// The views' compact toolbars: a flex-col of rows, search first, then a filter strip that scrolls itself (Feed).
const stackedToolbar = {
    toolbar: () =>
        h('div', { class: 'flex w-full min-w-0 flex-col gap-2 compact-landscape:flex-row' }, [
            h('div', { class: 'flex min-w-0 items-center gap-2', 'data-testid': 'search-row' }, [h('input')]),
            h('div', { class: '-m-1 flex min-w-0 overflow-x-auto p-1', 'data-testid': 'strip' }, 'Filters'),
            h('button', { class: 'self-start', 'data-testid': 'narrow-row' }, 'Select')
        ])
};

function mountLayout(props = {}, slots = stackedToolbar) {
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

function setFrame({ compact, landscape }) {
    layout.compact.value = compact;
    layout.landscape.value = landscape;
    const root = document.documentElement.classList;
    root.toggle('vrcx-compact', compact);
    root.toggle('vrcx-compact-landscape', landscape);
    root.toggle('vrcx-coarse', true);
}

const style = (element) => getComputedStyle(element instanceof Element ? element : element.element);

describe('mobile.css on the DataTableLayout DOM', () => {
    beforeAll(loadMobileCss);

    beforeEach(() => {
        layout.coarse.value = true;
    });

    afterEach(() => {
        document.body.innerHTML = '';
        document.documentElement.className = '';
    });

    it('portrait: the toolbar is a grid whose first row shares its line with the touch tools', () => {
        setFrame({ compact: true, landscape: false });
        const wrapper = mountLayout({ quickActions: true });
        const toolbar = wrapper.find('[data-slot="data-table-toolbar"]');
        const content = toolbar.find('[data-slot="data-table-toolbar-content"]');

        expect(style(toolbar).display).toBe('grid');
        expect(style(content).display).toBe('contents');
        expect(style(content.element.firstElementChild).display).toBe('contents');
        expect(style(toolbar.find('[data-testid="search-row"]')).gridColumn).toBe('1');
        expect(style(toolbar.find('[data-testid="strip"]')).gridColumn).toBe('1 / -1');
        expect(style(toolbar.find('[data-testid="narrow-row"]')).justifySelf).toBe('start');

        const tools = style(toolbar.find('[data-slot="data-table-toolbar-tools"]'));
        expect(tools.gridColumn).toBe('2');
        expect(tools.gridRow).toBe('1');
        wrapper.unmount();
    });

    it("portrait: a row that scrolls itself spans the card exactly, so its scrollport ends at the list's border", () => {
        setFrame({ compact: true, landscape: false });
        const wrapper = mountLayout();
        const strip = style(wrapper.find('[data-testid="strip"]'));
        for (const side of ['marginLeft', 'marginRight', 'paddingLeft', 'paddingRight']) {
            expect(parseFloat(strip[side])).toBe(0);
        }
        // Its vertical padding still holds the hit areas.
        expect(strip.paddingTop).toBe('4px');
        wrapper.unmount();
    });

    it('landscape: the table is a grid with the pagination right of the toolbar, above the list', () => {
        setFrame({ compact: true, landscape: true });
        const wrapper = mountLayout();
        const root = wrapper.find('.data-table');
        expect(root.attributes('data-pagination')).toBe('inline');
        expect(style(root).display).toBe('grid');

        const toolbar = style(root.find('[data-slot="data-table-toolbar"]'));
        expect([toolbar.gridColumn, toolbar.gridRow]).toEqual(['1', '1']);
        // The portrait grid does not apply: the toolbar stays DataTableLayout's flex row.
        expect(toolbar.display).not.toBe('grid');

        const pagination = style(root.find('.dt-pagination'));
        expect([pagination.gridColumn, pagination.gridRow]).toEqual(['2', '1']);

        const list = style([...root.element.children].find((child) => child.classList.contains('rounded-md')));
        expect([list.gridColumn, list.gridRow]).toEqual(['1 / -1', '2']);
        wrapper.unmount();
    });

    it('gives the table chrome (View options, Quick actions, pagination) a positioned 40px hit area', () => {
        setFrame({ compact: true, landscape: false });
        const wrapper = mountLayout({ quickActions: true });
        const controls = [
            wrapper.find('[data-slot="data-table-view-options"]'),
            wrapper.find('[data-slot="quick-actions-toggle"]'),
            ...wrapper.findAll('.dt-pagination button[data-slot^="pagination-"]')
        ];
        expect(controls.length).toBeGreaterThanOrEqual(4);
        const hitArea = [...document.styleSheets]
            .flatMap((sheet) => [...sheet.cssRules])
            .find(
                (rule) =>
                    rule.selectorText?.includes("[data-slot='data-table-view-options']") &&
                    rule.selectorText.includes('::after')
            );
        expect(hitArea.style.getPropertyValue('inset')).toContain('var(--touch-min)');
        for (const control of controls) {
            expect(control.exists()).toBe(true);
            expect(style(control).position).toBe('relative');
            expect(control.element.matches(hitArea.selectorText.replace(/::after/g, ''))).toBe(true);
        }
        wrapper.unmount();
    });

    it('lets the hit areas reach past a table only when its touch toolbar sits in the corner', () => {
        setFrame({ compact: true, landscape: false });
        const phone = mountLayout();
        const phoneRoot = style(phone.find('.data-table'));
        expect(phoneRoot.overflow).toBe('clip');
        expect(phoneRoot.overflowClipMargin).toBe('4px');
        phone.unmount();

        // Tablet frame without touch tools: the table keeps clipping at its edge, and its PC toolbar scrolls.
        setFrame({ compact: false, landscape: false });
        const tablet = mountLayout();
        expect(tablet.find('[data-slot="data-table-toolbar"]').exists()).toBe(false);
        expect(style(tablet.find('.data-table')).overflow).toBe('hidden');
        expect(tablet.find('[data-slot="data-table-toolbar-scroller"]').classes()).toContain('overflow-x-auto');
        tablet.unmount();
    });
});

describe('mobile.css phone landscape app bar', () => {
    beforeAll(() => {
        if (![...document.styleSheets].length) loadMobileCss();
    });

    afterEach(() => {
        document.body.innerHTML = '';
        document.documentElement.className = '';
    });

    it('draws the 40px app bar buttons at 36px in landscape and keeps a 40px hit area', () => {
        document.body.innerHTML =
            '<header class="vrcx-app-bar"><button class="title">Feed</button><button class="size-10">S</button></header>';
        const [title, search] = document.querySelectorAll('.vrcx-app-bar > button');

        document.documentElement.className = 'vrcx-compact';
        expect(getComputedStyle(search).width).not.toBe('36px');

        document.documentElement.className = 'vrcx-compact vrcx-compact-landscape';
        expect(getComputedStyle(search).width).toBe('36px');
        expect(getComputedStyle(search).height).toBe('36px');
        expect(getComputedStyle(search).position).toBe('relative');
        expect(getComputedStyle(title).width).not.toBe('36px');
        expect(getComputedStyle(document.documentElement).getPropertyValue('--app-bar-h').trim()).toBe('36px');
    });
});

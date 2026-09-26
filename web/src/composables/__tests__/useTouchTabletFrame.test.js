// Portrait touch tablets in the PC frame (docs/DESIGN.md §5): icon nav on first run, and a friends panel of at least
// TABLET_ASIDE_MIN_PX while the page keeps TABLET_PAGE_MIN_PX.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, reactive, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';

const mocks = vi.hoisted(() => ({
    isAndroid: true,
    compact: null,
    coarse: null,
    store: null,
    stored: null,
    configError: false
}));

vi.mock('../../shared/utils/platform', () => ({
    get isAndroid() {
        return mocks.isAndroid;
    }
}));
vi.mock('../useCompactLayout', async () => {
    const { ref: vueRef } = await import('vue');
    mocks.compact = vueRef(false);
    mocks.coarse = vueRef(true);
    return { useCompactLayout: () => ({ isCompact: mocks.compact, isCoarsePointer: mocks.coarse }) };
});
vi.mock('../../stores', () => ({ useAppearanceSettingsStore: () => mocks.store }));
vi.mock('../../services/config', () => ({
    default: {
        getString: vi.fn(async () => {
            if (mocks.configError) throw new Error('SQLiteException: database is locked');
            return mocks.stored;
        })
    }
}));

import {
    isPortraitTouchTablet,
    resolveAsideMinSize,
    shouldStartWithIconNav,
    TABLET_ASIDE_MIN_PX,
    TABLET_PAGE_MIN_PX,
    useTouchTabletFrame
} from '../useTouchTabletFrame';

const BASE = 12;

describe('isPortraitTouchTablet / shouldStartWithIconNav', () => {
    const portraitTablet = { width: 800, height: 1280, coarse: true, compact: false, storedChoice: null };

    it('starts a portrait touch tablet from 768 to 1023px wide with the icon nav', () => {
        expect(shouldStartWithIconNav(portraitTablet)).toBe(true);
        expect(shouldStartWithIconNav({ ...portraitTablet, width: 768, height: 1024 })).toBe(true);
        expect(shouldStartWithIconNav({ ...portraitTablet, width: 1023, height: 1366 })).toBe(true);
    });

    it('respects a choice the user already made, whichever it was', () => {
        expect(shouldStartWithIconNav({ ...portraitTablet, storedChoice: 'false' })).toBe(false);
        expect(shouldStartWithIconNav({ ...portraitTablet, storedChoice: 'true' })).toBe(false);
        expect(isPortraitTouchTablet(portraitTablet)).toBe(true);
    });

    it('leaves mouse devices, landscape, wider tablets and the phone layout alone', () => {
        for (const frame of [
            { ...portraitTablet, coarse: false },
            { ...portraitTablet, width: 1280, height: 800 },
            { ...portraitTablet, width: 1024, height: 768 },
            { ...portraitTablet, width: 1024, height: 1366 },
            { ...portraitTablet, width: 767, height: 1024 },
            { ...portraitTablet, compact: true }
        ]) {
            expect(isPortraitTouchTablet(frame)).toBe(false);
            expect(shouldStartWithIconNav(frame)).toBe(false);
        }
    });
});

describe('resolveAsideMinSize', () => {
    it('keeps the PC minimum until the panel group is known, or when it already gives the panel enough', () => {
        expect(resolveAsideMinSize(0, BASE)).toBe(BASE);
        expect(resolveAsideMinSize(Number.NaN, BASE)).toBe(BASE);
        expect(resolveAsideMinSize(3000, BASE)).toBe(BASE);
    });

    it('gives the friends panel at least TABLET_ASIDE_MIN_PX next to the icon nav, in whole percent', () => {
        // 800px and 768px portrait tablets with the 48px icon nav.
        for (const groupWidth of [752, 720]) {
            const percent = resolveAsideMinSize(groupWidth, BASE);
            expect(Number.isInteger(percent)).toBe(true);
            expect((percent / 100) * groupWidth).toBeGreaterThanOrEqual(TABLET_ASIDE_MIN_PX);
            expect((percent / 100) * groupWidth).toBeLessThan(TABLET_ASIDE_MIN_PX + groupWidth / 100);
            expect(groupWidth * (1 - percent / 100)).toBeGreaterThanOrEqual(TABLET_PAGE_MIN_PX);
        }
        expect(resolveAsideMinSize(752, BASE)).toBe(38);
        expect(resolveAsideMinSize(720, BASE)).toBe(39);
    });

    it('keeps the PC minimum when the page could not keep TABLET_PAGE_MIN_PX (the expanded nav)', () => {
        // 800px and 768px portrait tablets with the 240px nav.
        expect(resolveAsideMinSize(560, BASE)).toBe(BASE);
        expect(resolveAsideMinSize(528, BASE)).toBe(BASE);
        // A 1023px tablet with the expanded nav has room for both.
        const percent = resolveAsideMinSize(783, BASE);
        expect(percent).toBe(36);
        expect(783 * (1 - percent / 100)).toBeGreaterThanOrEqual(TABLET_PAGE_MIN_PX);
    });

    it('rounds to whole percent, so nearby group widths share one splitter constraint (and one saved layout)', () => {
        const values = new Set();
        for (let width = 741; width <= 756; width += 0.25) {
            values.add(resolveAsideMinSize(width, BASE));
        }
        expect([...values]).toEqual([38]);
        for (let width = 700; width <= 1000; width += 7) {
            expect(Number.isInteger(resolveAsideMinSize(width, BASE))).toBe(true);
        }
    });
});

function createStore({ collapsed = false, navWidth = 240 } = {}) {
    const store = reactive({
        isNavCollapsed: ref(collapsed),
        navWidth: ref(navWidth),
        setNavCollapsed: vi.fn(),
        applyNavCollapsedDefault: vi.fn((value) => {
            store.isNavCollapsed = value;
        })
    });
    return store;
}

function mountFrame() {
    let result;
    const Host = defineComponent({
        setup() {
            result = useTouchTabletFrame({ baseMinSize: BASE });
            return () => h('div');
        }
    });
    const wrapper = mount(Host, { attachTo: document.body });
    return { wrapper, get: () => result };
}

function setWindowSize(width, height) {
    window.innerWidth = width;
    window.innerHeight = height;
    window.dispatchEvent(new Event('resize'));
}

describe('useTouchTabletFrame', () => {
    const size = { width: window.innerWidth, height: window.innerHeight };

    beforeEach(() => {
        mocks.isAndroid = true;
        mocks.compact.value = false;
        mocks.coarse.value = true;
        mocks.stored = null;
        mocks.configError = false;
        mocks.store = createStore();
        setWindowSize(800, 1280);
    });

    afterEach(() => {
        setWindowSize(size.width, size.height);
        document.body.innerHTML = '';
    });

    it('starts a portrait touch tablet with the icon nav through the non-saving store action', async () => {
        const { wrapper } = mountFrame();
        await flushPromises();
        expect(mocks.store.isNavCollapsed).toBe(true);
        expect(mocks.store.applyNavCollapsedDefault).toHaveBeenCalledWith(true);
        expect(mocks.store.setNavCollapsed).not.toHaveBeenCalled();
        wrapper.unmount();
    });

    it('puts the default back when the appearance store finishes loading after the layout mounted', async () => {
        const { wrapper } = mountFrame();
        await flushPromises();
        expect(mocks.store.isNavCollapsed).toBe(true);

        // The store's late load writes the saved state: expanded, since nothing was saved.
        mocks.store.isNavCollapsed = false;
        await flushPromises();
        expect(mocks.store.isNavCollapsed).toBe(true);

        // Only once: afterwards the nav is the user's to change.
        mocks.store.isNavCollapsed = false;
        await flushPromises();
        expect(mocks.store.isNavCollapsed).toBe(false);
        wrapper.unmount();
    });

    it("keeps the user's own expand, which is saved", async () => {
        const { wrapper } = mountFrame();
        await flushPromises();
        expect(mocks.store.isNavCollapsed).toBe(true);

        // setNavCollapsed(false) saves the choice along with the state.
        mocks.stored = 'false';
        mocks.store.isNavCollapsed = false;
        await flushPromises();
        expect(mocks.store.isNavCollapsed).toBe(false);
        wrapper.unmount();
    });

    it('keeps the stored choice', async () => {
        mocks.stored = 'false';
        const { wrapper } = mountFrame();
        await flushPromises();
        expect(mocks.store.isNavCollapsed).toBe(false);
        expect(mocks.store.applyNavCollapsedDefault).not.toHaveBeenCalled();
        wrapper.unmount();
    });

    it('does nothing when the config cannot be read', async () => {
        mocks.configError = true;
        const { wrapper } = mountFrame();
        await flushPromises();
        expect(mocks.store.isNavCollapsed).toBe(false);
        wrapper.unmount();
    });

    it('does nothing on a mouse or in landscape', async () => {
        mocks.coarse.value = false;
        const mouse = mountFrame();
        await flushPromises();
        expect(mocks.store.isNavCollapsed).toBe(false);
        mouse.wrapper.unmount();

        mocks.coarse.value = true;
        setWindowSize(1280, 800);
        const landscape = mountFrame();
        await flushPromises();
        expect(mocks.store.isNavCollapsed).toBe(false);
        landscape.wrapper.unmount();
    });

    it('raises the friends panel minimum next to the icon nav, and keeps the PC minimum next to the expanded nav', async () => {
        mocks.stored = 'true';
        mocks.store = createStore({ collapsed: true });
        const { wrapper, get } = mountFrame();
        await flushPromises();
        expect(get().asideMinSize.value).toBe(38);

        // The user expands the nav: 560px left for the page and the panel, too little for both floors.
        mocks.store.isNavCollapsed = false;
        await nextTick();
        expect(get().asideMinSize.value).toBe(BASE);

        // 768 x 1024: 528px next to the expanded nav, 720px next to the icon nav.
        setWindowSize(768, 1024);
        await nextTick();
        expect(get().asideMinSize.value).toBe(BASE);
        mocks.store.isNavCollapsed = true;
        await nextTick();
        expect(get().asideMinSize.value).toBe(39);
        wrapper.unmount();
    });

    it('keeps the PC minimum in landscape (1024 x 768 and 1280 x 800), on mouse devices and in the phone layout', async () => {
        mocks.store = createStore({ collapsed: true });
        const { wrapper, get } = mountFrame();
        await flushPromises();
        expect(get().asideMinSize.value).toBe(38);

        for (const [width, height] of [
            [1024, 768],
            [1280, 800]
        ]) {
            setWindowSize(width, height);
            await nextTick();
            expect(get().asideMinSize.value).toBe(BASE);
        }

        setWindowSize(800, 1280);
        mocks.coarse.value = false;
        await nextTick();
        expect(get().asideMinSize.value).toBe(BASE);

        mocks.coarse.value = true;
        mocks.compact.value = true;
        await nextTick();
        expect(get().asideMinSize.value).toBe(BASE);
        wrapper.unmount();
    });

    it('desktop builds keep the PC minimum and never touch the nav', async () => {
        mocks.isAndroid = false;
        const { wrapper, get } = mountFrame();
        await flushPromises();
        expect(get().asideMinSize.value).toBe(BASE);
        expect(mocks.store.isNavCollapsed).toBe(false);
        expect(mocks.store.applyNavCollapsedDefault).not.toHaveBeenCalled();
        wrapper.unmount();
    });
});

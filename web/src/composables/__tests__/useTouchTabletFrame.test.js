// Touch tablets in the PC frame (docs/DESIGN.md §5): icon nav on first run in portrait, and a friends panel no
// narrower than TABLET_ASIDE_MIN_PX.
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
    resolveAsideMinSize,
    shouldStartWithIconNav,
    TABLET_ASIDE_MAX_MIN_PERCENT,
    TABLET_ASIDE_MIN_PX,
    useTouchTabletFrame
} from '../useTouchTabletFrame';

describe('shouldStartWithIconNav', () => {
    const portraitTablet = { width: 800, height: 1280, coarse: true, compact: false, storedChoice: null };

    it('starts a portrait touch tablet from 768 to 1023px wide with the icon nav', () => {
        expect(shouldStartWithIconNav(portraitTablet)).toBe(true);
        expect(shouldStartWithIconNav({ ...portraitTablet, width: 768, height: 1024 })).toBe(true);
        expect(shouldStartWithIconNav({ ...portraitTablet, width: 1023, height: 1366 })).toBe(true);
    });

    it('respects a choice the user already made, whichever it was', () => {
        expect(shouldStartWithIconNav({ ...portraitTablet, storedChoice: 'false' })).toBe(false);
        expect(shouldStartWithIconNav({ ...portraitTablet, storedChoice: 'true' })).toBe(false);
    });

    it('leaves mouse devices, landscape, wider tablets and the phone layout alone', () => {
        expect(shouldStartWithIconNav({ ...portraitTablet, coarse: false })).toBe(false);
        expect(shouldStartWithIconNav({ ...portraitTablet, width: 1280, height: 800 })).toBe(false);
        expect(shouldStartWithIconNav({ ...portraitTablet, width: 1024, height: 1366 })).toBe(false);
        expect(shouldStartWithIconNav({ ...portraitTablet, width: 767, height: 1024 })).toBe(false);
        expect(shouldStartWithIconNav({ ...portraitTablet, compact: true })).toBe(false);
    });
});

describe('resolveAsideMinSize', () => {
    it('keeps the PC minimum until the panel group is known or wide enough', () => {
        expect(resolveAsideMinSize(0, 12)).toBe(12);
        expect(resolveAsideMinSize(Number.NaN, 12)).toBe(12);
        expect(resolveAsideMinSize(3000, 12)).toBe(12);
    });

    it('raises the minimum to TABLET_ASIDE_MIN_PX on narrower groups', () => {
        const percent = resolveAsideMinSize(752, 12);
        expect((percent / 100) * 752).toBeCloseTo(TABLET_ASIDE_MIN_PX, 0);
    });

    it('never lets the friends panel minimum take more than half of the space', () => {
        expect(resolveAsideMinSize(400, 12)).toBe(TABLET_ASIDE_MAX_MIN_PERCENT);
    });
});

function mountFrame({ groupWidth = 752 } = {}) {
    let result;
    const Host = defineComponent({
        setup() {
            const groupRef = ref(null);
            result = useTouchTabletFrame({ groupRef, baseMinSize: 12 });
            return () => h('div', { ref: groupRef, style: `width:${groupWidth}px` });
        }
    });
    const wrapper = mount(Host, { attachTo: document.body });
    return { wrapper, get: () => result };
}

describe('useTouchTabletFrame', () => {
    const size = { width: window.innerWidth, height: window.innerHeight };

    beforeEach(() => {
        mocks.isAndroid = true;
        mocks.compact.value = false;
        mocks.coarse.value = true;
        mocks.stored = null;
        mocks.configError = false;
        mocks.store = reactive({ isNavCollapsed: false });
        window.innerWidth = 800;
        window.innerHeight = 1280;
    });

    afterEach(() => {
        window.innerWidth = size.width;
        window.innerHeight = size.height;
        document.body.innerHTML = '';
    });

    it('starts a portrait touch tablet with the icon nav, without saving it as the user choice', async () => {
        const { wrapper } = mountFrame();
        await flushPromises();
        expect(mocks.store.isNavCollapsed).toBe(true);
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
        window.innerWidth = 1280;
        window.innerHeight = 800;
        const landscape = mountFrame();
        await flushPromises();
        expect(mocks.store.isNavCollapsed).toBe(false);
        landscape.wrapper.unmount();
    });

    it('uses the PC minimum for the friends panel on mouse devices and in the phone layout', async () => {
        const { wrapper, get } = mountFrame();
        await nextTick();
        mocks.coarse.value = false;
        await nextTick();
        expect(get().asideMinSize.value).toBe(12);
        mocks.coarse.value = true;
        mocks.compact.value = true;
        await nextTick();
        expect(get().asideMinSize.value).toBe(12);
        wrapper.unmount();
    });

    it('desktop builds keep the PC minimum and never touch the nav', async () => {
        mocks.isAndroid = false;
        const { wrapper, get } = mountFrame();
        await flushPromises();
        expect(get().asideMinSize.value).toBe(12);
        expect(mocks.store.isNavCollapsed).toBe(false);
        wrapper.unmount();
    });
});

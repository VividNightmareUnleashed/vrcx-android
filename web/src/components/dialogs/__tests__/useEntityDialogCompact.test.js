import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, ref } from 'vue';
import { mount } from '@vue/test-utils';

const compact = vi.hoisted(() => ({ isCompact: null }));

vi.mock('../../../composables/useCompactLayout', () => ({
    useCompactLayout: () => ({ isCompact: compact.isCompact })
}));

import {
    ENTITY_CARD_COMPACT_CLASS,
    ENTITY_PANE_COMPACT_CLASS,
    ENTITY_RAIL_COMPACT_CLASS,
    ENTITY_ROOT_COMPACT_CLASS,
    ENTITY_TABS_COMPACT_CLASS,
    MENU_CONTENT_TOUCH_CLASS,
    findDialogScroller,
    getPaneScrollTarget,
    useEntityDialogCompact
} from '../useEntityDialogCompact';

describe('entity dialog compact classes', () => {
    it('only use the phone and touch variants, so the PC layout is untouched', () => {
        const classes = [
            ENTITY_ROOT_COMPACT_CLASS,
            ENTITY_RAIL_COMPACT_CLASS,
            ENTITY_PANE_COMPACT_CLASS,
            ENTITY_TABS_COMPACT_CLASS,
            ENTITY_CARD_COMPACT_CLASS,
            MENU_CONTENT_TOUCH_CLASS
        ]
            .join(' ')
            .split(/\s+/)
            .filter(Boolean);

        for (const name of classes) {
            expect(name).toMatch(/^(compact:|compact-landscape:|pointer-coarse:)/);
        }
    });

    it('make the tab strip sticky with an opaque base under the profile-card tint', () => {
        expect(ENTITY_TABS_COMPACT_CLASS).toContain('compact:[&>div:first-child]:sticky');
        expect(ENTITY_TABS_COMPACT_CLASS).toContain('compact:[&>div:first-child]:bg-background');
        expect(ENTITY_TABS_COMPACT_CLASS).toContain('linear-gradient(var(--profile-card),var(--profile-card))');
    });
});

describe('getPaneScrollTarget', () => {
    it('leaves the scroller alone while the pane is still below its top edge', () => {
        expect(getPaneScrollTarget({ top: 100 }, { top: 300 }, 0)).toBeNull();
        expect(getPaneScrollTarget({ top: 100 }, { top: 100 }, 40)).toBeNull();
    });

    it('scrolls back to the start of the pane once the pane has scrolled past the top', () => {
        // The pane starts 250px above the scroller's top edge while the scroller is at 900.
        expect(getPaneScrollTarget({ top: 100 }, { top: -150 }, 900)).toBe(650);
    });

    it('never returns a negative offset', () => {
        expect(getPaneScrollTarget({ top: 100 }, { top: -500 }, 100)).toBe(0);
    });
});

describe('findDialogScroller', () => {
    it('finds the compact dialog scroller around an element', () => {
        const scroller = document.createElement('div');
        scroller.setAttribute('data-slot', 'main-dialog-scroller');
        const inner = document.createElement('div');
        scroller.appendChild(inner);

        expect(findDialogScroller(inner)).toBe(scroller);
        expect(findDialogScroller(document.createElement('div'))).toBeNull();
        expect(findDialogScroller(null)).toBeNull();
    });
});

describe('useEntityDialogCompact', () => {
    let scroller;
    let pane;

    function mountHarness(state) {
        const Harness = defineComponent({
            setup() {
                const paneRef = ref(pane);
                useEntityDialogCompact({
                    paneRef,
                    entityId: () => state.value.id,
                    activeTab: () => state.value.tab
                });
                return () => h('div');
            }
        });
        return mount(Harness);
    }

    beforeEach(() => {
        compact.isCompact = ref(true);
        scroller = document.createElement('div');
        scroller.setAttribute('data-slot', 'main-dialog-scroller');
        pane = document.createElement('div');
        scroller.appendChild(pane);
        document.body.appendChild(scroller);
        scroller.getBoundingClientRect = () => ({ top: 96 });
        // jsdom has no layout, so scrollTop is a plain value here.
        let scrollTop = 0;
        Object.defineProperty(scroller, 'scrollTop', {
            configurable: true,
            get: () => scrollTop,
            set: (value) => {
                scrollTop = value;
            }
        });
    });

    afterEach(() => {
        scroller.remove();
    });

    it('returns to the top of the page when another entity opens', async () => {
        const state = ref({ id: 'usr_1', tab: 'Info' });
        mountHarness(state);
        scroller.scrollTop = 500;

        state.value = { id: 'usr_2', tab: 'Info' };
        await nextTick();
        await nextTick();

        expect(scroller.scrollTop).toBe(0);
    });

    it('shows a new tab from its start when the tab strip is stuck', async () => {
        const state = ref({ id: 'usr_1', tab: 'Info' });
        mountHarness(state);
        scroller.scrollTop = 1200;
        pane.getBoundingClientRect = () => ({ top: -404 });

        state.value = { id: 'usr_1', tab: 'Groups' };
        await nextTick();
        await nextTick();

        expect(scroller.scrollTop).toBe(700);
    });

    it('keeps the scroll position when the tab strip is not stuck yet', async () => {
        const state = ref({ id: 'usr_1', tab: 'Info' });
        mountHarness(state);
        scroller.scrollTop = 120;
        pane.getBoundingClientRect = () => ({ top: 400 });

        state.value = { id: 'usr_1', tab: 'Groups' };
        await nextTick();
        await nextTick();

        expect(scroller.scrollTop).toBe(120);
    });

    it('does nothing in the PC layout', async () => {
        compact.isCompact = ref(false);
        const state = ref({ id: 'usr_1', tab: 'Info' });
        mountHarness(state);
        scroller.scrollTop = 500;
        pane.getBoundingClientRect = () => ({ top: -404 });

        state.value = { id: 'usr_2', tab: 'Groups' };
        await nextTick();
        await nextTick();

        expect(scroller.scrollTop).toBe(500);
    });
});

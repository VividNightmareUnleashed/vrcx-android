import { afterEach, describe, expect, it } from 'vitest';
import { nextTick } from 'vue';

import {
    COARSE_POINTER_QUERY,
    COMPACT_LANDSCAPE_QUERY,
    COMPACT_QUERY,
    createCompactLayout,
    useCompactLayout
} from '../useCompactLayout';

/**
 * A window stand-in whose matchMedia answers the three shell queries from a simulated screen.
 *
 * @param {{ width: number; height: number; pointer?: string }} initial
 */
function createFakeWindow(initial) {
    let screen = { pointer: 'fine', ...initial };
    const lists = [];
    const evaluate = (query) => {
        switch (query) {
            case COMPACT_QUERY:
                return screen.width <= 767 || screen.height <= 500;
            case COMPACT_LANDSCAPE_QUERY:
                return screen.height <= 500 && screen.width > screen.height;
            case COARSE_POINTER_QUERY:
                return screen.pointer === 'coarse';
            default:
                throw new Error(`unexpected media query ${query}`);
        }
    };
    const win = {
        matchMedia(query) {
            const listeners = new Set();
            const list = {
                media: query,
                matches: evaluate(query),
                addEventListener: (type, fn) => type === 'change' && listeners.add(fn),
                removeEventListener: (type, fn) => listeners.delete(fn),
                listeners
            };
            lists.push(list);
            return list;
        }
    };
    return {
        win,
        lists,
        resize(next) {
            screen = { ...screen, ...next };
            for (const list of lists) {
                const matches = evaluate(list.media);
                if (matches !== list.matches) {
                    list.matches = matches;
                    list.listeners.forEach((fn) => fn({ matches, media: list.media }));
                }
            }
        }
    };
}

describe('useCompactLayout', () => {
    let layout = null;

    afterEach(() => {
        layout?.stop();
        layout = null;
    });

    it('stays off and never queries the media when disabled (desktop builds)', () => {
        const fake = createFakeWindow({ width: 360, height: 780 });
        const root = document.createElement('html');
        layout = createCompactLayout({ enabled: false, window: fake.win, root });

        expect(layout.isCompact.value).toBe(false);
        expect(layout.isCompactLandscape.value).toBe(false);
        expect(layout.isCoarsePointer.value).toBe(false);
        expect(fake.lists).toHaveLength(0);
        expect(root.className).toBe('');
    });

    it('is compact on a portrait phone and toggles html.vrcx-compact', () => {
        const fake = createFakeWindow({ width: 360, height: 780, pointer: 'coarse' });
        const root = document.createElement('html');
        layout = createCompactLayout({ enabled: true, window: fake.win, root });

        expect(layout.isCompact.value).toBe(true);
        expect(layout.isCompactLandscape.value).toBe(false);
        expect(layout.isCoarsePointer.value).toBe(true);
        expect(root.classList.contains('vrcx-compact')).toBe(true);
        expect(root.classList.contains('vrcx-compact-landscape')).toBe(false);
        expect(root.classList.contains('vrcx-coarse')).toBe(true);
    });

    it('flags phone landscape (height <= 500) as compact landscape', async () => {
        const fake = createFakeWindow({ width: 360, height: 780 });
        const root = document.createElement('html');
        layout = createCompactLayout({ enabled: true, window: fake.win, root });
        await nextTick(); // vueuse attaches the change listeners after a tick

        fake.resize({ width: 780, height: 360 });
        await nextTick();

        expect(layout.isCompact.value).toBe(true);
        expect(layout.isCompactLandscape.value).toBe(true);
        expect(root.classList.contains('vrcx-compact-landscape')).toBe(true);
    });

    it('keeps tablets on the desktop frame and follows resizes', async () => {
        const fake = createFakeWindow({ width: 800, height: 1280, pointer: 'coarse' });
        const root = document.createElement('html');
        layout = createCompactLayout({ enabled: true, window: fake.win, root });

        expect(layout.isCompact.value).toBe(false);
        expect(root.classList.contains('vrcx-compact')).toBe(false);
        expect(layout.isCoarsePointer.value).toBe(true);
        await nextTick();

        // Split screen makes the window phone-sized.
        fake.resize({ width: 400 });
        await nextTick();
        expect(layout.isCompact.value).toBe(true);
        expect(root.classList.contains('vrcx-compact')).toBe(true);

        fake.resize({ width: 800 });
        await nextTick();
        expect(layout.isCompact.value).toBe(false);
        expect(root.classList.contains('vrcx-compact')).toBe(false);
    });

    it('creates one media query listener per query and removes the classes on stop', () => {
        const fake = createFakeWindow({ width: 360, height: 780 });
        const root = document.createElement('html');
        layout = createCompactLayout({ enabled: true, window: fake.win, root });

        expect(fake.lists.map((list) => list.media).sort()).toEqual(
            [COMPACT_QUERY, COMPACT_LANDSCAPE_QUERY, COARSE_POINTER_QUERY].sort()
        );
        layout.stop();
        expect(root.classList.contains('vrcx-compact')).toBe(false);
        layout = null;
    });

    it('returns one shared instance, inert in the desktop/test build', () => {
        const first = useCompactLayout();
        const second = useCompactLayout();
        expect(second).toBe(first);
        expect(first.isCompact.value).toBe(false);
        expect(document.documentElement.classList.contains('vrcx-compact')).toBe(false);
    });
});

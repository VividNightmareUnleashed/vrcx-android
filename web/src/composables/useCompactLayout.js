// Phone ("compact") layout detection for the Android build (docs/DESIGN.md §2, docs/ARCHITECTURE.md §7).
//
// One shared set of media queries decides whether the phone shell is used. Every consumer reads the same refs, so
// there is exactly one listener per query for the whole app. Desktop builds never create the listeners: all flags
// stay false and <html> is never touched.
import { effectScope, readonly, ref, watch } from 'vue';
import { useMediaQuery } from '@vueuse/core';

import { isAndroid } from '../shared/utils/platform';

/** Phones: narrow portrait windows, or any window that is too short (phone landscape). Tablets keep the PC frame. */
export const COMPACT_QUERY = '(max-width: 767px), (max-height: 500px)';
/** Phone landscape: the dock becomes a rail (DESIGN.md §2.4). Only meaningful while compact. */
export const COMPACT_LANDSCAPE_QUERY = '(max-height: 500px) and (orientation: landscape)';
/** Touch-first devices: hover-free affordances, no column resize/reorder handles. */
export const COARSE_POINTER_QUERY = '(pointer: coarse)';

export const COMPACT_CLASS = 'vrcx-compact';
export const COMPACT_LANDSCAPE_CLASS = 'vrcx-compact-landscape';
export const COARSE_POINTER_CLASS = 'vrcx-coarse';

/**
 * Creates the compact-layout state. Exported for tests; the app uses the shared instance from useCompactLayout().
 *
 * @param {object} [options]
 * @param {boolean} [options.enabled] False keeps every flag false and never creates listeners (desktop builds)
 * @param {Window} [options.window] Window providing matchMedia (vueuse option, for tests)
 * @param {HTMLElement | null} [options.root] Element that receives the classes (default: document.documentElement)
 * @returns {{
 *     isCompact: import('vue').Ref<boolean>;
 *     isCompactLandscape: import('vue').Ref<boolean>;
 *     isCoarsePointer: import('vue').Ref<boolean>;
 *     stop: () => void;
 * }}
 */
export function createCompactLayout({ enabled = isAndroid, window: win, root } = {}) {
    const isCompact = ref(false);
    const isCompactLandscape = ref(false);
    const isCoarsePointer = ref(false);

    if (!enabled) {
        return {
            isCompact: readonly(isCompact),
            isCompactLandscape: readonly(isCompactLandscape),
            isCoarsePointer: readonly(isCoarsePointer),
            stop() {}
        };
    }

    const scope = effectScope(true);
    const target = root === undefined ? (typeof document !== 'undefined' ? document.documentElement : null) : root;

    scope.run(() => {
        const options = win ? { window: win } : undefined;
        const compactMatch = useMediaQuery(COMPACT_QUERY, options);
        const landscapeMatch = useMediaQuery(COMPACT_LANDSCAPE_QUERY, options);
        const coarseMatch = useMediaQuery(COARSE_POINTER_QUERY, options);

        watch(
            [compactMatch, landscapeMatch, coarseMatch],
            ([compact, landscape, coarse]) => {
                isCompact.value = Boolean(compact);
                isCompactLandscape.value = Boolean(compact && landscape);
                isCoarsePointer.value = Boolean(coarse);
                if (target) {
                    target.classList.toggle(COMPACT_CLASS, isCompact.value);
                    target.classList.toggle(COMPACT_LANDSCAPE_CLASS, isCompactLandscape.value);
                    target.classList.toggle(COARSE_POINTER_CLASS, isCoarsePointer.value);
                }
            },
            { immediate: true, flush: 'sync' }
        );
    });

    return {
        isCompact: readonly(isCompact),
        isCompactLandscape: readonly(isCompactLandscape),
        isCoarsePointer: readonly(isCoarsePointer),
        stop() {
            scope.stop();
            if (target) {
                target.classList.remove(COMPACT_CLASS, COMPACT_LANDSCAPE_CLASS, COARSE_POINTER_CLASS);
            }
        }
    };
}

let shared = null;

/**
 * Shared compact-layout flags. Safe to call from any component or store; the first call creates the listeners
 * (on Android only) and keeps `<html class="vrcx-compact">` in sync.
 *
 * @returns {{
 *     isCompact: import('vue').Ref<boolean>;
 *     isCompactLandscape: import('vue').Ref<boolean>;
 *     isCoarsePointer: import('vue').Ref<boolean>;
 * }}
 */
export function useCompactLayout() {
    if (!shared) {
        shared = createCompactLayout();
    }
    return shared;
}

/** Test helper: drops the shared instance. */
export function resetCompactLayoutForTests() {
    shared?.stop();
    shared = null;
}

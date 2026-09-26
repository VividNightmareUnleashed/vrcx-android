// Touch tablets in the PC frame (docs/DESIGN.md §5). Portrait tablets are too narrow for the PC's 240px nav, the page
// and the friends panel side by side: on first run the nav starts as the PC's collapsed icon nav, and the friends panel
// never gets narrower than a small PC window gives it. Android only; desktop builds keep upstream behaviour.
import { computed, onBeforeUnmount, onMounted, toRef, watch } from 'vue';
import { useElementSize } from '@vueuse/core';

import { isAndroid } from '../shared/utils/platform';
import { useAppearanceSettingsStore } from '../stores';
import { useCompactLayout } from './useCompactLayout';

import configRepository from '../services/config';

/** Config key of the PC nav's collapsed state (stores/settings/appearance.js). Absent until the user chooses. */
export const NAV_COLLAPSED_CONFIG_KEY = 'VRCX_navIsCollapsed';

/** Narrowest friends panel on touch tablets: what the PC frame gives it on a 1366px laptop. */
export const TABLET_ASIDE_MIN_PX = 280;

/** The friends panel minimum never takes more than this share of the space next to the nav. */
export const TABLET_ASIDE_MAX_MIN_PERCENT = 50;

/**
 * Whether the PC frame should start with the icon nav: a touch tablet in portrait, 768 to 1023px wide, whose user
 * has not chosen a nav state yet.
 *
 * @param {{ width: number; height: number; coarse: boolean; compact: boolean; storedChoice: string | null }} state
 * @returns {boolean}
 */
export function shouldStartWithIconNav({ width, height, coarse, compact, storedChoice }) {
    if (storedChoice !== null && storedChoice !== undefined) return false;
    if (!coarse || compact) return false;
    return width >= 768 && width <= 1023 && height > width;
}

/**
 * Minimum size of the friends panel, in percent of the panel group, so it is at least `minPx` wide.
 *
 * @param {number} groupWidth Width of the panel group (page + friends panel) in CSS px
 * @param {number} basePercent The PC minimum in percent
 * @param {number} [minPx]
 * @returns {number}
 */
export function resolveAsideMinSize(groupWidth, basePercent, minPx = TABLET_ASIDE_MIN_PX) {
    if (!Number.isFinite(groupWidth) || groupWidth <= 0) return basePercent;
    const percent = Math.min((minPx / groupWidth) * 100, TABLET_ASIDE_MAX_MIN_PERCENT);
    return Math.max(basePercent, Math.round(percent * 100) / 100);
}

/**
 * @param {object} options
 * @param {import('vue').Ref} options.groupRef The friends panel's ResizablePanelGroup (component or element)
 * @param {number} options.baseMinSize The PC minimum of the friends panel, in percent
 * @returns {{ asideMinSize: import('vue').ComputedRef<number> | import('vue').Ref<number> }}
 */
export function useTouchTabletFrame({ groupRef, baseMinSize }) {
    if (!isAndroid) {
        return { asideMinSize: computed(() => baseMinSize) };
    }

    const { isCompact, isCoarsePointer } = useCompactLayout();
    // The store's own action (setNavCollapsed) saves the state as the user's choice; this default must not be saved.
    const navCollapsed = toRef(useAppearanceSettingsStore(), 'isNavCollapsed');
    const { width: groupWidth } = useElementSize(groupRef);

    const asideMinSize = computed(() =>
        isCoarsePointer.value && !isCompact.value ? resolveAsideMinSize(groupWidth.value, baseMinSize) : baseMinSize
    );

    // The choice the user saved, `null` when there is none, `undefined` when the config cannot be read.
    const readStoredChoice = () => configRepository.getString(NAV_COLLAPSED_CONFIG_KEY).catch(() => undefined);

    let unmounted = false;
    let stopGuard = null;
    onBeforeUnmount(() => {
        unmounted = true;
        stopGuard?.();
    });

    onMounted(async () => {
        const storedChoice = await readStoredChoice();
        const start = shouldStartWithIconNav({
            width: window.innerWidth,
            height: window.innerHeight,
            coarse: isCoarsePointer.value,
            compact: isCompact.value,
            storedChoice: storedChoice === undefined ? 'unknown' : storedChoice
        });
        if (!start || unmounted) return;
        // Not saved: the state the user picks later (collapse button, nav toggle) is what gets saved.
        navCollapsed.value = true;
        // The appearance store may still be loading its settings; it then writes the saved state (expanded when none
        // was saved) over this default. Put the default back once, unless that change was the user's own, saved choice.
        stopGuard = watch(navCollapsed, async (collapsed) => {
            if (collapsed) return;
            stopGuard?.();
            stopGuard = null;
            if ((await readStoredChoice()) === null) {
                navCollapsed.value = true;
            }
        });
    });

    return { asideMinSize };
}

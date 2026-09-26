// Touch tablets in the PC frame (docs/DESIGN.md §5). A portrait touch tablet (768 to 1023px wide) is too narrow for the
// PC's 240px nav, the page and the friends panel side by side: on first run the nav starts as the PC's collapsed icon
// nav, and the friends panel gets a usable minimum width as long as the page keeps its own. Android only; desktop
// builds, mouse devices, landscape tablets and the phone layout keep upstream behaviour.
import { computed, onBeforeUnmount, onMounted, watch } from 'vue';
import { storeToRefs } from 'pinia';
import { useWindowSize } from '@vueuse/core';

import { isAndroid } from '../shared/utils/platform';
import { useAppearanceSettingsStore } from '../stores';
import { useCompactLayout } from './useCompactLayout';

import configRepository from '../services/config';

/** Config key of the PC nav's collapsed state (stores/settings/appearance.js). Absent until the user chooses. */
export const NAV_COLLAPSED_CONFIG_KEY = 'VRCX_navIsCollapsed';

/** Width of the collapsed icon nav (MainLayout's SidebarProvider `width-icon`). */
export const NAV_ICON_WIDTH_PX = 48;

/** Narrowest friends panel on portrait touch tablets: what the PC frame gives it on a 1366px laptop. */
export const TABLET_ASIDE_MIN_PX = 280;

/** The page keeps at least this much next to that friends panel; below it the PC minimum applies. */
export const TABLET_PAGE_MIN_PX = 420;

/**
 * Whether the window is a touch tablet in portrait that shows the PC frame, 768 to 1023px wide.
 *
 * @param {{ width: number; height: number; coarse: boolean; compact: boolean }} frame
 * @returns {boolean}
 */
export function isPortraitTouchTablet({ width, height, coarse, compact }) {
    if (!coarse || compact) return false;
    return width >= 768 && width <= 1023 && height > width;
}

/**
 * Whether the PC frame should start with the icon nav: a portrait touch tablet whose user has not chosen a nav state.
 *
 * @param {{ width: number; height: number; coarse: boolean; compact: boolean; storedChoice: string | null }} state
 * @returns {boolean}
 */
export function shouldStartWithIconNav({ storedChoice, ...frame }) {
    if (storedChoice !== null && storedChoice !== undefined) return false;
    return isPortraitTouchTablet(frame);
}

/**
 * Minimum size of the friends panel, in percent of the panel group (page + friends panel): at least `asideMinPx` wide
 * as long as the page keeps `pageMinPx`, otherwise the PC minimum. Rounded up to a whole percent, so the splitter's
 * saved layouts (reka keys them by the panel constraints) stay one per frame state.
 *
 * @param {number} groupWidth Width of the panel group in CSS px
 * @param {number} basePercent The PC minimum in percent
 * @param {{ asideMinPx?: number; pageMinPx?: number }} [options]
 * @returns {number}
 */
export function resolveAsideMinSize(
    groupWidth,
    basePercent,
    { asideMinPx = TABLET_ASIDE_MIN_PX, pageMinPx = TABLET_PAGE_MIN_PX } = {}
) {
    if (!Number.isFinite(groupWidth) || groupWidth <= 0) return basePercent;
    const percent = Math.ceil((asideMinPx / groupWidth) * 100);
    if (percent <= basePercent) return basePercent;
    // Both floors cannot be met (the expanded nav on a small tablet): the page keeps its PC share.
    if (groupWidth * (1 - percent / 100) < pageMinPx) return basePercent;
    return percent;
}

/**
 * @param {object} options
 * @param {number} options.baseMinSize The PC minimum of the friends panel, in percent
 * @returns {{ asideMinSize: import('vue').ComputedRef<number> }}
 */
export function useTouchTabletFrame({ baseMinSize }) {
    if (!isAndroid) {
        return { asideMinSize: computed(() => baseMinSize) };
    }

    const { isCompact, isCoarsePointer } = useCompactLayout();
    const appearanceSettingsStore = useAppearanceSettingsStore();
    const { isNavCollapsed, navWidth } = storeToRefs(appearanceSettingsStore);
    const { width, height } = useWindowSize();

    const isTablet = computed(() =>
        isPortraitTouchTablet({
            width: width.value,
            height: height.value,
            coarse: isCoarsePointer.value,
            compact: isCompact.value
        })
    );

    // The group width follows from the window and the nav state, not from a measurement: the nav animates its width,
    // and every intermediate value would be a new splitter constraint (and a new saved layout).
    const asideMinSize = computed(() => {
        if (!isTablet.value) return baseMinSize;
        const navPx = isNavCollapsed.value ? NAV_ICON_WIDTH_PX : navWidth.value;
        return resolveAsideMinSize(width.value - navPx, baseMinSize);
    });

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
        appearanceSettingsStore.applyNavCollapsedDefault(true);
        // The appearance store may still be loading its settings; it then writes the saved state (expanded when none
        // was saved) over this default. Put the default back once, unless that change was the user's own, saved choice.
        stopGuard = watch(isNavCollapsed, async (collapsed) => {
            if (collapsed) return;
            stopGuard?.();
            stopGuard = null;
            if (!unmounted && (await readStoredChoice()) === null) {
                appearanceSettingsStore.applyNavCollapsedDefault(true);
            }
        });
    });

    return { asideMinSize };
}

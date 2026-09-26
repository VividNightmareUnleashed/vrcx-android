// Small navigation helpers used by the phone shell.
import { COMPANION_SETTINGS_ROUTE } from '../companionStore.js';

/**
 * Opens Settings on the PC companion tab (Settings.vue selects the tab from `?tab=`).
 *
 * @param {import('vue-router').Router} router
 */
export function openCompanionSettings(router) {
    return router.push({ ...COMPANION_SETTINGS_ROUTE, query: { ...COMPANION_SETTINGS_ROUTE.query } });
}

const SCROLLER_SELECTOR = '.x-container, [data-slot="scroll-area-viewport"], .overflow-auto, .overflow-y-auto';

/**
 * Scrolls every scrolled container inside `root` back to the top (the app bar title tap, DESIGN.md §2.1).
 * Only runs on a tap, so reading scroll positions here is fine.
 *
 * @param {Element | null} root
 * @param {ScrollBehavior} [behavior]
 * @returns {number} Number of containers scrolled
 */
export function scrollPageToTop(root, behavior = 'smooth') {
    if (!root) return 0;
    let count = 0;
    const candidates = [root, ...root.querySelectorAll(SCROLLER_SELECTOR)];
    for (const el of candidates) {
        if (el.scrollTop > 0 && el.getClientRects().length > 0) {
            el.scrollTo({ top: 0, behavior });
            count++;
        }
    }
    return count;
}

// Phone layout of the entity dialogs (User, World, Avatar, Group) in the compact shell (docs/DESIGN.md §3.2).
//
// On PC each dialog is a 308px rail next to a tab pane, and both scroll on their own. In compact layout
// MainDialogContainer hosts the dialog in one vertical scroller, so the rail becomes the page header, the tab strip
// sticks to the top of that scroller, and the tab content flows below it. Everything here is inert on desktop:
// `isCompact` is always false there and the classes use the `compact:` variant.
import { nextTick, onMounted, watch } from 'vue';

import { useCompactLayout } from '../../composables/useCompactLayout';

/** The dialog root: one column that grows with its content, so the sticky tab strip can travel down the page. */
export const ENTITY_ROOT_COMPACT_CLASS = 'compact:flex-none compact:flex-col';

/** The left rail (profile card): full width, no scroller of its own. */
export const ENTITY_RAIL_COMPACT_CLASS = 'compact:w-full compact:pr-0 compact:overflow-visible';

/**
 * The tab pane: no scroller of its own, and at least one screen tall below the dialog app bar, so the tab strip can
 * always reach the top (and stays there when a short tab replaces a long one).
 */
export const ENTITY_PANE_COMPACT_CLASS =
    'compact:mt-2 compact:pl-0 compact:flex-none compact:min-h-[calc(100dvh-60px-var(--safe-top,0px)-var(--vrcx-bottom-inset,0px))]';

/**
 * TabsUnderline root in compact layout: its header (the first child) sticks to the top of the dialog scroller.
 * The PC header is a translucent `--profile-card` card; a sticky strip needs an opaque base under that tint so the
 * content scrolling behind it does not show through (TabsUnderline's own `sticky` prop would put `bg-background` and
 * `bg-(--profile-card)` on the same element, and one of them loses).
 */
export const ENTITY_TABS_COMPACT_CLASS = [
    'compact:[&>div:first-child]:sticky',
    // Sticky offsets count from the scroller's padding box edge minus its padding (MainDialogContainer's p-3), so
    // -12px puts the strip flush under the dialog app bar instead of leaving a 12px slot for content to show through.
    'compact:[&>div:first-child]:-top-3',
    // Above the rail's own overlays (the user card's avatar and moderation icons are z-20/z-30) scrolling under it.
    'compact:[&>div:first-child]:z-40',
    'compact:[&>div:first-child]:bg-background',
    'compact:[&>div:first-child]:bg-[image:linear-gradient(var(--profile-card),var(--profile-card))]'
].join(' ');

/** Entity cards (users, worlds, avatars, groups) in the dialogs' wrap grids: two per row on phones, three in landscape. */
export const ENTITY_CARD_COMPACT_CLASS = 'compact:w-1/2 compact-landscape:w-1/3';

/**
 * The entity dialogs' "..." menus on phones and touch screens: 40px rows, and never wider than the screen.
 * Desktop menus are unchanged.
 */
export const MENU_CONTENT_TOUCH_CLASS =
    'compact:min-w-56 compact:max-w-[calc(100vw-1rem)] pointer-coarse:[&_[data-slot=dropdown-menu-item]]:py-2.5';

const SCROLLER_SELECTOR = '[data-slot="main-dialog-scroller"]';

/**
 * @param {Element | null | undefined} element
 * @returns {HTMLElement | null} The compact dialog scroller around the element
 */
export function findDialogScroller(element) {
    return element?.closest?.(SCROLLER_SELECTOR) ?? null;
}

/**
 * Scroll offset that puts `pane` at the top of `scroller` when the pane's top has already scrolled out of view, or
 * null when nothing needs to move (the pane is still at or below the scroller's top edge).
 *
 * @param {{ top: number }} scrollerRect
 * @param {{ top: number }} paneRect
 * @param {number} scrollTop
 * @returns {number | null}
 */
export function getPaneScrollTarget(scrollerRect, paneRect, scrollTop) {
    const offset = paneRect.top - scrollerRect.top;
    if (offset >= -1) {
        return null;
    }
    return Math.max(0, scrollTop + offset);
}

/**
 * Puts the compact dialog scroller around `element` back at the top.
 *
 * @param {Element | null | undefined} element
 */
export function scrollDialogToTop(element) {
    const scroller = findDialogScroller(element);
    if (scroller && scroller.scrollTop > 0) {
        scroller.scrollTop = 0;
    }
}

/**
 * Starts a dialog that MainDialogContainer hosts at the top of the compact scroller. The container keeps one scroller
 * for every dialog type and remounts the dialog when the type changes (a crumb from a user to a world, a group, an
 * avatar or the Previous instances list), so without this the new page would open at the old page's scroll depth.
 *
 * @param {import('vue').Ref<HTMLElement | null>} elementRef Any element inside the dialog
 * @returns {{ isCompact: import('vue').Ref<boolean> }}
 */
export function useCompactDialogScrollReset(elementRef) {
    const { isCompact } = useCompactLayout();

    onMounted(() => {
        if (!isCompact.value) return;
        nextTick(() => scrollDialogToTop(elementRef.value));
    });

    return { isCompact };
}

/**
 * Keeps the compact dialog scroller in step with the dialog: the dialog opens at the top of the page, so does a new
 * entity of the same type, and a tab switch while the tab strip is stuck shows the new tab from its start instead of
 * the old tab's scroll depth. Layout is only read on those events, never while scrolling.
 *
 * @param {object} options
 * @param {import('vue').Ref<HTMLElement | null>} options.paneRef The tab pane element
 * @param {() => unknown} options.entityId Getter for the shown entity id
 * @param {() => unknown} options.activeTab Getter for the active tab
 * @returns {{ isCompact: import('vue').Ref<boolean> }}
 */
export function useEntityDialogCompact({ paneRef, entityId, activeTab }) {
    // A different dialog type (user -> world) mounts a new dialog in the same scroller.
    const { isCompact } = useCompactDialogScrollReset(paneRef);

    // The same dialog type shows another entity (user -> user) without remounting.
    watch(entityId, () => {
        if (!isCompact.value) return;
        nextTick(() => scrollDialogToTop(paneRef.value));
    });

    watch(activeTab, () => {
        if (!isCompact.value) return;
        nextTick(() => {
            const pane = paneRef.value;
            const scroller = findDialogScroller(pane);
            if (!pane || !scroller) return;
            const target = getPaneScrollTarget(
                scroller.getBoundingClientRect(),
                pane.getBoundingClientRect(),
                scroller.scrollTop
            );
            if (target !== null) {
                scroller.scrollTop = target;
            }
        });
    });

    return { isCompact };
}

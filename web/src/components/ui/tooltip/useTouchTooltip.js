// Touch access to tooltips (docs/DESIGN.md §3.3). reka's Tooltip ignores touch pointers, so on Android the wrapper
// controls `open` itself: a long-press shows the tooltip, and a tap toggles it on icon-only information triggers.
import { ref } from 'vue';

export const TOOLTIP_LONG_PRESS_MS = 500;
const MOVE_TOLERANCE_PX = 10;

const INTERACTIVE_SELECTOR =
    'button, a[href], input, select, textarea, label, [role="button"], [role="link"], [role="menuitem"], [role="tab"], [data-slot="context-menu-trigger"]';

/**
 * An information-only trigger: nothing to activate and no text of its own (an info icon, a status dot).
 * A trigger that is, sits inside or wraps something tappable (a button, a link, a clickable row, anything drawn with a
 * pointer cursor) is not: a tap there must reach that element.
 *
 * @param {Element | null} element
 * @returns {boolean}
 */
export function isInfoOnlyTrigger(element) {
    if (!element || typeof element.closest !== 'function') return false;
    if (element.closest(INTERACTIVE_SELECTOR) || element.querySelector(INTERACTIVE_SELECTOR)) return false;
    if ((element.textContent ?? '').trim() !== '') return false;
    // `cursor` is inherited, so this also catches clickable ancestors (VRCX marks them with cursor-pointer).
    // Only runs on a tap.
    const view = element.ownerDocument?.defaultView;
    return view?.getComputedStyle?.(element).cursor !== 'pointer';
}

function isTouchLike(event) {
    return event.pointerType === 'touch' || event.pointerType === 'pen';
}

/**
 * @param {object} options
 * @param {() => boolean} options.isEnabled Whether the tooltip can open (not disabled, has content)
 * @param {() => boolean} [options.tapToOpen] Force tap-to-toggle for this trigger
 * @param {number} [options.delay]
 * @returns {{
 *     open: import('vue').Ref<boolean>;
 *     setOpen: (value: boolean) => void;
 *     triggerListeners: object;
 *     cancel: () => void;
 * }}
 */
export function useTouchTooltip({ isEnabled, tapToOpen = () => false, delay = TOOLTIP_LONG_PRESS_MS }) {
    const open = ref(false);
    let timer = 0;
    let startX = 0;
    let startY = 0;
    let longPressed = false;
    let swallowClick = false;
    let wasOpen = false;

    const clearTimer = () => {
        if (timer) {
            clearTimeout(timer);
            timer = 0;
        }
    };

    const setOpen = (value) => {
        open.value = Boolean(value);
    };

    const triggerListeners = {
        onPointerdownCapture(event) {
            if (isTouchLike(event)) {
                wasOpen = open.value;
            }
        },
        onPointerdown(event) {
            longPressed = false;
            swallowClick = false;
            clearTimer();
            if (!isTouchLike(event) || !isEnabled()) return;
            // Inside a context-menu trigger the long-press belongs to the menu (reka's 700 ms timer).
            if (event.currentTarget?.closest?.('[data-slot="context-menu-trigger"]')) return;
            startX = event.clientX;
            startY = event.clientY;
            timer = setTimeout(() => {
                timer = 0;
                longPressed = true;
                swallowClick = true;
                open.value = true;
            }, delay);
        },
        onPointermove(event) {
            if (!timer) return;
            if (
                Math.abs(event.clientX - startX) > MOVE_TOLERANCE_PX ||
                Math.abs(event.clientY - startY) > MOVE_TOLERANCE_PX
            ) {
                clearTimer();
            }
        },
        onPointerup(event) {
            if (!isTouchLike(event)) return;
            const pending = Boolean(timer);
            clearTimer();
            if (longPressed || !pending) return;
            if (tapToOpen() || isInfoOnlyTrigger(event.currentTarget)) {
                open.value = !wasOpen;
                swallowClick = true;
            }
        },
        onPointercancel() {
            clearTimer();
        },
        onContextmenu(event) {
            // Chrome's own long-press menu / text selection would fight the tooltip.
            if (longPressed || timer) {
                event.preventDefault();
            }
        },
        onClickCapture(event) {
            // The click that ends a long-press or a tap-toggle must not activate the trigger or let reka close it.
            if (swallowClick) {
                swallowClick = false;
                event.preventDefault();
                event.stopImmediatePropagation();
            }
        }
    };

    return { open, setOpen, triggerListeners, cancel: clearTimer };
}

// Touch access to hover cards (docs/DESIGN.md §3.3: hover cards open on tap).
//
// reka's own `enableTouch` toggles the card on every touch pointerup that bubbles to the trigger, including taps on the
// buttons, links and names inside it (NotificationItem wraps its Accept/Decline buttons, MyAvatarCard its whole card),
// and it also fires after a long-press. The HoverCard wrapper therefore keeps reka's toggle off and HoverCardTrigger
// toggles the card itself, only for short taps that land on the trigger's own information.

import { isInteractiveDescendant } from '../touch/interactiveTarget';

/** Provided by HoverCard.vue: `{ enabled: Ref<boolean> }`. */
export const HOVER_CARD_TOUCH_KEY = Symbol('vrcx-hover-card-touch');

/** A press held longer than this is a long-press (the context menu's), not a tap. */
export const HOVER_CARD_TAP_MAX_MS = 500;
const TAP_MAX_MOVE_PX = 10;

/** Focus moving into one of these (reka moves it into every modal it opens) dismisses an open touch hover card. */
const MODAL_SELECTOR = '[role="dialog"], [role="alertdialog"]';

/**
 * @param {{ x: number; y: number; time: number } | null} start
 * @param {PointerEvent} event
 * @returns {boolean}
 */
export function isTap(start, event) {
    if (!start) return false;
    if (event.timeStamp - start.time > HOVER_CARD_TAP_MAX_MS) return false;
    return Math.hypot(event.clientX - start.x, event.clientY - start.y) <= TAP_MAX_MOVE_PX;
}

/**
 * Trigger-side handlers. `rootContext` is reka's HoverCardRoot context (open, onOpenChange, onDismiss, triggerElement).
 *
 * @param {object} options
 * @param {() => boolean} options.isEnabled
 * @param {any} options.rootContext
 */
export function createHoverCardTouchHandlers({ isEnabled, rootContext }) {
    let start = null;

    return {
        onPointerdown(event) {
            start =
                event.pointerType === 'touch' ? { x: event.clientX, y: event.clientY, time: event.timeStamp } : null;
        },
        onPointercancel() {
            start = null;
        },
        onPointerup(event) {
            const pressStart = start;
            start = null;
            if (!rootContext || !isEnabled() || event.pointerType !== 'touch') return;
            if (!isTap(pressStart, event)) return;
            // The tap belongs to the button, link or clickable name it landed on.
            if (isInteractiveDescendant(event.target, event.currentTarget)) return;
            if (rootContext.open.value) {
                rootContext.onDismiss();
            } else {
                rootContext.onOpenChange(true);
            }
        }
    };
}

/**
 * While the card is open, a modal that takes focus and does not contain the trigger closes it: a hover card is
 * transient and must not float above a dialog opened after it (its z-index is above every dialog's).
 *
 * @param {FocusEvent} event
 * @param {Element | null | undefined} trigger
 * @returns {boolean}
 */
export function isFocusInOtherModal(event, trigger) {
    const target = /** @type {Element | null} */ (event.target);
    const modal = target?.closest?.(MODAL_SELECTOR);
    if (!modal) return false;
    return !(trigger && modal.contains(trigger));
}

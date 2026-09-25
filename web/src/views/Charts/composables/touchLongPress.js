// Touch long-press for the mutual friends graph (docs/DESIGN.md §3.3).
//
// sigma.js has no touch path to `rightClickNode`, so on phones the node menu (view details, refresh mutuals, hide
// friend) would be unreachable. A press on a node that is held still for `delay` ms opens the same menu instead.

/** Hold time before the node menu opens (ms); Android's own long-press is about 500 ms. */
export const LONG_PRESS_DELAY = 500;

/** Finger travel that turns a press into a pan (px). */
export const LONG_PRESS_MOVE_TOLERANCE = 10;

/**
 * @param {unknown} event Original DOM event of a sigma interaction
 * @returns {boolean} Whether it came from a finger or a pen
 */
export function isTouchLikeEvent(event) {
    if (!event || typeof event !== 'object') {
        return false;
    }
    const { pointerType, type } = /** @type {{ pointerType?: string; type?: string }} */ (event);
    if (pointerType === 'touch' || pointerType === 'pen') {
        return true;
    }
    return typeof type === 'string' && type.startsWith('touch');
}

/**
 * @param {unknown} event A TouchEvent, PointerEvent or MouseEvent
 * @returns {{ x: number; y: number } | null} Client coordinates of the first finger or the pointer
 */
export function getClientPoint(event) {
    if (!event || typeof event !== 'object') {
        return null;
    }
    const source = /** @type {any} */ (event).touches?.[0] ?? /** @type {any} */ (event).changedTouches?.[0] ?? event;
    const x = Number(source.clientX);
    const y = Number(source.clientY);
    return Number.isFinite(x) && Number.isFinite(y) ? { x, y } : null;
}

/**
 * Creates a long-press tracker. `begin` starts the timer for a target, `move` cancels it once the finger travels past
 * the tolerance, `cancel` stops it (finger lifted, second finger, pan), and `consumeFired` tells the tap handler that
 * followed a long-press to do nothing.
 *
 * @param {object} options
 * @param {(target: any, point: { x: number; y: number }) => void} options.onLongPress
 * @param {number} [options.delay]
 * @param {number} [options.tolerance]
 * @param {(fn: () => void, ms: number) => any} [options.setTimer]
 * @param {(id: any) => void} [options.clearTimer]
 * @returns {{
 *     begin: (target: any, point: { x: number; y: number } | null) => void;
 *     move: (point: { x: number; y: number } | null) => void;
 *     cancel: () => void;
 *     consumeFired: () => boolean;
 *     isPending: () => boolean;
 * }}
 */
export function createLongPress({
    onLongPress,
    delay = LONG_PRESS_DELAY,
    tolerance = LONG_PRESS_MOVE_TOLERANCE,
    setTimer = (fn, ms) => setTimeout(fn, ms),
    clearTimer = (id) => clearTimeout(id)
}) {
    let timer = null;
    let start = null;
    let target = null;
    let fired = false;

    function cancel() {
        if (timer !== null) {
            clearTimer(timer);
            timer = null;
        }
        start = null;
        target = null;
    }

    function begin(nextTarget, point) {
        cancel();
        fired = false;
        if (!point) {
            return;
        }
        target = nextTarget;
        start = point;
        timer = setTimer(() => {
            timer = null;
            const firedTarget = target;
            const firedPoint = start;
            start = null;
            target = null;
            fired = true;
            onLongPress(firedTarget, firedPoint);
        }, delay);
    }

    function move(point) {
        if (!start || !point) {
            return;
        }
        if (Math.hypot(point.x - start.x, point.y - start.y) > tolerance) {
            cancel();
        }
    }

    function consumeFired() {
        const wasFired = fired;
        fired = false;
        return wasFired;
    }

    return { begin, move, cancel, consumeFired, isPending: () => timer !== null };
}

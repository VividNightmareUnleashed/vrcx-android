// Pan / zoom maths for the fullscreen image viewer (components/FullscreenImagePreview.vue).
// A view state is {scale, rotate (deg), tx, ty}; points are relative to the viewer's centre.

export const MIN_SCALE = 0.1;
export const MAX_SCALE = 10;
/** Scale a double-tap zooms to (DESIGN.md §3.3). */
export const DOUBLE_TAP_SCALE = 2.5;
export const DOUBLE_TAP_MAX_DELAY_MS = 300;
export const DOUBLE_TAP_MAX_DISTANCE_PX = 24;
export const TAP_MAX_MOVE_PX = 10;

/**
 * @param {number} value
 * @param {number} min
 * @param {number} max
 * @returns {number}
 */
export function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
}

/**
 * Zooms by `factor` while keeping `point` (relative to the viewer centre) fixed on screen.
 *
 * @param {{ scale: number; rotate: number; tx: number; ty: number }} state
 * @param {{ x: number; y: number }} point
 * @param {number} factor
 * @returns {{ scale: number; rotate: number; tx: number; ty: number }}
 */
export function zoomAtPoint(state, point, factor) {
    const oldScale = state.scale;
    const newScale = clamp(oldScale * factor, MIN_SCALE, MAX_SCALE);
    const r = (state.rotate * Math.PI) / 180;
    const cos = Math.cos(r);
    const sin = Math.sin(r);

    // vector from the transformed centre to the point, in the unrotated, unscaled image space
    const vx = point.x - state.tx;
    const vy = point.y - state.ty;
    const ux = (vx * cos + vy * sin) / oldScale;
    const uy = (-vx * sin + vy * cos) / oldScale;

    // back to screen space at the new scale
    const v2x = (ux * cos - uy * sin) * newScale;
    const v2y = (ux * sin + uy * cos) * newScale;

    return { ...state, scale: newScale, tx: point.x - v2x, ty: point.y - v2y };
}

/**
 * @param {{ x: number; y: number }} a
 * @param {{ x: number; y: number }} b
 * @returns {number}
 */
export function distance(a, b) {
    return Math.hypot(a.x - b.x, a.y - b.y);
}

/**
 * @param {{ x: number; y: number }} a
 * @param {{ x: number; y: number }} b
 * @returns {{ x: number; y: number }}
 */
export function midpoint(a, b) {
    return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
}

/**
 * Two-finger pinch: zoom around where the pinch started, then follow the fingers' midpoint.
 *
 * @param {{ scale: number; rotate: number; tx: number; ty: number }} startState State when the second finger landed
 * @param {{ x: number; y: number }} startMid Midpoint at start (relative to the viewer centre)
 * @param {number} startDistance Finger distance at start
 * @param {{ x: number; y: number }} mid Current midpoint (relative to the viewer centre)
 * @param {number} currentDistance Current finger distance
 * @returns {{ scale: number; rotate: number; tx: number; ty: number }}
 */
export function pinchState(startState, startMid, startDistance, mid, currentDistance) {
    const factor = startDistance > 0 ? currentDistance / startDistance : 1;
    const zoomed = zoomAtPoint(startState, startMid, factor);
    return { ...zoomed, tx: zoomed.tx + (mid.x - startMid.x), ty: zoomed.ty + (mid.y - startMid.y) };
}

/**
 * Double-tap toggle: zoom in on the tapped point, or back to fit when already zoomed.
 *
 * @param {{ scale: number; rotate: number; tx: number; ty: number }} state
 * @param {{ x: number; y: number }} point Relative to the viewer centre
 * @returns {{ scale: number; rotate: number; tx: number; ty: number }}
 */
export function doubleTapState(state, point) {
    if (state.scale > 1.01) {
        return { ...state, scale: 1, tx: 0, ty: 0 };
    }
    return zoomAtPoint(state, point, DOUBLE_TAP_SCALE / state.scale);
}

/**
 * @param {{ x: number; y: number; time: number } | null} previous
 * @param {{ x: number; y: number; time: number }} current
 * @returns {boolean}
 */
export function isDoubleTap(previous, current) {
    if (!previous) return false;
    return (
        current.time - previous.time <= DOUBLE_TAP_MAX_DELAY_MS &&
        distance(previous, current) <= DOUBLE_TAP_MAX_DISTANCE_PX
    );
}

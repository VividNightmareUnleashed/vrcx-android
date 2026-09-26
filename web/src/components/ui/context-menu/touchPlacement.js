// Context-menu placement on Android (docs/DESIGN.md §3.3).
//
// A long-press opens the menu at the finger, often close to a screen edge. reka's default order (shift along the
// menu's side, then flip) never moves the menu sideways, so a menu that fits on neither side of the finger sticks out
// of the screen, and one near the bottom is squeezed into a scroller at the screen edge. Flipping first and then
// shifting on both axes (`prioritizePosition`) keeps the whole menu on screen; the padding keeps it off the screen
// edge and clear of the system bars and the keyboard.

/** Gap between a context menu and the screen edge (or a system bar), in CSS px. */
export const TOUCH_MENU_EDGE_MARGIN = 8;

/**
 * @param {CSSStyleDeclaration | undefined} style
 * @param {string} name
 * @returns {number}
 */
function readInset(style, name) {
    const value = parseFloat(style?.getPropertyValue?.(name) ?? '');
    return Number.isFinite(value) && value > 0 ? value : 0;
}

/**
 * Collision padding for a context menu: the edge margin plus the system bar and keyboard insets. The shim writes the
 * insets inline on <html>, so this reads them without a style recalculation.
 *
 * @param {HTMLElement | null | undefined} [root] Element carrying --safe-* and --ime-bottom (default: <html>)
 * @returns {{ top: number; right: number; bottom: number; left: number }}
 */
export function getTouchMenuCollisionPadding(root = globalThis.document?.documentElement) {
    const style = root?.style;
    const margin = TOUCH_MENU_EDGE_MARGIN;
    return {
        top: margin + readInset(style, '--safe-top'),
        right: margin + readInset(style, '--safe-right'),
        bottom: margin + Math.max(readInset(style, '--safe-bottom'), readInset(style, '--ime-bottom')),
        left: margin + readInset(style, '--safe-left')
    };
}

/**
 * Positioning props for reka's ContextMenuContent / ContextMenuSubContent on Android. Props a caller passes itself
 * take precedence (they are spread after these).
 *
 * @param {HTMLElement | null | undefined} [root]
 * @returns {{
 *     prioritizePosition: boolean;
 *     collisionPadding: { top: number; right: number; bottom: number; left: number };
 * }}
 */
export function getTouchMenuPositioning(root) {
    return {
        prioritizePosition: true,
        collisionPadding: getTouchMenuCollisionPadding(root)
    };
}

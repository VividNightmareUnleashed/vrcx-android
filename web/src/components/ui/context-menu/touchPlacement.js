// Context-menu placement on Android (docs/DESIGN.md §3.3).
//
// A long-press opens the menu at the finger, often close to a screen edge. reka's default order (shift along the
// menu's side, then flip) never moves the menu sideways, so a menu that fits on neither side of the finger sticks out
// of the screen, and one near the bottom is squeezed into a scroller at the screen edge. Flipping first and then
// shifting on both axes (`prioritizePosition`) keeps the whole menu on screen; the padding keeps it off the screen
// edge and clear of the system bars and the keyboard.
import { computed, shallowRef, watch } from 'vue';
import { injectContextMenuRootContext } from 'reka-ui';

import { isAndroid } from '@/shared/utils/platform';

/** Gap between a context menu and the screen edge (or a system bar), in CSS px. */
export const TOUCH_MENU_EDGE_MARGIN = 8;

const SIDES = ['top', 'right', 'bottom', 'left'];

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
 * The system bar and keyboard insets. The shim writes them inline on <html>, so this reads them without a style
 * recalculation.
 *
 * @param {HTMLElement | null | undefined} [root] Element carrying --safe-* and --ime-bottom (default: <html>)
 * @returns {{ top: number; right: number; bottom: number; left: number }}
 */
export function readTouchInsets(root = globalThis.document?.documentElement) {
    const style = root?.style;
    return {
        top: readInset(style, '--safe-top'),
        right: readInset(style, '--safe-right'),
        bottom: Math.max(readInset(style, '--safe-bottom'), readInset(style, '--ime-bottom')),
        left: readInset(style, '--safe-left')
    };
}

/**
 * Collision padding for a context menu: a padding (the edge margin unless a caller passes its own, as a number or per
 * side) plus the system bar and keyboard insets, which always apply.
 *
 * @param {HTMLElement | null | undefined} [root]
 * @param {number | Partial<Record<'top' | 'right' | 'bottom' | 'left', number>>} [padding]
 * @returns {{ top: number; right: number; bottom: number; left: number }}
 */
export function getTouchMenuCollisionPadding(root, padding = TOUCH_MENU_EDGE_MARGIN) {
    const insets = readTouchInsets(root ?? undefined);
    const result = {};
    for (const side of SIDES) {
        const base = typeof padding === 'number' ? padding : Number(padding?.[side] ?? 0);
        result[side] = (Number.isFinite(base) ? base : 0) + insets[side];
    }
    return result;
}

/**
 * Positioning props for reka's ContextMenuContent / ContextMenuSubContent on Android. A caller's own
 * `prioritizePosition` wins; a caller's `collisionPadding` replaces the edge margin, and the insets are added to it.
 *
 * @param {HTMLElement | null | undefined} [root]
 * @param {{ prioritizePosition?: boolean; collisionPadding?: number | object }} [caller] Props the caller passed
 * @returns {{
 *     prioritizePosition: boolean;
 *     collisionPadding: { top: number; right: number; bottom: number; left: number };
 * }}
 */
export function getTouchMenuPositioning(root, caller = {}) {
    return {
        prioritizePosition: caller.prioritizePosition ?? true,
        collisionPadding: getTouchMenuCollisionPadding(root, caller.collisionPadding ?? TOUCH_MENU_EDGE_MARGIN)
    };
}

/**
 * The props to bind on a context-menu content component: the caller's props (useForwardPropsEmits) with the Android
 * placement merged in. The insets are read again each time the menu opens (rotation, keyboard). Desktop builds get the
 * caller's props unchanged, so reka's defaults apply.
 *
 * @param {import('vue').Ref<Record<string, unknown>>} forwarded
 * @returns {import('vue').ComputedRef<Record<string, unknown>>}
 */
export function useTouchMenuProps(forwarded) {
    if (!isAndroid) {
        return computed(() => forwarded.value);
    }
    const openCount = shallowRef(0);
    const rootContext = injectContextMenuRootContext(null);
    if (rootContext) {
        watch(rootContext.open, (open) => {
            if (open) {
                openCount.value += 1;
            }
        });
    }
    return computed(() => {
        // Re-read the insets on every open.
        void openCount.value;
        return { ...forwarded.value, ...getTouchMenuPositioning(undefined, forwarded.value) };
    });
}

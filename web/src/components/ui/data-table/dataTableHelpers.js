/**
 * Pure helper functions for DataTableLayout.
 * Extracted for testability.
 */

/**
 * @param {object} col - TanStack column instance
 * @returns {boolean}
 */
export function isSpacer(col) {
    return col?.id === '__spacer';
}

/**
 * @param {object} col - TanStack column instance
 * @returns {boolean}
 */
export function isStretch(col) {
    return !!col?.columnDef?.meta?.stretch;
}

/**
 * Resolves a column's display label for the visibility menu.
 * Supports both string and function labels (for lazy i18n).
 *
 * @param {object} col - TanStack column instance
 * @returns {string}
 */
export function resolveHeaderLabel(col) {
    const label = col?.columnDef?.meta?.label;
    if (typeof label === 'function') return label();
    return label ?? col?.id ?? '';
}

/**
 * Filters columns to determine which are toggleable in the visibility menu.
 *
 * @param {Array} cols - Array of TanStack column instances
 * @returns {Array}
 */
export function getToggleableColumns(cols) {
    if (!Array.isArray(cols)) return [];
    return cols.filter((col) => {
        if (isSpacer(col)) return false;
        if (col.columnDef?.meta?.disableVisibilityToggle) return false;
        if (!col.columnDef?.meta?.label) return false;
        return true;
    });
}

/**
 * Computes the style object for a column's <col> element.
 *
 * @param {object} col - TanStack column instance
 * @returns {object | null}
 */
export function getColStyle(col) {
    if (isSpacer(col)) return { width: '0px' };
    if (isStretch(col)) return null;

    const size = col?.getSize?.();
    if (!Number.isFinite(size)) return null;
    return { width: `${size}px` };
}

/**
 * Determines if a header can be reordered via drag-and-drop.
 *
 * @param {object} header - TanStack header instance
 * @param {function} getPinnedState - Function to check if column is pinned
 * @returns {boolean}
 */
export function isReorderable(header, getPinnedState) {
    const col = header?.column;
    if (!col) return false;
    if (isSpacer(col)) return false;
    if (!col.columnDef?.meta?.label) return false;
    if (getPinnedState?.(col)) return false;
    if (col.columnDef?.meta?.disableReorder) return false;
    return true;
}

/*
 * Card mode (docs/DESIGN.md §3.1).
 *
 * columnDef.meta.mobile places a column's cell in a card slot:
 *   mobile: 'title'
 *   mobile: { slot: 'footer', label: true, order: 1, class: '...' }
 */

/** Card slots in reading order. `detail` only shows while the row is expanded; `hidden` never shows. */
export const MOBILE_SLOTS = Object.freeze([
    'leading',
    'title',
    'titleSuffix',
    'badge',
    'trailing',
    'body',
    'footer',
    'detail',
    'actions',
    'hidden'
]);

/**
 * @param {object} col - TanStack column instance
 * @returns {boolean} Whether the column declares a card hint
 */
export function hasMobileHint(col) {
    return Boolean(col?.columnDef?.meta?.mobile);
}

/**
 * Normalised card hint of a column. Columns without a hint go to the footer, prefixed with their label, so a column
 * the user made visible is never lost; spacer columns are hidden.
 *
 * @param {object} col - TanStack column instance
 * @returns {{ slot: string; label: boolean; order: number; class: string }}
 */
export function getMobileHint(col) {
    if (isSpacer(col)) {
        return { slot: 'hidden', label: false, order: 0, class: '' };
    }
    const raw = col?.columnDef?.meta?.mobile;
    if (!raw) {
        return { slot: 'footer', label: Boolean(col?.columnDef?.meta?.label), order: 0, class: '' };
    }
    if (typeof raw === 'string') {
        return { slot: MOBILE_SLOTS.includes(raw) ? raw : 'footer', label: false, order: 0, class: '' };
    }
    return {
        slot: MOBILE_SLOTS.includes(raw.slot) ? raw.slot : 'footer',
        label: Boolean(raw.label),
        order: Number.isFinite(raw.order) ? raw.order : 0,
        class: typeof raw.class === 'string' ? raw.class : ''
    };
}

/**
 * Groups a row's visible cells by card slot, keeping column order within a slot unless `order` says otherwise.
 *
 * @param {Array} cells - Row.getVisibleCells()
 * @returns {Record<string, { cell: object; hint: object; index: number }[]>}
 */
export function groupCellsBySlot(cells) {
    const groups = Object.fromEntries(MOBILE_SLOTS.map((slot) => [slot, []]));
    (Array.isArray(cells) ? cells : []).forEach((cell, index) => {
        const hint = getMobileHint(cell?.column);
        groups[hint.slot].push({ cell, hint, index });
    });
    for (const slot of MOBILE_SLOTS) {
        groups[slot].sort((a, b) => a.hint.order - b.hint.order || a.index - b.index);
    }
    return groups;
}

/**
 * Columns offered in the View options sort picker.
 *
 * @param {Array} cols - Array of TanStack column instances
 * @returns {Array}
 */
export function getSortableColumns(cols) {
    if (!Array.isArray(cols)) return [];
    return cols.filter((col) => {
        if (isSpacer(col)) return false;
        if (!col?.columnDef?.meta?.label) return false;
        return typeof col.getCanSort === 'function' ? col.getCanSort() : false;
    });
}

const INTERACTIVE_TARGET_SELECTOR =
    'a, button, input, select, textarea, label, summary, [role="button"], [role="link"], [role="checkbox"], [role="switch"], [contenteditable="true"], .cursor-pointer';

/**
 * Whether a tap inside a card belongs to the element that was tapped (links, buttons, clickable names) rather than
 * to the card itself.
 *
 * @param {EventTarget | null} target
 * @param {Element | null} row
 * @returns {boolean}
 */
export function isInteractiveTarget(target, row) {
    if (!target || typeof target.closest !== 'function') return false;
    const hit = target.closest(INTERACTIVE_TARGET_SELECTOR);
    return Boolean(hit && hit !== row && (!row || row.contains(hit)));
}

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

/** Size of the compact pagination's items (DataTableLayout: 32px buttons, 4px apart) and of the touch tools. */
const COMPACT_CONTROL_PX = 32;
const COMPACT_CONTROL_GAP_PX = 4;
/** Gap between the view's toolbar, the touch tools and an inline pagination. */
const TOOLBAR_GAP_PX = 8;

/** Room the view's toolbar keeps next to an inline pagination; narrower toolbars scroll sideways. */
export const INLINE_PAGINATION_TOOLBAR_MIN_PX = 240;

/**
 * Width of the compact pagination (sibling count 0, first and last page shown): up to five page and ellipsis items
 * plus Previous and Next.
 *
 * @param {number} pageCount
 * @returns {number}
 */
export function estimateCompactPaginationWidth(pageCount) {
    const pages = Math.min(Math.max(Math.floor(Number(pageCount)) || 1, 1), 5);
    const items = pages + 2;
    return items * COMPACT_CONTROL_PX + (items - 1) * COMPACT_CONTROL_GAP_PX;
}

/**
 * Whether phone landscape moves the pagination up into the toolbar row: only when the view's toolbar keeps at least
 * `toolbarMinPx` next to the touch tools and the pagination (a table beside the open friends panel with many pages
 * keeps its pagination below the list).
 *
 * @param {object} state
 * @param {number} state.tableWidth Width of the table (DataTableLayout root) in CSS px
 * @param {number} state.pageCount
 * @param {number} state.toolCount Touch tools in the toolbar row (View options, Quick actions)
 * @param {boolean} state.hasToolbar Whether the view passes a toolbar
 * @param {number} [state.toolbarMinPx]
 * @returns {boolean}
 */
export function shouldInlinePagination({
    tableWidth,
    pageCount,
    toolCount,
    hasToolbar,
    toolbarMinPx = INLINE_PAGINATION_TOOLBAR_MIN_PX
}) {
    if (!Number.isFinite(tableWidth) || tableWidth <= 0) return false;
    const tools = toolCount > 0 ? toolCount * COMPACT_CONTROL_PX + (toolCount - 1) * COMPACT_CONTROL_GAP_PX : 0;
    const used = estimateCompactPaginationWidth(pageCount) + TOOLBAR_GAP_PX + tools + (hasToolbar ? TOOLBAR_GAP_PX : 0);
    return tableWidth - used >= (hasToolbar ? toolbarMinPx : 0);
}

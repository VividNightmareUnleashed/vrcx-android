// Phone layout of dashboards (docs/DESIGN.md §3.4): the panels of every row are stacked in one scrolling column.

/**
 * @param {string | { key?: string } | null} panel Panel entry of a dashboard row (a key or `{ key, config }`)
 * @returns {string | null}
 */
export function getPanelKey(panel) {
    if (!panel) return null;
    if (typeof panel === 'string') return panel;
    return typeof panel.key === 'string' && panel.key ? panel.key : null;
}

/**
 * Height of a stacked panel: widgets take about half a screen, full pages most of one. Empty panels only need room
 * for their message. In phone landscape a page panel is exactly as tall as the scroll area, so its own scroller never
 * reaches past the screen.
 *
 * @param {string | { key?: string } | null} panel
 * @returns {string} Tailwind classes
 */
export function getCompactPanelHeightClass(panel) {
    const key = getPanelKey(panel);
    if (!key) return 'h-24';
    if (key.startsWith('widget:')) return 'h-[45dvh] min-h-56';
    return 'h-[75dvh] min-h-80 compact-landscape:h-[calc(100dvh-var(--app-chrome-h)-2rem)] compact-landscape:min-h-0';
}

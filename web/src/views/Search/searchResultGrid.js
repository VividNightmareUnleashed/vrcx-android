/** PC: as many 180px cards as fit per row (upstream). */
export const SEARCH_GRID_PC = 'repeat(auto-fill, minmax(180px, 1fr))';
/** Phone portrait: two cards per row (docs/DESIGN.md §3.4). */
export const SEARCH_GRID_PHONE_PORTRAIT = 'repeat(2, minmax(0, 1fr))';
/** Phone landscape: the page is wide but short, so smaller cards, as many as fit. */
export const SEARCH_GRID_PHONE_LANDSCAPE = 'repeat(auto-fill, minmax(160px, 1fr))';

/**
 * Grid template for the world and avatar result cards.
 *
 * @param {boolean} isCompact Phone layout
 * @param {boolean} isCompactLandscape Phone layout in landscape
 * @returns {string}
 */
export function getSearchResultGridColumns(isCompact, isCompactLandscape) {
    if (!isCompact) {
        return SEARCH_GRID_PC;
    }
    return isCompactLandscape ? SEARCH_GRID_PHONE_LANDSCAPE : SEARCH_GRID_PHONE_PORTRAIT;
}

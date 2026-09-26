// Page sizes of card lists in the phone layout (docs/DESIGN.md §7).

/** Largest page the compact feed offers: its cards are not virtualised, and each one is costly to mount on a phone. */
export const COMPACT_MAX_PAGE_SIZE = 50;

/**
 * The user's page sizes up to `max`, or `[max]` when every one is larger.
 *
 * @param {number[]} sizes
 * @param {number} [max]
 * @returns {number[]}
 */
export function capCompactPageSizes(sizes, max = COMPACT_MAX_PAGE_SIZE) {
    const capped = (Array.isArray(sizes) ? sizes : []).filter((size) => size <= max);
    return capped.length ? capped : [max];
}

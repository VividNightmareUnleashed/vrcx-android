// Phone layout helpers of the Friend List (docs/DESIGN.md §3.1).

/**
 * Whether a row tap selects the friend instead of opening the user dialog. Phones in bulk unfriend mode select: the
 * card is the only sizeable target there. PC rows keep opening the user; their checkbox is the selection target.
 *
 * @param {{ isCompact: boolean; bulkMode: boolean; friendId?: string | null }} state
 * @returns {boolean}
 */
export function isRowTapSelection({ isCompact, bulkMode, friendId }) {
    return Boolean(isCompact && bulkMode && friendId);
}

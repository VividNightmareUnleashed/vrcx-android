/**
 * Columns a phone hides by default in the My Avatars table. They stay available in
 * the table's View options sheet, and their cells go to the card footer once shown.
 */
export const MY_AVATARS_PHONE_HIDDEN_COLUMNS = Object.freeze([
    'version',
    'impostor',
    'pcPerf',
    'androidPerf',
    'iosPerf',
    'created_at'
]);

/**
 * Table persistence options for My Avatars. Phones keep their own column state (key `my-avatars-phone`), so hiding
 * columns on a phone never changes the PC table.
 *
 * @param {boolean} compact Phone layout (useCompactLayout().isCompact)
 * @returns {{ persistKey: string; initialColumnVisibility?: Record<string, boolean> }}
 */
export function getMyAvatarsTableOptions(compact) {
    if (!compact) {
        return { persistKey: 'my-avatars' };
    }
    return {
        persistKey: 'my-avatars-phone',
        initialColumnVisibility: Object.fromEntries(MY_AVATARS_PHONE_HIDDEN_COLUMNS.map((id) => [id, false]))
    };
}

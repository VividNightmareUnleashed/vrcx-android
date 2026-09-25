// Legend for the player list's icon column. On PC each icon explains itself in a hover tooltip; phones show the
// meanings of the icons that are on screen as a line above the list (docs/DESIGN.md §3.3).

/** Same order and icons as the icon column in columns.jsx. */
export const PLAYER_ICON_LEGEND = Object.freeze([
    { key: 'isMaster', icon: '👑', labelKey: 'android.views_a.player_icons.master' },
    { key: 'isModerator', icon: '⚔️', labelKey: 'android.views_a.player_icons.moderator' },
    { key: 'isFriend', icon: '💚', labelKey: 'android.views_a.player_icons.friend' },
    { key: 'isBlocked', icon: '⛔', labelKey: 'android.views_a.player_icons.blocked' },
    { key: 'isMuted', icon: '🔇', labelKey: 'android.views_a.player_icons.muted' },
    { key: 'isAvatarInteractionDisabled', icon: '🚫', labelKey: 'android.views_a.player_icons.avatar_interaction' },
    { key: 'isChatBoxMuted', icon: '💬', labelKey: 'android.views_a.player_icons.chatbox_muted' },
    { key: 'timeoutTime', icon: '🔴', labelKey: 'android.views_a.player_icons.timeout' },
    { key: 'ageVerified', icon: '', labelKey: 'android.views_a.player_icons.age_verified' }
]);

/**
 * @param {object[]} rows Player list rows (stores/instance.js currentInstanceUsersData)
 * @returns {{ key: string; icon: string; labelKey: string }[]} Legend entries for the icons shown in `rows`
 */
export function getPlayerIconLegend(rows) {
    const list = Array.isArray(rows) ? rows : [];
    return PLAYER_ICON_LEGEND.filter((entry) => list.some((row) => Boolean(row?.[entry.key])));
}

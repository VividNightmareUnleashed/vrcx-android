// Phone stand-in for the game log's right-click menus (docs/DESIGN.md §3.3): which actions a row's "more" menu offers.
// On PC these live in context menus on the Location cell (world actions), the Event cell (Copy) and the video and
// resource cells (Open link / Copy).

const NO_LINK_VIDEO_IDS = new Set(['LSMedia', 'PopcornPalace']);

/**
 * @param {object} entry Game log row (table mode) or sessions event
 * @returns {{ location: string; copyText: string; openUrl: string }}
 */
export function getGameLogMenuActions(entry) {
    const actions = { location: '', copyText: '', openUrl: '' };
    if (!entry || typeof entry !== 'object') {
        return actions;
    }
    switch (entry.type) {
        case 'Location':
            actions.location = entry.location || '';
            break;
        case 'PortalSpawn':
            actions.location = entry.instanceId || '';
            break;
        case 'Event':
            actions.copyText = entry.data || '';
            break;
        case 'External':
            actions.copyText = entry.message || '';
            break;
        case 'VideoPlay':
            actions.copyText = entry.videoUrl || '';
            actions.openUrl = entry.videoUrl && !NO_LINK_VIDEO_IDS.has(entry.videoId) ? entry.videoUrl : '';
            break;
        case 'ImageLoad':
        case 'StringLoad':
            actions.copyText = entry.resourceUrl || '';
            actions.openUrl = entry.resourceUrl || '';
            break;
    }
    return actions;
}

/**
 * @param {object} entry
 * @returns {boolean} Whether the row has anything to put in a "more" menu
 */
export function hasGameLogMenu(entry) {
    const { location, copyText, openUrl } = getGameLogMenuActions(entry);
    return Boolean(location || copyText || openUrl);
}

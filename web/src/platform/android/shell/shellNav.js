// Pure helpers that map the NavMenu model onto the phone dock and app bar (docs/DESIGN.md §2.1).
import { DASHBOARD_NAV_KEY_PREFIX, DEFAULT_DASHBOARD_ICON } from '../../../shared/constants/dashboard';

/** The dock shows Friends, the first three top-level entries of the user's nav layout, then Menu. */
export const DOCK_ENTRY_COUNT = 3;

const SETTINGS_TITLE = Object.freeze({ key: 'settings', icon: 'ri-settings-3-line', labelKey: 'nav_tooltip.settings' });

/**
 * @param {Array} menuItems NavMenu menu items (navLayoutHelpers.buildMenuItems)
 * @param {number} [count]
 * @returns {Array} The first `count` top-level entries (items, dashboards or folders)
 */
export function getDockEntries(menuItems, count = DOCK_ENTRY_COUNT) {
    if (!Array.isArray(menuItems)) return [];
    return menuItems.slice(0, count);
}

/**
 * @param {object} entry Menu item, folder or folder child
 * @param {string} activeIndex NavMenu activeMenuIndex
 * @returns {boolean}
 */
export function isEntryActive(entry, activeIndex) {
    if (!entry || !activeIndex) return false;
    if (Array.isArray(entry.children) && entry.children.length) {
        return entry.children.some((child) => child.index === activeIndex);
    }
    return entry.index === activeIndex;
}

/**
 * @param {object} entry Menu item ({title, titleIsCustom}) or folder child ({label, titleIsCustom})
 * @param {(key: string) => string} t
 * @returns {string}
 */
export function getEntryLabel(entry, t) {
    if (!entry) return '';
    const raw = entry.title ?? entry.label ?? '';
    return entry.titleIsCustom ? raw : t(raw || '');
}

/**
 * Width budget of a dock label, in em of its 10.5px font: a portrait slot on a 360px phone is 72px wide, 70px of which
 * hold text.
 */
export const DOCK_LABEL_MAX_EM = 6.6;

const ELLIPSIS = '…';

/**
 * Rough rendered width of a label in em: CJK, Hangul and full-width characters take a full em, everything else about
 * half (measured on Inter at 10.5px: "Direct Access" = 6.56em). Cheap enough to run on every dock render, and it never
 * reads layout.
 *
 * @param {string} text
 * @returns {number}
 */
export function estimateLabelEm(text) {
    let width = 0;
    for (const char of String(text ?? '')) {
        width += (char.codePointAt(0) ?? 0) >= 0x1100 ? 1 : 0.5;
    }
    return width;
}

/**
 * Shortens a label that would not fit a dock slot at a word boundary ("Position des amis" → "Position…"), so the
 * dock never cuts a word in half ("Friends Lo…"). Single words and scripts without spaces are returned whole and
 * left to the CSS ellipsis.
 *
 * @param {string} label
 * @param {number} [maxEm]
 * @returns {string}
 */
export function shortenDockLabel(label, maxEm = DOCK_LABEL_MAX_EM) {
    const text = String(label ?? '')
        .trim()
        .replace(/\s+/g, ' ');
    if (estimateLabelEm(text) <= maxEm) return text;
    const words = text.split(' ');
    if (words.length < 2) return text;
    // The ellipsis itself is about 1em wide.
    const budget = maxEm - 1;
    let result = words[0];
    if (estimateLabelEm(result) > budget) return text;
    for (const word of words.slice(1)) {
        const next = `${result} ${word}`;
        if (estimateLabelEm(next) > budget) break;
        result = next;
    }
    // A stub such as "My…" says less than the CSS ellipsis would ("My Favouri…").
    if (estimateLabelEm(result) < budget / 2) return text;
    return `${result}${ELLIPSIS}`;
}

/**
 * The label under a dock icon: the entry's short dock label when the locale has one (android.shell.dock_short.<key>,
 * for built-in entries whose PC label is too long for a slot, such as "Friends Locations" → "Locations"), otherwise
 * its nav label shortened at a word boundary. Folders and dashboards keep the user's own name.
 *
 * @param {object} entry Menu item or folder
 * @param {(key: string) => string} t
 * @param {(key: string) => boolean} [te] vue-i18n `te`, to look up the short label
 * @returns {string}
 */
export function getDockLabel(entry, t, te) {
    if (!entry) return '';
    if (!entry.titleIsCustom && entry.index && typeof te === 'function') {
        const shortKey = `android.shell.dock_short.${String(entry.index).replace(/-/g, '_')}`;
        if (te(shortKey)) return t(shortKey);
    }
    return shortenDockLabel(getEntryLabel(entry, t));
}

/**
 * Nav keys a route belongs to, most specific first (mirrors useNavLayout's activeMenuIndex).
 *
 * @param {object} route Vue-router route
 * @returns {string[]}
 */
export function getRouteNavKeys(route) {
    if (!route) return [];
    if (route.name === 'dashboard' && route.params?.id) {
        return [`${DASHBOARD_NAV_KEY_PREFIX}${route.params.id}`];
    }
    if (Array.isArray(route.meta?.navKeys)) {
        return [...route.meta.navKeys];
    }
    return [route.meta?.navKey || route.name].filter(Boolean);
}

/**
 * The app bar title: the icon and label of the nav entry for the current route, a dashboard's own name, or Settings.
 *
 * @param {object} route Vue-router route
 * @param {Array} definitions All nav definitions (built-in, tools and dashboards)
 * @param {(key: string) => string} t
 * @returns {{ key: string; icon: string; label: string } | null}
 */
export function resolveRouteTitle(route, definitions, t) {
    if (!route?.name) return null;
    if (route.name === 'settings') {
        return { key: SETTINGS_TITLE.key, icon: SETTINGS_TITLE.icon, label: t(SETTINGS_TITLE.labelKey) };
    }
    const list = Array.isArray(definitions) ? definitions : [];
    const byKey = new Map(list.map((definition) => [definition.key, definition]));
    const toTitle = (definition) => ({
        key: definition.key,
        icon: definition.icon || (definition.isDashboard ? DEFAULT_DASHBOARD_ICON : ''),
        label: definition.isDashboard ? definition.labelKey : t(definition.labelKey || definition.tooltip || '')
    });
    for (const key of getRouteNavKeys(route)) {
        const definition = byKey.get(key);
        if (definition) return toTitle(definition);
    }
    const byRoute = list.find((definition) => !definition.isDashboard && definition.routeName === route.name);
    if (byRoute) return toTitle(byRoute);
    return { key: String(route.name), icon: '', label: String(route.name) };
}

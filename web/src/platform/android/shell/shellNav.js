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

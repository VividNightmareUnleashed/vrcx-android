// Phone shell state shared by the compact frame, the dock, the app bar and the Android back handler.
// Plain module state (not a Pinia store): it only describes which shell panel is showing.
import { reactive } from 'vue';

export const shellState = reactive({
    /** The friends panel (views/Sidebar/Sidebar.vue slid in from the right) is showing. */
    friendsPanelOpen: false,
    /** Mirror of the nav sheet (NavMenu inside the SidebarProvider mobile Sheet). */
    navSheetOpen: false
});

let navSheetSetter = null;

/**
 * Registered by the compact frame (inside the SidebarProvider), so code outside the provider can drive the sheet.
 *
 * @param {((open: boolean) => void) | null} setter
 */
export function registerNavSheetSetter(setter) {
    navSheetSetter = setter;
}

/**
 * @param {boolean} open
 */
export function setNavSheetOpen(open) {
    if (open) {
        shellState.friendsPanelOpen = false;
    }
    if (navSheetSetter) {
        navSheetSetter(open);
    } else {
        shellState.navSheetOpen = open;
    }
}

/**
 * @param {boolean} open
 */
export function setFriendsPanelOpen(open) {
    if (open && shellState.navSheetOpen) {
        setNavSheetOpen(false);
    }
    shellState.friendsPanelOpen = Boolean(open);
}

export function toggleFriendsPanel() {
    setFriendsPanelOpen(!shellState.friendsPanelOpen);
}

/** Closes every shell panel. Returns true when something was open. */
export function closeShellPanels() {
    let closed = false;
    if (shellState.friendsPanelOpen) {
        shellState.friendsPanelOpen = false;
        closed = true;
    }
    if (shellState.navSheetOpen) {
        setNavSheetOpen(false);
        closed = true;
    }
    return closed;
}

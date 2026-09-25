// Slots of MainLayout's frames that receive the shared routed view and Sidebar on Android (Teleport targets).

export const FRAME_SLOT_IDS = Object.freeze({
    compactMain: 'vrcx-frame-compact-main',
    compactFriends: 'vrcx-frame-compact-friends',
    desktopMain: 'vrcx-frame-desktop-main',
    desktopFriends: 'vrcx-frame-desktop-friends',
    parking: 'vrcx-frame-parking'
});

/**
 * Where the routed view and the Sidebar live right now.
 *
 * @param {{ isCompact: boolean; compactFrameReady: boolean }} state
 * @returns {{ main: string; friends: string; parked: boolean }} Element ids
 */
export function resolveFrameSlots({ isCompact, compactFrameReady }) {
    if (!isCompact) {
        return { main: FRAME_SLOT_IDS.desktopMain, friends: FRAME_SLOT_IDS.desktopFriends, parked: false };
    }
    if (!compactFrameReady) {
        return { main: FRAME_SLOT_IDS.parking, friends: FRAME_SLOT_IDS.parking, parked: true };
    }
    return { main: FRAME_SLOT_IDS.compactMain, friends: FRAME_SLOT_IDS.compactFriends, parked: false };
}

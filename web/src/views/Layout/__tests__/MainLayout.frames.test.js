// Android: switching between the phone frame and the PC frame (a small tablet rotating across the compact threshold,
// split screen, foldables) must not remount the routed view or the Sidebar (docs/DESIGN.md §2.2).
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick, ref } from 'vue';

const mocks = vi.hoisted(() => {
    // MainLayout reads the ANDROID build define to include the phone frame chunk.
    globalThis.ANDROID = true;
    return {
        isAndroid: true,
        isCompact: null,
        counts: { sidebarMounted: 0, sidebarUnmounted: 0, routedMounted: 0, routedUnmounted: 0 },
        watchState: { isLoggedIn: true }
    };
});

vi.mock('../../../shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    get isAndroid() {
        return mocks.isAndroid;
    }
}));
vi.mock('../../../composables/useCompactLayout', async () => {
    const { ref: vueRef } = await import('vue');
    mocks.isCompact = vueRef(false);
    return {
        useCompactLayout: () => ({
            isCompact: mocks.isCompact,
            isCompactLandscape: vueRef(false),
            isCoarsePointer: vueRef(true)
        })
    };
});
vi.mock('pinia', async (i) => ({ ...(await i()), storeToRefs: (s) => s }));
vi.mock('vue-router', async (importOriginal) => ({
    ...(await importOriginal()),
    useRouter: () => ({ replace: vi.fn() })
}));
vi.mock('../../../services/watchState', () => ({ watchState: mocks.watchState }));
vi.mock('../../../stores', () => ({
    useAppearanceSettingsStore: () => ({
        navWidth: ref(240),
        isNavCollapsed: ref(false),
        setNavCollapsed: vi.fn(),
        setNavWidth: vi.fn()
    })
}));
vi.mock('../../../composables/useMainLayoutResizable', () => ({
    useMainLayoutResizable: () => ({
        asideDefaultSize: 30,
        asideMinSize: 0,
        asideMaxPx: 480,
        mainDefaultSize: 70,
        handleLayout: vi.fn(),
        isAsideCollapsed: () => false,
        isAsideCollapsedStatic: false,
        isSideBarTabShow: ref(true)
    })
}));
vi.mock('../../../components/ui/resizable', () => ({
    ResizablePanelGroup: { template: '<div data-testid="panel-group"><slot :layout="[]" /></div>' },
    ResizablePanel: { template: '<div data-testid="panel"><slot /></div>' },
    ResizableHandle: { template: '<div />' }
}));
vi.mock('../../../components/ui/sidebar', () => ({
    SidebarProvider: { template: '<div><slot /></div>' },
    SidebarInset: { template: '<div><slot /></div>' }
}));
vi.mock('../CompactFrame.vue', () => ({
    __esModule: true,
    default: {
        emits: ['ready'],
        mounted() {
            this.$emit('ready');
        },
        template: `<div data-testid="compact-frame">
            <slot name="nav" />
            <main class="vrcx-phone-main"><slot name="main" /></main>
            <aside class="vrcx-friends-panel"><slot name="friends" /></aside>
        </div>`
    }
}));
vi.mock('../../Sidebar/Sidebar.vue', async () => {
    const { onMounted, onUnmounted } = await import('vue');
    return {
        default: {
            setup() {
                onMounted(() => mocks.counts.sidebarMounted++);
                onUnmounted(() => mocks.counts.sidebarUnmounted++);
            },
            template: '<div data-testid="sidebar" />'
        }
    };
});
vi.mock('../RoutedView.vue', async () => {
    const { onMounted, onUnmounted } = await import('vue');
    return {
        default: {
            props: { max: { type: Number, default: undefined } },
            setup() {
                onMounted(() => mocks.counts.routedMounted++);
                onUnmounted(() => mocks.counts.routedUnmounted++);
            },
            template: '<div data-testid="routed" class="x-container" :data-max="max ?? \'none\'" />'
        }
    };
});
const { stub } = vi.hoisted(() => ({ stub: () => ({ default: { template: '<div />' } }) }));
vi.mock('../../../components/nav-menu/NavMenu.vue', stub);
vi.mock('../../../components/StatusBar.vue', stub);
vi.mock('../../../components/dialogs/MainDialogContainer.vue', stub);
vi.mock('../../../components/FullscreenImagePreview.vue', stub);
vi.mock('../../../components/dialogs/ChooseFavoriteGroupDialog.vue', stub);
vi.mock('../../../components/dialogs/LaunchDialog.vue', stub);
vi.mock('../../Settings/dialogs/LaunchOptionsDialog.vue', stub);
vi.mock('../../Favorites/dialogs/FriendImportDialog.vue', stub);
vi.mock('../../Favorites/dialogs/WorldImportDialog.vue', stub);
vi.mock('../../Favorites/dialogs/AvatarImportDialog.vue', stub);
vi.mock('../../../components/dialogs/GroupDialog/GroupEditDialog.vue', stub);
vi.mock('../../../components/dialogs/GroupDialog/GroupEventEditDialog.vue', stub);
vi.mock('../../../components/dialogs/InviteGroupDialog.vue', stub);
vi.mock('../../Settings/dialogs/VRChatConfigDialog.vue', stub);
vi.mock('../../Settings/dialogs/PrimaryPasswordDialog.vue', stub);
vi.mock('../../../components/dialogs/SendBoopDialog.vue', stub);
vi.mock('../../Tools/components/GlobalToolsDialogs.vue', stub);
vi.mock('../../Settings/dialogs/ChangelogDialog.vue', stub);
vi.mock('../../../components/onboarding/WhatsNewDialog.vue', stub);
vi.mock('../../../components/onboarding/SpotlightDialog.vue', stub);

import { FRAME_SLOT_IDS, resolveFrameSlots } from '../frameSlots';
import MainLayout from '../MainLayout.vue';

async function settle() {
    for (let i = 0; i < 4; i++) {
        await flushPromises();
        await nextTick();
    }
}

function parentSlotId(testId) {
    return document.querySelector(`[data-testid="${testId}"]`)?.parentElement?.id ?? null;
}

// Undo the ANDROID define override for whatever runs next in this worker.
afterAll(() => {
    globalThis.ANDROID = false;
});

describe('MainLayout frames on Android', () => {
    let wrapper;

    beforeEach(() => {
        mocks.isAndroid = true;
        mocks.isCompact.value = false;
        Object.assign(mocks.counts, { sidebarMounted: 0, sidebarUnmounted: 0, routedMounted: 0, routedUnmounted: 0 });
    });

    afterEach(() => {
        wrapper?.unmount();
        wrapper = null;
        document.body.innerHTML = '';
    });

    it('moves one routed view and one Sidebar between the frames instead of remounting them', async () => {
        wrapper = mount(MainLayout, { attachTo: document.body });
        await settle();
        expect(parentSlotId('routed')).toBe(FRAME_SLOT_IDS.desktopMain);
        expect(parentSlotId('sidebar')).toBe(FRAME_SLOT_IDS.desktopFriends);
        // The tablet (PC) frame on Android caps the page cache too.
        expect(document.querySelector('[data-testid="routed"]').dataset.max).toBe('8');

        // Portrait on a small tablet: the phone frame.
        mocks.isCompact.value = true;
        await settle();
        expect(document.querySelector('[data-testid="compact-frame"]')).not.toBeNull();
        expect(parentSlotId('routed')).toBe(FRAME_SLOT_IDS.compactMain);
        expect(parentSlotId('sidebar')).toBe(FRAME_SLOT_IDS.compactFriends);
        expect(document.querySelector('.vrcx-phone-main > .vrcx-frame-slot > .x-container')).not.toBeNull();
        expect(document.querySelector('[data-testid="routed"]').dataset.max).toBe('6');

        // And back to landscape.
        mocks.isCompact.value = false;
        await settle();
        expect(document.querySelector('[data-testid="compact-frame"]')).toBeNull();
        expect(document.querySelector('[data-testid="routed"]').dataset.max).toBe('8');
        expect(parentSlotId('routed')).toBe(FRAME_SLOT_IDS.desktopMain);
        expect(parentSlotId('sidebar')).toBe(FRAME_SLOT_IDS.desktopFriends);

        expect(mocks.counts).toEqual({ sidebarMounted: 1, sidebarUnmounted: 0, routedMounted: 1, routedUnmounted: 0 });
        expect(document.querySelectorAll('[data-testid="sidebar"]')).toHaveLength(1);
        expect(document.querySelectorAll('[data-testid="routed"]')).toHaveLength(1);
    });

    it('starts the phone frame with the page in its slot, not in the parking slot', async () => {
        mocks.isCompact.value = true;
        wrapper = mount(MainLayout, { attachTo: document.body });
        await settle();

        expect(parentSlotId('routed')).toBe(FRAME_SLOT_IDS.compactMain);
        expect(parentSlotId('sidebar')).toBe(FRAME_SLOT_IDS.compactFriends);
        expect(mocks.counts.routedMounted).toBe(1);
        expect(mocks.counts.sidebarMounted).toBe(1);
    });

    it('keeps the upstream inline structure in desktop builds', async () => {
        mocks.isAndroid = false;
        wrapper = mount(MainLayout, { attachTo: document.body });
        await settle();

        const panels = document.querySelectorAll('[data-testid="panel"]');
        expect(panels[0].firstElementChild?.dataset.testid).toBe('routed');
        expect(panels[1].firstElementChild?.dataset.testid).toBe('sidebar');
        expect(document.querySelector('.vrcx-frame-slot')).toBeNull();
    });
});

describe('resolveFrameSlots', () => {
    it('parks the shared children until the phone frame has mounted its slots', () => {
        expect(resolveFrameSlots({ isCompact: true, compactFrameReady: false })).toEqual({
            main: FRAME_SLOT_IDS.parking,
            friends: FRAME_SLOT_IDS.parking,
            parked: true
        });
        expect(resolveFrameSlots({ isCompact: true, compactFrameReady: true }).main).toBe(FRAME_SLOT_IDS.compactMain);
        expect(resolveFrameSlots({ isCompact: false, compactFrameReady: true }).friends).toBe(
            FRAME_SLOT_IDS.desktopFriends
        );
    });
});

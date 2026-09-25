<template>
    <template v-if="watchState.isLoggedIn">
        <!-- Phone frame (Android, compact layout): docs/DESIGN.md §2. Only the frame differs; the routed view, the
             friends panel (Sidebar), dialogs and watchers below are shared. -->
        <CompactFrame v-if="isCompact && CompactFrame" @ready="compactFrameReady = true">
            <template #nav>
                <NavMenu sheet>
                    <template #sheet-footer>
                        <StatusBar variant="sheet" />
                    </template>
                </NavMenu>
            </template>
            <template #main>
                <div :id="FRAME_SLOT_IDS.compactMain" class="vrcx-frame-slot contents" />
            </template>
            <template #friends>
                <div :id="FRAME_SLOT_IDS.compactFriends" class="vrcx-frame-slot contents" />
            </template>
        </CompactFrame>

        <div v-else class="vrcx-shell flex flex-col flex-1 h-full min-h-0 min-w-0 overflow-hidden">
            <SidebarProvider
                :open="sidebarOpen"
                :width="navWidth"
                :width-icon="48"
                :mobile="desktopNavMobile"
                class="relative flex-1 h-full min-w-0 min-h-0"
                @update:open="handleSidebarOpenChange">
                <NavMenu />

                <div
                    v-if="!isCoarsePointer"
                    v-show="sidebarOpen"
                    class="absolute top-0 bottom-0 z-30 w-1 cursor-ew-resize select-none"
                    :style="{ left: 'var(--sidebar-width)' }"
                    @pointerdown.prevent="startNavResize" />

                <SidebarInset class="min-w-0 bg-sidebar">
                    <ResizablePanelGroup
                        direction="horizontal"
                        auto-save-id="vrcx-main-layout-right-sidebar"
                        :class="[
                            'group/main-layout flex-1 h-full min-w-0',
                            { 'aside-collapsed': isAsideCollapsedStatic }
                        ]"
                        @layout="handleLayout">
                        <template #default="{ layout }">
                            <ResizablePanel :default-size="mainDefaultSize" :order="1">
                                <div
                                    v-if="shareFrameChildren"
                                    :id="FRAME_SLOT_IDS.desktopMain"
                                    class="vrcx-frame-slot contents" />
                                <RoutedView v-else />
                            </ResizablePanel>

                            <ResizableHandle
                                with-handle
                                :class="[
                                    isAsideCollapsed(layout) ? 'opacity-100' : 'opacity-0',
                                    'z-20 [&>div]:-translate-x-1/2'
                                ]"></ResizableHandle>
                            <ResizablePanel
                                ref="asidePanelRef"
                                :default-size="asideDefaultSize"
                                :min-size="asideMinSize"
                                :collapsed-size="0"
                                collapsible
                                :order="2"
                                :style="{ maxWidth: `${asideMaxPx}px` }">
                                <div
                                    v-if="shareFrameChildren"
                                    :id="FRAME_SLOT_IDS.desktopFriends"
                                    class="vrcx-frame-slot contents" />
                                <Sidebar v-else></Sidebar>
                            </ResizablePanel>
                        </template>
                    </ResizablePanelGroup>
                </SidebarInset>
            </SidebarProvider>
            <StatusBar />
        </div>

        <!-- Android: one routed view and one Sidebar for the life of the layout, moved between the frames' slots when
             the frame changes (a small tablet rotating across the compact threshold, split screen, foldables), so the
             page cache, scroll positions and the dialogs the Sidebar owns survive it (docs/DESIGN.md §2.2). Desktop
             builds render both inline, as upstream. -->
        <template v-if="shareFrameChildren && frameChildrenMounted">
            <div
                :id="FRAME_SLOT_IDS.parking"
                class="vrcx-frame-parking pointer-events-none invisible fixed inset-0 -z-10 overflow-hidden opacity-0"
                aria-hidden="true"
                inert />
            <Teleport defer :to="`#${frameSlots.main}`">
                <RoutedView :max="isCompact ? COMPACT_KEEP_ALIVE_MAX : undefined" />
            </Teleport>
            <Teleport defer :to="`#${frameSlots.friends}`">
                <Sidebar></Sidebar>
            </Teleport>
        </template>

        <!-- ## Dialogs ## -->
        <MainDialogContainer />
        <InviteGroupDialog />
        <GroupEditDialog />
        <GroupEventEditDialog />
        <FullscreenImagePreview />
        <LaunchDialog />
        <LaunchOptionsDialog />
        <FriendImportDialog />
        <WorldImportDialog />
        <AvatarImportDialog />
        <ChooseFavoriteGroupDialog />
        <VRChatConfigDialog />
        <PrimaryPasswordDialog />
        <SendBoopDialog />
        <GlobalToolsDialogs />
        <ChangelogDialog />
        <WhatsNewDialog />
        <SpotlightDialog />
    </template>
</template>

<script setup>
    import { computed, defineAsyncComponent, nextTick, onUnmounted, ref, watch } from 'vue';
    import { storeToRefs } from 'pinia';
    import { useRouter } from 'vue-router';

    import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from '../../components/ui/resizable';
    import { SidebarInset, SidebarProvider } from '../../components/ui/sidebar';
    import { useAppearanceSettingsStore } from '../../stores';
    import { useMainLayoutResizable } from '../../composables/useMainLayoutResizable';
    import { useCompactLayout } from '../../composables/useCompactLayout';
    import { watchState } from '../../services/watchState';
    import { isAndroid } from '../../shared/utils/platform';
    import { COMPACT_KEEP_ALIVE_MAX } from './keepAlive';
    import { FRAME_SLOT_IDS, resolveFrameSlots } from './frameSlots';

    import AvatarImportDialog from '../Favorites/dialogs/AvatarImportDialog.vue';
    import ChangelogDialog from '../Settings/dialogs/ChangelogDialog.vue';
    import ChooseFavoriteGroupDialog from '../../components/dialogs/ChooseFavoriteGroupDialog.vue';
    import FriendImportDialog from '../Favorites/dialogs/FriendImportDialog.vue';
    import FullscreenImagePreview from '../../components/FullscreenImagePreview.vue';
    import GlobalToolsDialogs from '../Tools/components/GlobalToolsDialogs.vue';
    import GroupEditDialog from '../../components/dialogs/GroupDialog/GroupEditDialog.vue';
    import GroupEventEditDialog from '../../components/dialogs/GroupDialog/GroupEventEditDialog.vue';
    import InviteGroupDialog from '../../components/dialogs/InviteGroupDialog.vue';
    import LaunchDialog from '../../components/dialogs/LaunchDialog.vue';
    import LaunchOptionsDialog from '../Settings/dialogs/LaunchOptionsDialog.vue';
    import MainDialogContainer from '../../components/dialogs/MainDialogContainer.vue';
    import NavMenu from '../../components/nav-menu/NavMenu.vue';
    import PrimaryPasswordDialog from '../Settings/dialogs/PrimaryPasswordDialog.vue';
    import SendBoopDialog from '../../components/dialogs/SendBoopDialog.vue';
    import Sidebar from '../Sidebar/Sidebar.vue';
    import StatusBar from '../../components/StatusBar.vue';
    import VRChatConfigDialog from '../Settings/dialogs/VRChatConfigDialog.vue';
    import WorldImportDialog from '../Favorites/dialogs/WorldImportDialog.vue';
    import WhatsNewDialog from '../../components/onboarding/WhatsNewDialog.vue';
    import SpotlightDialog from '../../components/onboarding/SpotlightDialog.vue';
    import RoutedView from './RoutedView.vue';

    // Android only: the phone frame is a separate chunk. The build-time define (not the isAndroid re-export) lets the
    // bundler drop the import from desktop builds.
    const CompactFrame = ANDROID ? defineAsyncComponent(() => import('./CompactFrame.vue')) : null;

    const router = useRouter();

    // Always false on desktop builds (useCompactLayout never matches there).
    const { isCompact, isCoarsePointer } = useCompactLayout();
    // Android tablets keep the PC frame with its PC nav at every width (upstream switches the nav to a Sheet at
    // 768px, which has no trigger in the PC frame). Desktop builds keep the upstream media query.
    const desktopNavMobile = isAndroid ? false : undefined;

    // Android: the routed view and the Sidebar are rendered once and teleported into the current frame's slots.
    const shareFrameChildren = isAndroid;
    // The phone frame is an async chunk: until it has mounted its slots, the shared children wait in a parking slot.
    const compactFrameReady = ref(false);
    watch(isCompact, (compact) => {
        if (!compact) {
            compactFrameReady.value = false;
        }
    });
    const frameSlots = computed(() =>
        resolveFrameSlots({ isCompact: isCompact.value, compactFrameReady: compactFrameReady.value })
    );
    // Mount the shared children once a real slot exists, so the first page does not start in the parking slot.
    const frameChildrenMounted = ref(false);
    watch(
        frameSlots,
        (slots) => {
            if (!slots.parked) {
                frameChildrenMounted.value = true;
            }
        },
        { immediate: true }
    );

    const appearanceSettingsStore = useAppearanceSettingsStore();
    const { navWidth, isNavCollapsed } = storeToRefs(appearanceSettingsStore);

    const sidebarOpen = computed(() => !isNavCollapsed.value);

    const handleSidebarOpenChange = (open) => {
        appearanceSettingsStore.setNavCollapsed(!open);
    };

    let isResizingNav = false;
    let cleanupNavResize = null;

    const startNavResize = (event) => {
        if (!sidebarOpen.value) {
            return;
        }

        isResizingNav = true;
        const prevUserSelect = document.body.style.userSelect;
        const prevCursor = document.body.style.cursor;
        document.body.style.userSelect = 'none';
        document.body.style.cursor = 'col-resize';

        const handleMove = (e) => {
            if (!isResizingNav) {
                return;
            }
            appearanceSettingsStore.setNavWidth(e.clientX);
        };

        const handleUp = () => {
            isResizingNav = false;
            document.body.style.userSelect = prevUserSelect;
            document.body.style.cursor = prevCursor;
            window.removeEventListener('pointermove', handleMove);
            window.removeEventListener('pointerup', handleUp);
            cleanupNavResize = null;
        };

        window.addEventListener('pointermove', handleMove);
        window.addEventListener('pointerup', handleUp);
        cleanupNavResize = handleUp;
        appearanceSettingsStore.setNavWidth(event.clientX);
    };

    onUnmounted(() => {
        cleanupNavResize?.();
    });

    const {
        asideDefaultSize,
        asideMinSize,
        asideMaxPx,
        mainDefaultSize,
        handleLayout,
        isAsideCollapsed,
        isAsideCollapsedStatic,
        isSideBarTabShow
    } = useMainLayoutResizable();

    const asidePanelRef = ref(null);
    let restoreAsideAfterHiddenRoute = false;

    watch(isSideBarTabShow, async (show) => {
        if (!show) {
            restoreAsideAfterHiddenRoute = asidePanelRef.value?.isCollapsed === false;
        }

        await nextTick();
        if (show) {
            if (restoreAsideAfterHiddenRoute) {
                asidePanelRef.value?.expand();
            }
        } else {
            asidePanelRef.value?.collapse();
        }
    });

    watch(
        () => watchState.isLoggedIn,
        (isLoggedIn) => {
            if (!isLoggedIn) {
                router.replace({ name: 'login' });
            }
        },
        { immediate: true }
    );
</script>

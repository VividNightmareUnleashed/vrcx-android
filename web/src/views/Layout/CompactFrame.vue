<template>
    <!-- The phone nav is always the expanded PC nav inside the sheet, whatever the tablet/PC collapsed setting is. -->
    <SidebarProvider
        :mobile="true"
        :default-open="true"
        class="vrcx-shell vrcx-phone-shell h-full min-h-0 min-w-0 flex-1">
        <!-- NavMenu: rendered by the provider as the left nav sheet. -->
        <slot name="nav" />
        <NavSheetBridge />

        <div class="vrcx-phone-frame" :class="{ 'is-landscape': isCompactLandscape }">
            <PhoneDock class="vrcx-phone-dock" :rail="isCompactLandscape" />
            <PhoneAppBar class="vrcx-phone-bar" @title-click="handleTitleClick" />

            <main ref="mainRef" class="vrcx-phone-main">
                <slot name="main" />
            </main>

            <!-- The single persistent friends panel (views/Sidebar/Sidebar.vue). Hidden with transform/visibility,
                 never v-if or display:none: it owns Quick Search, the notification center and more, and its
                 virtualized list must stay measurable (docs/DESIGN.md §2.2). -->
            <aside
                class="vrcx-friends-panel"
                :class="{ 'is-open': shellState.friendsPanelOpen }"
                :aria-hidden="!shellState.friendsPanelOpen"
                :inert="!shellState.friendsPanelOpen">
                <slot name="friends" />
            </aside>
        </div>
    </SidebarProvider>
</template>

<script setup>
    // Phone frame of MainLayout (docs/DESIGN.md §2): app bar, page card, dock (rail in landscape), friends panel and
    // nav sheet. MainLayout keeps the dialogs, watchers and the routed view; this component only arranges them.
    import { onMounted, ref } from 'vue';

    import { SidebarProvider } from '../../components/ui/sidebar';
    import { useCompactLayout } from '../../composables/useCompactLayout';
    import { scrollPageToTop } from '../../platform/android/shell/navigation';
    import { shellState } from '../../platform/android/shell/shellState';

    import NavSheetBridge from '../../platform/android/shell/NavSheetBridge';
    import PhoneAppBar from '../../platform/android/shell/PhoneAppBar.vue';
    import PhoneDock from '../../platform/android/shell/PhoneDock.vue';

    // MainLayout moves the shared routed view and Sidebar into the #main and #friends slots once they exist.
    const emit = defineEmits(['ready']);

    const { isCompactLandscape } = useCompactLayout();
    const mainRef = ref(null);

    onMounted(() => emit('ready'));

    function handleTitleClick() {
        if (shellState.friendsPanelOpen) {
            return;
        }
        scrollPageToTop(mainRef.value);
    }
</script>

<style scoped>
    /*
     * One DOM for both orientations, so rotating never remounts the page or the friends panel.
     * Portrait:  app bar / page / dock, the friends panel slides over the page.
     * Landscape: rail | app bar + page | friends panel pushed in on the right (min(360px, 45vw)).
     */
    .vrcx-phone-frame {
        position: relative;
        display: grid;
        flex: 1 1 0%;
        min-width: 0;
        min-height: 0;
        overflow: hidden;
        grid-template-columns: minmax(0, 1fr);
        grid-template-rows: auto minmax(0, 1fr) auto;
        grid-template-areas: 'bar' 'main' 'dock';
        background: var(--sidebar);
    }

    .vrcx-phone-bar {
        grid-area: bar;
    }

    .vrcx-phone-dock {
        grid-area: dock;
    }

    .vrcx-phone-main {
        grid-area: main;
        position: relative;
        min-width: 0;
        min-height: 0;
        overflow: hidden;
    }

    .vrcx-friends-panel {
        grid-area: main;
        z-index: 20;
        min-width: 0;
        min-height: 0;
        overflow: hidden;
        background: var(--sidebar);
        transform: translateX(100%);
        visibility: hidden;
        transition:
            transform 250ms cubic-bezier(0.4, 0, 0.2, 1),
            visibility 0s linear 250ms;
    }

    .vrcx-friends-panel.is-open {
        transform: none;
        visibility: visible;
        transition:
            transform 250ms cubic-bezier(0.4, 0, 0.2, 1),
            visibility 0s;
    }

    .vrcx-phone-frame.is-landscape {
        grid-template-columns: auto minmax(0, 1fr) auto;
        grid-template-rows: auto minmax(0, 1fr);
        grid-template-areas:
            'dock bar aside'
            'dock main aside';
    }

    .is-landscape .vrcx-friends-panel {
        grid-area: aside;
        position: absolute;
        top: 0;
        right: 0;
        bottom: 0;
        width: min(360px, 45vw);
        border-left: 1px solid var(--border);
        transition: none;
    }

    .is-landscape .vrcx-friends-panel.is-open {
        position: relative;
        transition: none;
    }

    @media (prefers-reduced-motion: reduce) {
        .vrcx-friends-panel,
        .vrcx-friends-panel.is-open {
            transition: none;
        }
    }
</style>

// Renderless helper mounted inside the phone frame's SidebarProvider: mirrors the nav sheet (the provider's mobile
// Sheet) into shellState, lets code outside the provider open or close it, and closes the shell panels whenever the
// route changes (the sheet closes on navigation, docs/DESIGN.md §2.3).
import { defineComponent, onBeforeUnmount, watch } from 'vue';
import { useRoute } from 'vue-router';

import { useSidebar } from '../../../components/ui/sidebar';
import { registerNavSheetSetter, shellState } from './shellState';

export default defineComponent({
    name: 'NavSheetBridge',
    setup() {
        const sidebar = useSidebar();
        const route = useRoute();

        registerNavSheetSetter((open) => sidebar.setOpenMobile(Boolean(open)));

        watch(
            sidebar.openMobile,
            (open) => {
                shellState.navSheetOpen = Boolean(open);
                if (open) {
                    shellState.friendsPanelOpen = false;
                }
            },
            { immediate: true }
        );

        watch(
            () => route.fullPath,
            () => {
                sidebar.setOpenMobile(false);
                shellState.friendsPanelOpen = false;
            }
        );

        onBeforeUnmount(() => {
            registerNavSheetSetter(null);
            shellState.navSheetOpen = false;
            shellState.friendsPanelOpen = false;
        });

        return () => null;
    }
});

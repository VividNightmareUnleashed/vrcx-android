<template>
    <header
        class="vrcx-app-bar flex min-w-0 items-center gap-0.5 border-b border-border bg-sidebar pl-1 pr-1 text-sidebar-foreground">
        <button
            type="button"
            class="flex h-full min-w-0 flex-1 cursor-pointer items-center gap-2 rounded-md px-2 text-left outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring"
            :aria-label="title.label"
            @click="emit('title-click')">
            <i
                v-if="title.icon"
                :class="title.icon"
                class="inline-flex size-6 shrink-0 items-center justify-center text-lg"
                aria-hidden="true" />
            <span class="truncate text-base font-semibold">{{ title.label }}</span>
        </button>

        <GameIndicator />

        <Button
            variant="ghost"
            size="icon"
            class="size-10 rounded-full"
            :aria-label="t('android.shell.search')"
            @click="quickSearchStore.open()">
            <Search class="size-[18px]" />
        </Button>

        <ContextMenu v-if="hasUnseen && notificationLayout !== 'table'">
            <ContextMenuTrigger as-child>
                <Button
                    variant="ghost"
                    size="icon"
                    class="relative size-10 rounded-full"
                    :aria-label="t('side_panel.notification_center.title')"
                    @click="handleBellClick">
                    <Bell class="size-[18px]" />
                    <span class="absolute top-2 right-2.5 size-1.5 rounded-full bg-red-500" aria-hidden="true" />
                </Button>
            </ContextMenuTrigger>
            <ContextMenuContent>
                <ContextMenuItem @click="notificationStore.markAllAsSeen()">
                    {{ t('nav_menu.mark_all_read') }}
                </ContextMenuItem>
            </ContextMenuContent>
        </ContextMenu>
        <Button
            v-else
            variant="ghost"
            size="icon"
            class="relative size-10 rounded-full"
            :aria-label="t('side_panel.notification_center.title')"
            @click="handleBellClick">
            <Bell class="size-[18px]" />
            <span
                v-if="hasUnseen"
                class="absolute top-2 right-2.5 size-1.5 rounded-full bg-red-500"
                aria-hidden="true" />
        </Button>
    </header>
</template>

<script setup>
    // Phone app bar (docs/DESIGN.md §2.1): the route's nav icon and label (tap scrolls the page to the top), the game
    // indicator, Quick Search and the notification bell.
    import { computed } from 'vue';
    import { Bell, Search } from 'lucide-vue-next';
    import { storeToRefs } from 'pinia';
    import { useI18n } from 'vue-i18n';
    import { useRoute, useRouter } from 'vue-router';

    import { Button } from '../../../components/ui/button';
    import {
        ContextMenu,
        ContextMenuContent,
        ContextMenuItem,
        ContextMenuTrigger
    } from '../../../components/ui/context-menu';
    import { useNavMenuModel } from '../../../components/nav-menu/navMenuModel';
    import { navDefinitions } from '../../../shared/constants/ui';
    import { useNotificationStore, useNotificationsSettingsStore, useUiStore } from '../../../stores';
    import { useQuickSearchStore } from '../../../stores/quickSearch';
    import { resolveRouteTitle } from './shellNav';
    import { shellState } from './shellState';

    import GameIndicator from './GameIndicator.vue';

    const emit = defineEmits(['title-click']);

    const { t } = useI18n();
    const route = useRoute();
    const router = useRouter();
    const navModel = useNavMenuModel();
    const quickSearchStore = useQuickSearchStore();
    const notificationStore = useNotificationStore();
    const { isNotificationCenterOpen, hasUnseenNotifications } = storeToRefs(notificationStore);
    const { notificationLayout } = storeToRefs(useNotificationsSettingsStore());
    const { notifiedMenus } = storeToRefs(useUiStore());

    const title = computed(() => {
        if (shellState.friendsPanelOpen) {
            return { icon: 'ri-team-line', label: t('side_panel.friends') };
        }
        const definitions = navModel.value?.allNavDefinitions?.value ?? navDefinitions;
        return resolveRouteTitle(route, definitions, t) ?? { icon: '', label: 'VRCX' };
    });

    // The same unseen marker as on PC: the bell dot for the notification center, the nav dot for the table layout.
    const hasUnseen = computed(() =>
        notificationLayout.value === 'table'
            ? notifiedMenus.value.includes('notification')
            : hasUnseenNotifications.value
    );

    function handleBellClick() {
        if (notificationLayout.value === 'table') {
            router.push({ name: 'notification' });
            return;
        }
        isNotificationCenterOpen.value = !isNotificationCenterOpen.value;
    }
</script>

<style scoped>
    .vrcx-app-bar {
        height: var(--app-bar-h, 48px);
    }
</style>

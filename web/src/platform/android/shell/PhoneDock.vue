<template>
    <nav
        class="vrcx-dock flex shrink-0 select-none bg-sidebar text-sidebar-foreground"
        :class="
            rail
                ? 'vrcx-dock--rail flex-col items-center gap-1 border-r border-border py-1.5'
                : 'border-t border-border'
        "
        :aria-label="t('android.shell.dock_label')">
        <!-- Friends: the friends panel (the PC's right column). -->
        <button
            type="button"
            class="vrcx-dock-slot"
            :class="{ 'is-active': friendsOpen }"
            :aria-pressed="friendsOpen"
            :aria-label="t('side_panel.friends')"
            @click="toggleFriendsPanel()">
            <span class="vrcx-dock-icon">
                <i class="ri-team-line inline-flex size-6 items-center justify-center text-lg" aria-hidden="true" />
            </span>
            <span v-if="!rail" class="vrcx-dock-label">{{ t('side_panel.friends') }}</span>
        </button>

        <!-- The first three top-level entries of the user's own nav layout. -->
        <template v-for="entry in entries" :key="entry.index">
            <DropdownMenu v-if="entry.children?.length">
                <DropdownMenuTrigger as-child>
                    <button
                        type="button"
                        class="vrcx-dock-slot"
                        :class="{ 'is-active': isActive(entry) }"
                        :aria-label="getEntryLabel(entry, t)">
                        <span class="vrcx-dock-icon">
                            <i
                                :class="entry.icon"
                                class="inline-flex size-6 items-center justify-center text-lg"
                                aria-hidden="true" />
                            <span v-if="isNotified(entry)" class="vrcx-dock-dot bg-red-500" aria-hidden="true" />
                        </span>
                        <span v-if="!rail" class="vrcx-dock-label">{{ getEntryLabel(entry, t) }}</span>
                    </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent :side="rail ? 'right' : 'top'" align="center" class="w-56">
                    <DropdownMenuItem
                        v-for="child in entry.children"
                        :key="child.index"
                        :class="child.index === activeIndex && 'bg-accent font-medium'"
                        @select="activate(child)">
                        <i
                            v-if="child.icon"
                            :class="child.icon"
                            class="relative inline-flex size-4 items-center justify-center text-base"
                            aria-hidden="true"
                            ><span
                                v-if="navModel?.isEntryNotified?.(child)"
                                class="vrcx-dock-dot vrcx-dock-dot--small bg-red-500"
                                aria-hidden="true"
                        /></i>
                        <span>{{ getEntryLabel(child, t) }}</span>
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>

            <button
                v-else
                type="button"
                class="vrcx-dock-slot"
                :class="{ 'is-active': isActive(entry) }"
                :aria-current="isActive(entry) ? 'page' : undefined"
                :aria-label="getEntryLabel(entry, t)"
                @click="activate(entry)">
                <span class="vrcx-dock-icon">
                    <i
                        :class="entry.icon"
                        class="inline-flex size-6 items-center justify-center text-lg"
                        aria-hidden="true" />
                    <span v-if="isNotified(entry)" class="vrcx-dock-dot bg-red-500" aria-hidden="true" />
                </span>
                <span v-if="!rail" class="vrcx-dock-label">{{ getEntryLabel(entry, t) }}</span>
            </button>
        </template>

        <!-- Menu: the full nav sheet (NavMenu), from the left like the PC nav. -->
        <button
            type="button"
            class="vrcx-dock-slot"
            :class="{ 'is-active': menuOpen }"
            :aria-expanded="menuOpen"
            :aria-label="t('android.shell.menu')"
            @click="openMenu">
            <span class="vrcx-dock-icon">
                <i class="ri-menu-line inline-flex size-6 items-center justify-center text-lg" aria-hidden="true" />
                <span v-if="hasHiddenNotifications" class="vrcx-dock-dot bg-red-500" aria-hidden="true" />
            </span>
            <span v-if="!rail" class="vrcx-dock-label">{{ t('android.shell.menu') }}</span>
        </button>
    </nav>
</template>

<script setup>
    // Phone dock (docs/DESIGN.md §2.1) and, in landscape, the 48px icon rail (§2.4). Built from the mounted NavMenu's
    // own model, so entries, folders, dashboards, active state and notification dots match the PC nav exactly.
    import { computed } from 'vue';
    import { useI18n } from 'vue-i18n';

    import {
        DropdownMenu,
        DropdownMenuContent,
        DropdownMenuItem,
        DropdownMenuTrigger
    } from '../../../components/ui/dropdown-menu';
    import { useSidebar } from '../../../components/ui/sidebar';
    import { useNavMenuModel } from '../../../components/nav-menu/navMenuModel';
    import { getDockEntries, getEntryLabel, isEntryActive } from './shellNav';
    import { setFriendsPanelOpen, shellState, toggleFriendsPanel } from './shellState';

    const props = defineProps({
        rail: {
            type: Boolean,
            default: false
        }
    });

    const { t } = useI18n();
    const sidebar = useSidebar();
    const modelRef = useNavMenuModel();

    const navModel = computed(() => modelRef.value);
    const menuItems = computed(() => navModel.value?.menuItems?.value ?? []);
    const activeIndex = computed(() => navModel.value?.activeMenuIndex?.value ?? '');
    const entries = computed(() => getDockEntries(menuItems.value));

    const friendsOpen = computed(() => shellState.friendsPanelOpen);
    const menuOpen = computed(() => Boolean(sidebar.openMobile.value));

    // Portrait: the friends panel covers the page, so only Friends is active. The rail shows both side by side.
    const isActive = (entry) => (!friendsOpen.value || props.rail) && isEntryActive(entry, activeIndex.value);
    const isNotified = (entry) => Boolean(navModel.value?.isNavItemNotified?.(entry));

    // Entries that only live in the nav sheet still get a dot, on the Menu slot.
    const hasHiddenNotifications = computed(() =>
        menuItems.value.slice(entries.value.length).some((entry) => isNotified(entry))
    );

    function activate(entry) {
        setFriendsPanelOpen(false);
        navModel.value?.triggerNavAction?.(entry);
    }

    function openMenu() {
        setFriendsPanelOpen(false);
        sidebar.setOpenMobile(true);
    }
</script>

<style scoped>
    .vrcx-dock:not(.vrcx-dock--rail) {
        height: var(--dock-h, 56px);
    }

    .vrcx-dock--rail {
        width: var(--rail-w, 48px);
    }

    .vrcx-dock-slot {
        display: flex;
        min-width: 0;
        flex: 1 1 0%;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 2px;
        cursor: pointer;
        outline: none;
        -webkit-tap-highlight-color: transparent;
    }

    .vrcx-dock--rail .vrcx-dock-slot {
        flex: none;
        width: var(--touch-min, 40px);
        height: var(--touch-min, 40px);
    }

    .vrcx-dock-icon {
        position: relative;
        display: flex;
        height: 28px;
        width: 48px;
        align-items: center;
        justify-content: center;
        border-radius: calc(var(--radius) - 2px);
        transition: background-color 0.15s ease;
    }

    .vrcx-dock--rail .vrcx-dock-icon {
        height: var(--touch-min, 40px);
        width: var(--touch-min, 40px);
    }

    .vrcx-dock-slot.is-active .vrcx-dock-icon,
    .vrcx-dock-slot:active .vrcx-dock-icon {
        background: var(--sidebar-accent);
        color: var(--sidebar-accent-foreground);
    }

    .vrcx-dock-slot:focus-visible .vrcx-dock-icon {
        box-shadow: 0 0 0 2px var(--sidebar-ring);
    }

    .vrcx-dock-label {
        max-width: 100%;
        overflow: hidden;
        padding: 0 2px;
        font-size: 10.5px;
        line-height: 14px;
        text-overflow: ellipsis;
        white-space: nowrap;
        color: var(--muted-foreground);
    }

    .vrcx-dock-slot.is-active .vrcx-dock-label {
        font-weight: 500;
        color: var(--sidebar-accent-foreground);
    }

    /* The PC nav's notification dot (NavMenu.vue .notify-dot). */
    .vrcx-dock-dot {
        position: absolute;
        top: 4px;
        right: 10px;
        width: 6px;
        height: 6px;
        border-radius: 50%;
    }

    .vrcx-dock--rail .vrcx-dock-dot {
        top: 8px;
        right: 8px;
    }

    .vrcx-dock-dot--small {
        top: 0;
        right: -2px;
    }
</style>

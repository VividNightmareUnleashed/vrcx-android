<template>
    <div class="x-container x-container--auto-height" ref="playerListRef">
        <div class="flex h-full min-h-0 flex-col overflow-y-auto overflow-x-hidden">
            <div
                v-if="currentInstanceWorld.ref.id"
                ref="playerListHeaderRef"
                :style="isCompact ? null : { display: 'flex', minHeight: '120px' }"
                class="mb-7 compact:mb-3 compact:grid compact:grid-cols-[auto_minmax(0,1fr)] compact:gap-x-3"
                :data-layout="isCompact ? 'compact' : undefined">
                <img
                    v-if="!worldImageError"
                    :src="currentInstanceWorld.ref.thumbnailImageUrl"
                    class="cursor-pointer compact:row-span-3 compact:object-cover"
                    :style="worldImageStyle"
                    @click="showFullscreenImageDialog(currentInstanceWorld.ref.imageUrl)"
                    @error="worldImageError = true"
                    loading="lazy" />
                <div
                    v-else
                    class="flex items-center justify-center bg-muted compact:row-span-3"
                    :style="worldImageStyle">
                    <Image class="size-8 text-muted-foreground" />
                </div>
                <!-- Phones: display: contents, so the rows below join the header grid (location, description and the
                     stats run full width under the image). -->
                <div
                    class="ml-2 compact:contents"
                    :style="
                        isCompact
                            ? null
                            : { display: 'flex', flexDirection: 'column', minWidth: '320px', width: '100%' }
                    ">
                    <div class="flex items-center">
                        <span
                            class="cursor-pointer"
                            style="
                                font-weight: bold;
                                overflow: hidden;
                                text-overflow: ellipsis;
                                display: -webkit-box;
                                -webkit-box-orient: vertical;
                                line-clamp: 1;
                            "
                            @click="showWorldDialog(currentInstanceWorld.ref.id)">
                            <Home
                                v-if="
                                    currentUser.$homeLocation &&
                                    currentUser.$homeLocation.worldId === currentInstanceWorld.ref.id
                                "
                                class="inline-block" />
                            {{ currentInstanceWorld.ref.name }}
                        </span>
                    </div>
                    <div>
                        <span
                            class="cursor-pointer x-grey font-mono"
                            @click="showUserDialog(currentInstanceWorld.ref.authorId)"
                            v-text="currentInstanceWorld.ref.authorName"></span>
                    </div>
                    <div class="mt-1.5">
                        <Badge class="mr-1.5" v-if="currentInstanceWorld.ref.$isLabs" variant="outline">
                            {{ t('dialog.world.tags.labs') }}
                        </Badge>
                        <Badge
                            class="mr-1.5"
                            v-else-if="currentInstanceWorld.ref.releaseStatus === 'public'"
                            variant="outline">
                            {{ t('dialog.world.tags.public') }}
                        </Badge>
                        <Badge
                            class="mr-1.5"
                            v-else-if="currentInstanceWorld.ref.releaseStatus === 'private'"
                            variant="outline">
                            {{ t('dialog.world.tags.private') }}
                        </Badge>
                        <TooltipWrapper v-if="currentInstanceWorld.isPC" side="top" content="PC">
                            <Badge class="text-platform-pc border-platform-pc! mr-1.5" variant="outline"
                                ><Monitor class="h-4 w-4" />
                                <span
                                    v-if="currentInstanceWorld.fileAnalysis.standalonewindows?._fileSize"
                                    class="x-grey text-platform-pc border-l-[0.8px] border-solid ml-1.5 pl-1.5 pb-px"
                                    >{{ currentInstanceWorld.fileAnalysis.standalonewindows._fileSize }}</span
                                >
                            </Badge>
                        </TooltipWrapper>
                        <TooltipWrapper v-if="currentInstanceWorld.isQuest" side="top" content="Android">
                            <Badge class="text-platform-quest border-platform-quest! mr-1.5" variant="outline"
                                ><Smartphone class="h-4 w-4" />
                                <span
                                    v-if="currentInstanceWorld.fileAnalysis.android?._fileSize"
                                    class="x-grey text-platform-quest border-l-[0.8px] border-solid ml-1.5 pl-1.5 pb-px"
                                    >{{ currentInstanceWorld.fileAnalysis.android._fileSize }}</span
                                >
                            </Badge>
                        </TooltipWrapper>
                        <TooltipWrapper v-if="currentInstanceWorld.isIos" side="top" content="iOS">
                            <Badge class="text-platform-ios border-platform-ios mr-1.5" variant="outline"
                                ><Apple class="h-4 w-4 text-platform-ios" />
                                <span
                                    v-if="currentInstanceWorld.fileAnalysis.ios?._fileSize"
                                    class="x-grey text-platform-ios border-platform-ios border-l-[0.8px] border-solid ml-1.5 pl-1.5 pb-px"
                                    >{{ currentInstanceWorld.fileAnalysis.ios._fileSize }}</span
                                >
                            </Badge>
                        </TooltipWrapper>
                        <Badge
                            class="mr-1.5 mt-1.5"
                            v-if="currentInstanceWorld.avatarScalingDisabled"
                            variant="outline">
                            {{ t('dialog.world.tags.avatar_scaling_disabled') }}
                        </Badge>
                        <Badge class="mr-1.5" v-if="currentInstanceWorld.inCache" variant="outline">
                            <span>{{ currentInstanceWorld.cacheSize }} {{ t('dialog.world.tags.cache') }}</span>
                        </Badge>
                    </div>
                    <div class="mt-1.5 compact:col-span-2 compact:mt-2">
                        <LocationWorld
                            :locationobject="currentInstanceLocation"
                            :currentuserid="currentUser.id"
                            class="w-fit" />
                        <span class="ml-1.5" v-if="lastLocation.playerList.size > 0">
                            {{ lastLocation.playerList.size }}
                            <template v-if="lastLocation.friendList.size > 0"
                                >({{ lastLocation.friendList.size }})</template
                            >
                            &nbsp;&horbar; <Timer v-if="lastLocation.date" :epoch="lastLocation.date" />
                        </span>
                    </div>
                    <div class="mt-1.5 compact:col-span-2">
                        <span
                            v-show="currentInstanceWorld.ref.name !== currentInstanceWorld.ref.description"
                            class="inline-block max-w-full align-middle text-xs break-words compact:line-clamp-3"
                            v-text="currentInstanceWorld.ref.description"></span>
                    </div>
                </div>
                <div
                    class="ml-5 compact:col-span-2 compact:ml-0 compact:mt-1.5 compact:grid compact:grid-cols-3 compact:rounded-md compact:border"
                    :style="isCompact ? null : { display: 'flex', flexDirection: 'column' }">
                    <div class="box-border flex items-center p-1.5 text-[13px] cursor-default">
                        <div class="flex-1 overflow-hidden">
                            <span class="block truncate font-medium leading-[18px]">{{
                                t('dialog.world.info.capacity')
                            }}</span>
                            <span class="block truncate text-xs compact:whitespace-normal compact:break-words"
                                >{{ commaNumber(currentInstanceWorld.ref.recommendedCapacity) }} ({{
                                    commaNumber(currentInstanceWorld.ref.capacity)
                                }})</span
                            >
                        </div>
                    </div>
                    <div class="box-border flex items-center p-1.5 text-[13px] cursor-default">
                        <div class="flex-1 overflow-hidden">
                            <span class="block truncate font-medium leading-[18px]">{{
                                t('dialog.world.info.last_updated')
                            }}</span>
                            <span class="block truncate text-xs compact:whitespace-normal compact:break-words">{{
                                formatDateFilter(
                                    currentInstanceWorld.fileAnalysis.standalonewindows?.created_at,
                                    'long'
                                )
                            }}</span>
                        </div>
                    </div>
                    <div class="box-border flex items-center p-1.5 text-[13px] cursor-default">
                        <div class="flex-1 overflow-hidden">
                            <span class="block truncate font-medium leading-[18px]">{{
                                t('dialog.world.info.created')
                            }}</span>
                            <span class="block truncate text-xs compact:whitespace-normal compact:break-words">{{
                                formatDateFilter(currentInstanceWorld.ref.created_at, 'long')
                            }}</span>
                        </div>
                    </div>
                </div>
            </div>

            <div class="mb-2" v-if="photonLoggingEnabled" ref="playerListPhotonRef">
                <PhotonEventTable @show-chatbox-blacklist="showChatboxBlacklistDialog" />
            </div>

            <!-- Phones: the header scrolls away with the list instead of squeezing it, and the cards take the full
                 width (without auto-height the table would shrink to its content inside this flex row). -->
            <div
                class="current-instance-table flex min-h-0 min-w-0 flex-1 compact:block compact:flex-none"
                data-testid="player-list-table">
                <DataTableLayout
                    class="[&_th]:px-2.5! [&_th]:py-0.75! [&_td]:px-2.5! [&_td]:py-0.75! [&_tr]:h-7!"
                    :table="playerListTable"
                    :auto-height="!isCompact"
                    :loading="false"
                    :show-pagination="false"
                    :on-row-click="handlePlayerListRowClick">
                    <!-- Phones: what the icons mean (PC explains them in hover tooltips). -->
                    <template v-if="isCompact && iconLegend.length" #toolbar>
                        <div
                            class="flex min-h-8 w-full min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground"
                            data-testid="player-icon-legend">
                            <span v-for="entry in iconLegend" :key="entry.key" class="inline-flex items-center gap-1">
                                <IdCard v-if="entry.key === 'ageVerified'" class="size-3.5 x-tag-age-verification" />
                                <span v-else>{{ entry.icon }}</span>
                                {{ t(entry.labelKey) }}
                            </span>
                        </div>
                    </template>
                    <template v-if="isAndroid" #empty>
                        <CompanionEmptyState kind="playerList" />
                    </template>
                </DataTableLayout>
            </div>
        </div>
        <ChatboxBlacklistDialog
            :chatbox-blacklist-dialog="chatboxBlacklistDialog"
            @delete-chatbox-user-blacklist="deleteChatboxUserBlacklist" />
    </div>
</template>

<script setup>
    import { computed, defineAsyncComponent, onActivated, onMounted, ref, watch } from 'vue';
    import { Apple, Home, IdCard, Image, Monitor, Smartphone } from 'lucide-vue-next';
    import { storeToRefs } from 'pinia';
    import { useI18n } from 'vue-i18n';

    import {
        useAppearanceSettingsStore,
        useGalleryStore,
        useInstanceStore,
        useLocationStore,
        usePhotonStore,
        useUserStore
    } from '../../stores';
    import { commaNumber, formatDateFilter } from '../../shared/utils';
    import { Badge } from '../../components/ui/badge';
    import { DataTableLayout } from '../../components/ui/data-table';
    import { createColumns } from './columns.jsx';
    import { useVrcxVueTable } from '../../lib/table/useVrcxVueTable';

    import ChatboxBlacklistDialog from './dialogs/ChatboxBlacklistDialog.vue';
    import Timer from '../../components/Timer.vue';
    import { showUserDialog, lookupUser } from '../../coordinators/userCoordinator';
    import { showWorldDialog } from '../../coordinators/worldCoordinator';

    import PhotonEventTable from './components/PhotonEventTable.vue';
    import { useUserDisplay } from '../../composables/useUserDisplay';
    import { isAndroid } from '../../shared/utils/platform';
    import { useCompactLayout } from '../../composables/useCompactLayout';
    import { getPlayerIconLegend } from './playerIconLegend';

    const CompanionEmptyState = isAndroid
        ? defineAsyncComponent(() => import('../../platform/android/components/CompanionEmptyState.vue'))
        : null;

    const { randomUserColours } = storeToRefs(useAppearanceSettingsStore());
    const { userImage } = useUserDisplay();
    const photonStore = usePhotonStore();
    const { photonLoggingEnabled, chatboxUserBlacklist } = storeToRefs(photonStore);
    const { saveChatboxUserBlacklist } = photonStore;

    const { lastLocation } = storeToRefs(useLocationStore());
    const { currentInstanceLocation, currentInstanceWorld, currentInstanceUsersData } = storeToRefs(useInstanceStore());

    const worldImageError = ref(false);

    const { isCompact } = useCompactLayout();
    // Phones: a smaller thumbnail beside the name; PC keeps upstream's 160x120.
    const worldImageStyle = computed(() =>
        isCompact.value
            ? { width: '120px', height: '90px', borderRadius: 'var(--radius-md)' }
            : { flex: 'none', width: '160px', height: '120px', borderRadius: 'var(--radius-md)' }
    );
    const iconLegend = computed(() => (isCompact.value ? getPlayerIconLegend(currentInstanceUsersData.value) : []));

    watch(
        () => currentInstanceWorld.value?.ref?.id,
        () => {
            worldImageError.value = false;
        }
    );
    const { getCurrentInstanceUserList } = useInstanceStore();
    const { showFullscreenImageDialog } = useGalleryStore();
    const { currentUser } = storeToRefs(useUserStore());

    const playerListRef = ref(null);
    const playerListHeaderRef = ref(null);
    const playerListPhotonRef = ref(null);

    const { t } = useI18n();

    const chatboxBlacklistDialog = ref({
        visible: false,
        loading: false
    });

    function showChatboxBlacklistDialog() {
        const D = chatboxBlacklistDialog.value;
        D.visible = true;
    }

    /**
     * @param val
     */
    function selectCurrentInstanceRow(val) {
        if (val === null) {
            return;
        }
        const ref = val.ref;
        if (ref.id) {
            showUserDialog(ref.id);
        } else {
            lookupUser(ref);
        }
    }

    /**
     * @param userId
     */
    async function deleteChatboxUserBlacklist(userId) {
        chatboxUserBlacklist.value.delete(userId);
        await saveChatboxUserBlacklist();
        getCurrentInstanceUserList();
    }

    /**
     * @param user
     */
    async function addChatboxUserBlacklist(user) {
        chatboxUserBlacklist.value.set(user.id, user.displayName);
        await saveChatboxUserBlacklist();
        getCurrentInstanceUserList();
    }

    /**
     * @param a
     * @param b
     * @param field
     */
    function sortAlphabetically(a, b, field) {
        if (!a[field] || !b[field]) return 0;
        return a[field].toLowerCase().localeCompare(b[field].toLowerCase());
    }

    const playerListColumns = computed(() =>
        createColumns({
            randomUserColours,
            chatboxUserBlacklist,
            onBlockChatbox: addChatboxUserBlacklist,
            onUnblockChatbox: deleteChatboxUserBlacklist,
            sortAlphabetically,
            userImage
        })
    );

    const { table: playerListTable } = useVrcxVueTable({
        persistKey: 'playerList',
        get data() {
            return currentInstanceUsersData.value;
        },
        columns: playerListColumns,
        enablePagination: false,
        getRowId: (row) => `${row?.ref?.id ?? ''}:${row?.displayName ?? ''}`
    });

    watch(
        playerListColumns,
        (next) => {
            playerListTable.setOptions((prev) => ({
                ...prev,
                columns: next
            }));
        },
        { immediate: true }
    );

    watch(
        photonLoggingEnabled,
        (enabled) => {
            const column = playerListTable?.getColumn?.('photonId');
            if (!column) {
                return;
            }
            column.toggleVisibility(Boolean(enabled));
        },
        { immediate: true }
    );

    const playerListTotalItems = computed(() => playerListTable.getRowModel().rows.length);

    const handlePlayerListRowClick = (row) => {
        selectCurrentInstanceRow(row?.original ?? null);
    };

    onMounted(() => {
        getCurrentInstanceUserList();
    });

    onActivated(() => {
        getCurrentInstanceUserList();
    });
</script>

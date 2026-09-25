<template>
    <ContextMenu>
        <ContextMenuTrigger as-child>
            <Item
                variant="outline"
                class="favorites-item cursor-pointer hover:bg-muted x-hover-list compact:relative pointer-coarse:select-none pointer-coarse:[-webkit-touch-callout:none]"
                :style="itemStyle"
                @click="handleViewDetails">
                <ItemMedia variant="image">
                    <Avatar class="rounded-sm size-full">
                        <AvatarImage
                            v-if="smallThumbnail"
                            :src="smallThumbnail"
                            loading="lazy"
                            decoding="async"
                            fetchpriority="low"
                            class="rounded-sm object-cover" />
                        <AvatarFallback class="rounded-sm">
                            <Image class="size-4 text-muted-foreground" />
                        </AvatarFallback>
                    </Avatar>
                </ItemMedia>
                <ItemContent class="min-w-0">
                    <ItemTitle class="truncate max-w-full">
                        <!-- Phones: the name ellipsizes and the status icons stay visible. -->
                        <span class="compact:min-w-0 compact:truncate">{{ displayName }}</span>
                        <AlertTriangle
                            v-if="showUnavailable"
                            :title="t('view.favorite.unavailable_tooltip')"
                            class="h-4 w-4" />
                        <Lock v-if="isPrivateWorld" :title="t('view.favorite.private')" class="h-4 w-4" />
                    </ItemTitle>
                    <ItemDescription class="truncate line-clamp-1 text-xs compact:max-w-[calc(100%-1.75rem)]">
                        {{ authorText }}
                    </ItemDescription>
                </ItemContent>
                <ItemActions v-if="editMode && !isLocalFavorite" @click.stop>
                    <Checkbox v-model="isSelected" />
                </ItemActions>
                <DropdownMenu v-else-if="!editMode">
                    <DropdownMenuTrigger as-child>
                        <Button
                            size="icon-sm"
                            variant="ghost"
                            class="rounded-full compact:absolute compact:bottom-1 compact:right-1 compact:size-7"
                            @click.stop
                            :ariaLabel="t('nav_tooltip.manage')">
                            <MoreHorizontal class="h-4 w-4" />
                        </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                        <WorldActionMenuItems
                            variant="dropdown"
                            :can-open-instance-in-game="canOpenInstanceInGame"
                            @view-details="handleViewDetails"
                            @new-instance="handleNewInstance"
                            @self-invite="handleSelfInvite">
                            <template #append>
                                <DropdownMenuSeparator />
                                <DropdownMenuItem @click="showFavoriteDialog('world', favorite.id)">
                                    {{ t('view.favorite.edit_favorite_tooltip') }}
                                </DropdownMenuItem>
                                <DropdownMenuItem variant="destructive" @click="handleDeleteFavorite">
                                    {{ deleteMenuLabel }}
                                </DropdownMenuItem>
                            </template>
                        </WorldActionMenuItems>
                    </DropdownMenuContent>
                </DropdownMenu>
            </Item>
        </ContextMenuTrigger>
        <ContextMenuContent>
            <WorldActionMenuItems
                :can-open-instance-in-game="canOpenInstanceInGame"
                @view-details="handleViewDetails"
                @new-instance="handleNewInstance"
                @self-invite="handleSelfInvite">
                <template #append>
                    <ContextMenuSeparator />
                    <ContextMenuItem @click="showFavoriteDialog('world', favorite.id)">
                        {{ t('view.favorite.edit_favorite_tooltip') }}
                    </ContextMenuItem>
                    <ContextMenuItem variant="destructive" @click="handleDeleteFavorite">
                        {{ deleteMenuLabel }}
                    </ContextMenuItem>
                </template>
            </WorldActionMenuItems>
        </ContextMenuContent>
    </ContextMenu>
    <NewInstanceDialog
        v-if="newInstanceDialogMounted"
        :new-instance-dialog-location-tag="newInstanceDialogLocationTag"
        :last-location="lastLocation" />
</template>

<script setup>
    import { AlertTriangle, Image, Lock, MoreHorizontal } from 'lucide-vue-next';
    import { Button } from '@/components/ui/button';
    import { Checkbox } from '@/components/ui/checkbox';
    import {
        ContextMenu,
        ContextMenuContent,
        ContextMenuItem,
        ContextMenuSeparator,
        ContextMenuTrigger
    } from '@/components/ui/context-menu';
    import {
        DropdownMenu,
        DropdownMenuContent,
        DropdownMenuItem,
        DropdownMenuSeparator,
        DropdownMenuTrigger
    } from '@/components/ui/dropdown-menu';
    import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
    import { Item, ItemActions, ItemContent, ItemDescription, ItemMedia, ItemTitle } from '@/components/ui/item';
    import { computed, nextTick, ref } from 'vue';
    import { useI18n } from 'vue-i18n';
    import { storeToRefs } from 'pinia';

    import { favoriteRequest } from '../../../api';
    import WorldActionMenuItems from '../../../components/WorldActionMenuItems.vue';
    import { removeLocalWorldFavorite } from '../../../coordinators/favoriteCoordinator';
    import { runNewInstanceSelfInviteFlow as newInstanceSelfInvite } from '../../../coordinators/inviteCoordinator';
    import { showWorldDialog } from '../../../coordinators/worldCoordinator';
    import { useFavoriteStore, useInviteStore, useLocationStore } from '../../../stores';
    import NewInstanceDialog from '../../../components/dialogs/NewInstanceDialog/NewInstanceDialog.vue';
    import { isAndroid } from '../../../shared/utils/platform';

    const props = defineProps({
        group: [Object, String],
        favorite: Object,
        isLocalFavorite: { type: Boolean, default: false },
        editMode: { type: Boolean, default: false },
        selected: { type: Boolean, default: false }
    });

    const emit = defineEmits(['toggle-select']);
    const { showFavoriteDialog } = useFavoriteStore();
    const { lastLocation } = storeToRefs(useLocationStore());

    const { t } = useI18n();
    const { canOpenInstanceInGame } = useInviteStore();

    const newInstanceDialogLocationTag = ref('');
    // Android: every card used to mount its own New Instance dialog (and read its settings) up front; phones mount
    // it on first use instead. Desktop keeps the upstream behaviour.
    const newInstanceDialogMounted = ref(!isAndroid);

    const isSelected = computed({
        get: () => props.selected,
        set: (value) => emit('toggle-select', value)
    });

    const localFavRef = computed(() => (props.isLocalFavorite ? props.favorite : props.favorite?.ref));

    const displayName = computed(() => localFavRef.value?.name || props.favorite?.name || props.favorite?.id);

    const showUnavailable = computed(() => !props.isLocalFavorite && props.favorite?.deleted);

    const isPrivateWorld = computed(() => localFavRef.value?.releaseStatus === 'private');

    const authorText = computed(() => {
        const author = localFavRef.value?.authorName || '';
        const occupants = localFavRef.value?.occupants;
        return occupants ? `${author} (${occupants})` : author;
    });

    const smallThumbnail = computed(() => {
        const url = localFavRef.value?.thumbnailImageUrl?.replace('256', '128');
        return url || localFavRef.value?.thumbnailImageUrl;
    });

    const deleteMenuLabel = computed(() =>
        props.isLocalFavorite ? t('view.favorite.delete_tooltip') : t('view.favorite.unfavorite_tooltip')
    );

    const itemStyle = computed(() => ({
        padding: 'var(--favorites-card-padding-y, 8px) var(--favorites-card-padding-x, 10px)',
        gap: 'var(--favorites-card-content-gap, 10px)',
        minWidth: 'var(--favorites-card-min-width, 220px)',
        maxWidth: 'var(--favorites-card-target-width, 220px)',
        width: '100%',
        fontSize: 'calc(0.875rem * var(--favorites-card-scale, 1))'
    }));

    function handleViewDetails() {
        showWorldDialog(props.favorite.id);
    }

    function handleNewInstance() {
        newInstanceDialogMounted.value = true;
        newInstanceDialogLocationTag.value = '';
        nextTick(() => (newInstanceDialogLocationTag.value = props.favorite.id));
    }

    function handleSelfInvite() {
        newInstanceSelfInvite(props.favorite.id);
    }

    function handleDeleteFavorite() {
        if (props.isLocalFavorite) {
            removeLocalWorldFavorite(props.favorite.id, props.group);
            return;
        }
        favoriteRequest.deleteFavorite({ objectId: props.favorite.id });
    }
</script>

<style scoped>
    .favorites-item :deep(img) {
        filter: saturate(0.8) contrast(0.8);
        transition: filter 0.2s ease;
    }

    .favorites-item:hover :deep(img) {
        filter: saturate(1) contrast(1);
    }

    /* Touch screens have no hover to lift the dimming, so thumbnails show in full colour. */
    @media (hover: none) {
        .favorites-item :deep(img) {
            filter: none;
        }
    }
</style>

<script setup>
    import {
        Breadcrumb,
        BreadcrumbEllipsis,
        BreadcrumbItem,
        BreadcrumbLink,
        BreadcrumbList,
        BreadcrumbPage,
        BreadcrumbSeparator
    } from '@/components/ui/breadcrumb';
    import {
        useAvatarStore,
        useGroupStore,
        useInstanceStore,
        useUiStore,
        useUserStore,
        useWorldStore,
        useAppearanceSettingsStore
    } from '@/stores';
    import {
        DropdownMenu,
        DropdownMenuContent,
        DropdownMenuItem,
        DropdownMenuTrigger
    } from '@/components/ui/dropdown-menu';
    import { Dialog, DialogContent } from '@/components/ui/dialog';
    import { ArrowLeft, ChevronDown, X } from 'lucide-vue-next';
    import { Button } from '@/components/ui/button';
    import { TooltipWrapper } from '@/components/ui/tooltip';
    import { computed, ref } from 'vue';
    import { storeToRefs } from 'pinia';
    import { useI18n } from 'vue-i18n';
    import { useCompactLayout } from '@/composables/useCompactLayout';
    import { isAndroid } from '@/shared/utils/platform';
    import { handleMainDialogEscape } from '@/platform/android/shell/backHandler';

    import AvatarDialog from './AvatarDialog/AvatarDialog.vue';
    import GroupDialog from './GroupDialog/GroupDialog.vue';
    import PreviousInstancesInfoDialog from './PreviousInstancesDialog/PreviousInstancesInfoDialog.vue';
    import PreviousInstancesListDialog from './PreviousInstancesDialog/PreviousInstancesListDialog.vue';
    import UserDialog from './UserDialog/UserDialog.vue';
    import WorldDialog from './WorldDialog/WorldDialog.vue';
    import GroupMemberModerationDialog from './GroupDialog/GroupMemberModerationDialog.vue';
    import { getReadableProfileThemeColor } from '@/shared/utils/user';
    import { profileBackgrounds } from '@/shared/constants/backgrounds';

    const avatarStore = useAvatarStore();
    const groupStore = useGroupStore();
    const instanceStore = useInstanceStore();
    const uiStore = useUiStore();
    const userStore = useUserStore();
    const worldStore = useWorldStore();
    const appearanceSettingsStore = useAppearanceSettingsStore();

    const { previousInstancesInfoDialog, previousInstancesListDialog } = storeToRefs(instanceStore);

    const { t } = useI18n();
    // Phones: a full-screen page with a 48px app bar and one scroller (docs/DESIGN.md §3.2). Always false on desktop.
    const { isCompact } = useCompactLayout();
    // Marks the entity dialog host on Android (tests and the preview harness look it up).
    const mainDialogMarker = isAndroid ? '' : undefined;

    // Android back button: reka hands the back press's Escape to this dialog only when it is the top layer; with more
    // than one crumb it steps back instead of closing (docs/DESIGN.md §6, platform/android/shell/backHandler.js).
    function handleEscapeKeyDown(event) {
        if (isAndroid) {
            handleMainDialogEscape(event, uiStore);
        }
    }

    const previousIds = ref({
        userDialog: {
            mutualFriend: null,
            group: null,
            avatar: null,
            world: null,
            favoriteWorld: null
        }
    });

    function updateUserPreviousId(key, value) {
        previousIds.value.userDialog[key] = value;
    }

    const dialogCrumbs = computed(() => uiStore.dialogCrumbs);
    const activeType = computed(() => {
        const type = (() => {
            if (previousInstancesInfoDialog.value.visible) {
                return 'previous-instances-info';
            }
            if (previousInstancesListDialog.value.visible) {
                return `previous-instances-${previousInstancesListDialog.value.variant}`;
            }
            if (userStore.userDialog.visible) {
                return 'user';
            }
            if (worldStore.worldDialog.visible) {
                return 'world';
            }
            if (avatarStore.avatarDialog.visible) {
                return 'avatar';
            }
            if (groupStore.groupDialog.visible) {
                return 'group';
            }
            if (groupStore.groupMemberModeration.visible) {
                return 'group-member-moderation';
            }
            return null;
        })();
        return type;
    });
    const activeComponent = computed(() => {
        switch (activeType.value) {
            case 'user':
                return UserDialog;
            case 'world':
                return WorldDialog;
            case 'avatar':
                return AvatarDialog;
            case 'group':
                return GroupDialog;
            case 'previous-instances-info':
                return PreviousInstancesInfoDialog;
            case 'previous-instances-user':
                return PreviousInstancesListDialog;
            case 'previous-instances-world':
                return PreviousInstancesListDialog;
            case 'previous-instances-group':
                return PreviousInstancesListDialog;
            case 'group-member-moderation':
                return GroupMemberModerationDialog;
            default:
                return null;
        }
    });
    const activeComponentProps = computed(() => {
        switch (activeType.value) {
            case 'user':
                return { previousIds: previousIds.value.userDialog, updatePreviousId: updateUserPreviousId };
            case 'previous-instances-user':
                return { variant: 'user' };
            case 'previous-instances-world':
                return { variant: 'world' };
            case 'previous-instances-group':
                return { variant: 'group' };
            default:
                return {};
        }
    });
    const isOpen = computed({
        get: () => activeComponent.value !== null,
        set: (value) => {
            if (!value) {
                uiStore.closeMainDialog();
            }
        }
    });

    const dialogClass = computed(() => {
        if (isCompact.value) {
            return 'x-dialog flex flex-col gap-0 overflow-hidden p-0';
        }
        switch (activeType.value) {
            case 'user':
            case 'group':
            case 'world':
            case 'avatar':
                return 'x-dialog translate-y-0 sm:max-w-270 overflow-hidden flex flex-col';
            case 'group-member-moderation':
                return 'x-dialog translate-y-0 max-w-none flex flex-col sm:min-w-[90vw] sm:max-w-[90vw] sm:min-h-[80vh] sm:max-h-[80vh]';
            case 'previous-instances-info':
            case 'previous-instances-user':
            case 'previous-instances-world':
            case 'previous-instances-group':
                return 'x-dialog translate-y-0 sm:max-w-250';
            default:
                return 'x-dialog translate-y-0 sm:max-w-235 overflow-hidden flex flex-col';
        }
    });

    const shouldShowBreadcrumbs = computed(() => dialogCrumbs.value.length > 1);
    const shouldCollapseBreadcrumbs = computed(() => dialogCrumbs.value.length > 5);
    const middleBreadcrumbs = computed(() => {
        if (!shouldCollapseBreadcrumbs.value) {
            return [];
        }
        return dialogCrumbs.value.slice(1, -2);
    });
    const backCrumbLabel = computed(() => {
        if (dialogCrumbs.value.length < 2) {
            return '';
        }
        const backCrumb = dialogCrumbs.value[dialogCrumbs.value.length - 2];
        return backCrumb?.label || backCrumb?.id || '';
    });

    function handleBreadcrumbClick(index) {
        uiStore.handleBreadcrumbClick(index);
    }

    const currentCrumbLabel = computed(() => {
        const current = dialogCrumbs.value[dialogCrumbs.value.length - 1];
        return current?.label || current?.id || '';
    });

    // Compact app bar: back steps through the crumb history and closes the dialog after the first crumb.
    function handleCompactBack() {
        uiStore.jumpBackDialogCrumb();
    }

    function handleCompactClose() {
        uiStore.closeMainDialog();
    }

    const dialogStyle = computed(() => {
        if (activeType.value !== 'user' || !appearanceSettingsStore.displayVRCProfileBackgrounds) {
            return {};
        }

        const userDialogBaseStyle = {
            overflow: 'hidden',
            backgroundClip: 'padding-box'
        };

        const opacity = -appearanceSettingsStore.profileBackgroundOpacity + 1; // Invert the opacity value
        const textureOverlay = appearanceSettingsStore.isDarkMode
            ? `rgba(0, 0, 0, ${opacity})`
            : `rgba(255, 255, 255, ${opacity})`;
        if (userStore.userDialog.publicProfileRef?.backgroundType === 'gradient') {
            const bgTopColor = getReadableProfileThemeColor(
                `#${userStore.userDialog.publicProfileRef?.backgroundGradientTop}`,
                'var(--background)',
                !appearanceSettingsStore.isDarkMode
            );
            const bgBottomColor = getReadableProfileThemeColor(
                `#${userStore.userDialog.publicProfileRef?.backgroundGradientBottom}`,
                'var(--background)',
                !appearanceSettingsStore.isDarkMode
            );
            return {
                ...userDialogBaseStyle,
                backgroundImage: `linear-gradient(${textureOverlay}, ${textureOverlay}), linear-gradient(180deg, ${bgTopColor}, ${bgBottomColor})`
            };
        }
        if (userStore.userDialog.publicProfileRef?.backgroundType === 'texture') {
            const bg = profileBackgrounds.find(
                (b) => b.id === userStore.userDialog.publicProfileRef?.backgroundTextureId
            );
            if (!bg) {
                return userDialogBaseStyle;
            }
            return {
                ...userDialogBaseStyle,
                backgroundImage: `linear-gradient(${textureOverlay}, ${textureOverlay}), url(${bg.url})`,
                backgroundSize: 'cover',
                backgroundPosition: 'top center',
                backgroundRepeat: 'no-repeat'
            };
        }
        return userDialogBaseStyle;
    });
</script>

<template>
    <Dialog v-if="isOpen" v-model:open="isOpen">
        <DialogContent
            :class="dialogClass"
            style="top: 10vh"
            :show-close-button="false"
            :style="dialogStyle"
            :data-vrcx-main-dialog="mainDialogMarker"
            :data-mobile="isCompact ? 'bare' : undefined"
            @escape-key-down="handleEscapeKeyDown">
            <!-- Phone: dialog app bar with back (crumb history), the current crumb and close. -->
            <header
                v-if="isCompact"
                class="vrcx-dialog-app-bar flex shrink-0 items-center gap-1 border-b border-border bg-(--profile-card) px-1"
                data-slot="dialog-app-bar">
                <Button
                    variant="ghost"
                    size="icon"
                    class="size-10 shrink-0 rounded-full"
                    :aria-label="t('android.shell.dialog.back')"
                    @click="handleCompactBack">
                    <ArrowLeft class="size-5" />
                </Button>
                <DropdownMenu v-if="dialogCrumbs.length > 1">
                    <DropdownMenuTrigger as-child>
                        <button
                            type="button"
                            class="flex h-10 min-w-0 flex-1 cursor-pointer items-center gap-1 rounded-md px-1 text-left outline-none focus-visible:ring-2 focus-visible:ring-ring"
                            :aria-label="t('android.shell.dialog.history')">
                            <span class="truncate text-base font-semibold">{{ currentCrumbLabel }}</span>
                            <ChevronDown class="size-4 shrink-0 opacity-60" />
                        </button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="start" class="w-64 max-w-[calc(100vw-2rem)]">
                        <DropdownMenuItem
                            v-for="(crumb, index) in dialogCrumbs"
                            :key="`${crumb.type}-${crumb.id}`"
                            :disabled="index === dialogCrumbs.length - 1"
                            @click="handleBreadcrumbClick(index)">
                            <span class="truncate">{{ crumb.label || crumb.id }}</span>
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
                <span v-else class="min-w-0 flex-1 truncate px-1 text-base font-semibold">{{ currentCrumbLabel }}</span>
                <Button
                    variant="ghost"
                    size="icon"
                    class="size-10 shrink-0 rounded-full"
                    :aria-label="t('android.shell.dialog.close')"
                    @click="handleCompactClose">
                    <X class="size-5" />
                </Button>
            </header>
            <Breadcrumb
                v-if="shouldShowBreadcrumbs && !isCompact"
                class="mb-2 flex-shrink-0 rounded-xl bg-(--profile-card) w-fit pr-4">
                <BreadcrumbList>
                    <TooltipWrapper :content="backCrumbLabel" :disabled="!backCrumbLabel" :delayDuration="500">
                        <Button variant="ghost" size="icon-sm" @click="handleBreadcrumbClick(dialogCrumbs.length - 2)">
                            <ArrowLeft />
                            <span class="sr-only">{{ backCrumbLabel }}</span>
                        </Button>
                    </TooltipWrapper>
                    <template v-if="shouldCollapseBreadcrumbs">
                        <BreadcrumbItem>
                            <TooltipWrapper
                                :content="dialogCrumbs[0]?.label || dialogCrumbs[0]?.id"
                                :delayDuration="500">
                                <BreadcrumbLink as-child>
                                    <Button
                                        type="button"
                                        variant="ghost"
                                        size="sm"
                                        class="max-w-40 justify-start truncate text-left"
                                        @click="handleBreadcrumbClick(0)">
                                        {{ dialogCrumbs[0]?.label || dialogCrumbs[0]?.id }}
                                    </Button>
                                </BreadcrumbLink>
                            </TooltipWrapper>
                        </BreadcrumbItem>
                        <BreadcrumbSeparator />
                        <BreadcrumbItem>
                            <DropdownMenu>
                                <DropdownMenuTrigger class="flex items-center gap-1">
                                    <BreadcrumbEllipsis class="h-4 w-4" />
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="start">
                                    <DropdownMenuItem
                                        v-for="(crumb, index) in middleBreadcrumbs"
                                        :key="`${crumb.type}-${crumb.id}`"
                                        @click="handleBreadcrumbClick(index + 1)">
                                        {{ crumb.label || crumb.id }}
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenu>
                        </BreadcrumbItem>
                        <BreadcrumbSeparator />
                        <BreadcrumbItem>
                            <TooltipWrapper
                                :content="
                                    dialogCrumbs[dialogCrumbs.length - 2]?.label ||
                                    dialogCrumbs[dialogCrumbs.length - 2]?.id
                                "
                                :delayDuration="500">
                                <BreadcrumbLink as-child>
                                    <Button
                                        type="button"
                                        variant="ghost"
                                        size="sm"
                                        class="max-w-40 justify-start truncate text-left"
                                        @click="handleBreadcrumbClick(dialogCrumbs.length - 2)">
                                        {{
                                            dialogCrumbs[dialogCrumbs.length - 2]?.label ||
                                            dialogCrumbs[dialogCrumbs.length - 2]?.id
                                        }}
                                    </Button>
                                </BreadcrumbLink>
                            </TooltipWrapper>
                        </BreadcrumbItem>
                        <BreadcrumbSeparator />
                        <BreadcrumbItem>
                            <BreadcrumbPage class="max-w-40 truncate">
                                {{
                                    dialogCrumbs[dialogCrumbs.length - 1]?.label ||
                                    dialogCrumbs[dialogCrumbs.length - 1]?.id
                                }}
                            </BreadcrumbPage>
                        </BreadcrumbItem>
                    </template>
                    <template v-else>
                        <template v-for="(crumb, index) in dialogCrumbs" :key="`${crumb.type}-${crumb.id}`">
                            <BreadcrumbItem>
                                <TooltipWrapper
                                    v-if="index < dialogCrumbs.length - 1"
                                    :content="crumb.label || crumb.id"
                                    :delayDuration="500">
                                    <BreadcrumbLink as-child>
                                        <Button
                                            type="button"
                                            variant="ghost"
                                            size="sm"
                                            class="max-w-40 justify-start truncate text-left"
                                            @click="handleBreadcrumbClick(index)">
                                            {{ crumb.label || crumb.id }}
                                        </Button>
                                    </BreadcrumbLink>
                                </TooltipWrapper>
                                <BreadcrumbPage v-else class="max-w-40 truncate">
                                    {{ crumb.label || crumb.id }}
                                </BreadcrumbPage>
                            </BreadcrumbItem>
                            <BreadcrumbSeparator v-if="index < dialogCrumbs.length - 1" />
                        </template>
                    </template>
                </BreadcrumbList>
            </Breadcrumb>

            <!-- Phone: one scroller for the whole page. The entity dialogs stack their rail and tabs inside it. -->
            <div
                v-if="isCompact"
                class="vrcx-main-dialog-body flex min-h-0 flex-1 flex-col overflow-y-auto overscroll-contain p-3"
                data-slot="main-dialog-scroller">
                <component
                    :is="activeComponent"
                    v-if="activeComponent"
                    v-bind="activeComponentProps"
                    :key="activeType" />
            </div>
            <component
                :is="activeComponent"
                v-else-if="activeComponent"
                v-bind="activeComponentProps"
                :key="activeType" />
        </DialogContent>
    </Dialog>
</template>

<style scoped>
    .vrcx-dialog-app-bar {
        height: calc(48px + var(--safe-top, 0px));
        padding-top: var(--safe-top, 0px);
        padding-left: calc(4px + var(--safe-left, 0px));
        padding-right: calc(4px + var(--safe-right, 0px));
    }

    .vrcx-main-dialog-body {
        padding-left: calc(12px + var(--safe-left, 0px));
        padding-right: calc(12px + var(--safe-right, 0px));
        padding-bottom: calc(12px + var(--vrcx-bottom-inset, 0px));
    }
</style>

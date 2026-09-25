<template>
    <component :is="itemComponent" v-if="shows('view')" @click="handleViewDetails">
        <ExternalLink class="size-4" />
        {{ t('common.actions.view_details') }}
    </component>
    <component :is="separatorComponent" v-if="shows('view') && hasInviteGroup" />
    <component :is="itemComponent" v-if="shows('request-invite') && isOnline" @click="handleRequestInvite">
        <Mail class="size-4" />
        {{ t('dialog.user.actions.request_invite') }}
        <component :is="shortcutComponent" v-if="showRecentRequestInvite">
            <Clock class="size-3.5 text-muted-foreground" />
        </component>
    </component>
    <component
        :is="itemComponent"
        v-if="shows('invite') && isGameRunning"
        :disabled="!canInviteToMyLocation"
        @click="handleInvite">
        <MessageSquare class="size-4" />
        {{ t('dialog.user.actions.invite') }}
        <component :is="shortcutComponent" v-if="showRecentInvite">
            <Clock class="size-3.5 text-muted-foreground" />
        </component>
    </component>
    <component
        :is="itemComponent"
        v-if="shows('boop')"
        :disabled="!currentUser?.isBoopingEnabled"
        @click="handleSendBoop">
        <Hand class="size-4" />
        {{ t('dialog.user.actions.send_boop') }}
    </component>
    <component :is="separatorComponent" v-if="hasJoinGroup && (hasInviteGroup || shows('view') || separatorBefore)" />
    <component
        :is="itemComponent"
        v-if="shows('join') && isOnline && hasLocation"
        :disabled="!canJoin"
        @click="handleJoin">
        <LogIn class="size-4" />
        {{ t('dialog.user.info.launch_invite_tooltip') }}
    </component>
    <component
        :is="itemComponent"
        v-if="shows('self-invite') && isOnline && hasLocation"
        :disabled="!canJoin"
        @click="handleSelfInvite">
        <Mail class="size-4" />
        {{ t('dialog.user.info.self_invite_tooltip') }}
    </component>
</template>

<script>
    /** Every item, in menu order. */
    export const USER_ACTION_ITEMS = Object.freeze(['view', 'request-invite', 'invite', 'boop', 'join', 'self-invite']);
</script>

<script setup>
    // The quick actions for a user (the PC right-click menu on user rows), shared by the long-press context menu
    // (UserContextMenu, variant "context") and the touch kebab (UserActionMenuButton, variant "dropdown"), so both
    // offer exactly the same items (docs/DESIGN.md §3.3).
    import { Clock, ExternalLink, Hand, LogIn, Mail, MessageSquare } from 'lucide-vue-next';
    import { computed } from 'vue';
    import { storeToRefs } from 'pinia';
    import { toast } from 'vue-sonner';
    import { useI18n } from 'vue-i18n';

    import { ContextMenuItem, ContextMenuSeparator, ContextMenuShortcut } from './ui/context-menu';
    import { DropdownMenuItem, DropdownMenuSeparator, DropdownMenuShortcut } from './ui/dropdown-menu';
    import { isRealInstance, parseLocation } from '../shared/utils';
    import { useGameStore, useLaunchStore, useLocationStore, useUserStore } from '../stores';
    import { instanceRequest, notificationRequest, queryRequest } from '../api';
    import { useInviteChecks } from '../composables/useInviteChecks';
    import { isActionRecent, recordRecentAction } from '../composables/useRecentActions';

    import { showUserDialog } from '../coordinators/userCoordinator';

    const props = defineProps({
        userId: {
            type: String,
            required: true
        },
        state: {
            type: String,
            default: ''
        },
        location: {
            type: String,
            default: ''
        },
        // 'context' renders ContextMenu items, 'dropdown' renders DropdownMenu items.
        variant: {
            type: String,
            default: 'context',
            validator: (value) => ['context', 'dropdown'].includes(value)
        },
        // Subset of USER_ACTION_ITEMS to show (all by default).
        items: {
            type: Array,
            default: () => USER_ACTION_ITEMS
        },
        // Draw a separator above the join group even when it is the first group (appending to an existing menu).
        separatorBefore: {
            type: Boolean,
            default: false
        }
    });

    const { t } = useI18n();
    const { showSendBoopDialog } = useUserStore();
    const launchStore = useLaunchStore();
    const { lastLocation, lastLocationDestination } = storeToRefs(useLocationStore());
    const { isGameRunning } = storeToRefs(useGameStore());
    const { currentUser } = storeToRefs(useUserStore());
    const { checkCanInvite, checkCanInviteSelf } = useInviteChecks();

    const itemComponent = computed(() => (props.variant === 'dropdown' ? DropdownMenuItem : ContextMenuItem));
    const separatorComponent = computed(() =>
        props.variant === 'dropdown' ? DropdownMenuSeparator : ContextMenuSeparator
    );
    const shortcutComponent = computed(() =>
        props.variant === 'dropdown' ? DropdownMenuShortcut : ContextMenuShortcut
    );

    const shows = (key) => props.items.includes(key);

    const isOnline = computed(() => props.state === 'online');
    const hasLocation = computed(() => !!props.location && isRealInstance(props.location));
    const canInviteToMyLocation = computed(() => checkCanInvite(lastLocation.value.location));
    const canJoin = computed(() => {
        if (!props.location || !isRealInstance(props.location)) return false;
        return checkCanInviteSelf(props.location);
    });

    // The request-invite / invite / boop group always has Send boop when it is listed.
    const hasInviteGroup = computed(
        () => shows('boop') || (shows('request-invite') && isOnline.value) || (shows('invite') && isGameRunning.value)
    );
    const hasJoinGroup = computed(() => (shows('join') || shows('self-invite')) && isOnline.value && hasLocation.value);

    const showRecentRequestInvite = computed(() => isActionRecent(props.userId, 'Request Invite'));
    const showRecentInvite = computed(() => isActionRecent(props.userId, 'Invite'));

    function handleViewDetails() {
        showUserDialog(props.userId);
    }

    function handleRequestInvite() {
        notificationRequest.sendRequestInvite({ platform: 'standalonewindows' }, props.userId).then(() => {
            recordRecentAction(props.userId, 'Request Invite');
            toast.success(t('message.user.request_invite_sent'));
        });
    }

    function handleInvite() {
        let currentLocation = lastLocation.value.location;
        if (currentLocation === 'traveling') {
            currentLocation = lastLocationDestination.value;
        }
        const L = parseLocation(currentLocation);
        queryRequest.fetch('world.location', { worldId: L.worldId }).then((args) => {
            notificationRequest
                .sendInvite(
                    {
                        instanceId: L.tag,
                        worldId: L.tag,
                        worldName: args.ref.name
                    },
                    props.userId
                )
                .then(() => {
                    recordRecentAction(props.userId, 'Invite');
                    toast.success(t('message.invite.sent'));
                });
        });
    }

    function handleSendBoop() {
        showSendBoopDialog(props.userId);
    }

    function handleJoin() {
        if (!props.location) return;
        launchStore.showLaunchDialog(props.location);
    }

    function handleSelfInvite() {
        if (!props.location) return;
        const L = parseLocation(props.location);
        instanceRequest
            .selfInvite({
                instanceId: L.instanceId,
                worldId: L.worldId
            })
            .then(() => {
                toast.success(t('message.invite.self_sent'));
            });
    }
</script>

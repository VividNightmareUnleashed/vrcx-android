<template>
    <div ref="compactScrollRootRef" class="flex-1 min-h-0 flex flex-col compact:flex-[1_0_auto]">
        <DialogHeader class="sr-only">
            <DialogTitle>{{ t('dialog.group_member_moderation.header') }}</DialogTitle>
        </DialogHeader>

        <div class="flex-1 min-h-0 flex flex-col compact:flex-[1_0_auto]">
            <h3>{{ groupMemberModeration.groupRef.name }}</h3>
            <TabsUnderline
                v-model="groupMemberModeration.activeTab"
                class="compact:mb-3"
                @update:modelValue="tabClick"
                default-value="members"
                :items="groupModerationTabs"
                :unmount-on-hide="false">
                <template #members>
                    <GroupModerationMembersTab
                        :loading="isGroupMembersLoading"
                        :table-data="tables.members"
                        :group-ref="groupMemberModeration.groupRef"
                        :member-sort-order="memberSortOrder"
                        :member-filter="memberFilter"
                        :member-search="memberSearch"
                        :sorting-options="groupDialogSortingOptions"
                        :filter-options="groupDialogFilterOptions"
                        :page-sizes="pageSizes"
                        :column-context="membersColumnContext"
                        :handle-page-change="(val) => (tables.members.pageIndex = Math.max(0, val - 1))"
                        @refresh="loadAllGroupMembers"
                        @update:member-search="memberSearch = $event"
                        @search="groupMembersSearch"
                        @sort-change="setGroupMemberSortOrder"
                        @filter-change="setGroupMemberFilter"
                        @select-all="selectAll" />
                </template>

                <template #bans>
                    <GroupModerationBansTab
                        :loading="isGroupMembersLoading"
                        :table-data="tables.bans"
                        :group-ref="groupMemberModeration.groupRef"
                        :page-sizes="pageSizes"
                        :column-context="bansColumnContext"
                        :handle-page-change="(val) => (tables.bans.pageIndex = Math.max(0, val - 1))"
                        @refresh="getAllGroupBans(groupMemberModeration.id)"
                        @select-all="selectAll"
                        @export="isGroupBansExportDialogVisible = true"
                        @import="isGroupBansImportDialogVisible = true" />
                </template>

                <template #invites>
                    <GroupModerationInvitesTab
                        :loading="isGroupMembersLoading"
                        :invites-table="tables.invites"
                        :join-requests-table="tables.joinRequests"
                        :blocked-table="tables.blocked"
                        :group-ref="groupMemberModeration.groupRef"
                        :progress-current="progressCurrent"
                        :page-sizes="pageSizes"
                        :column-context="invitesColumnContext"
                        :handle-page-change="(val) => (tables.invites.pageIndex = Math.max(0, val - 1))"
                        @refresh="getAllGroupInvitesAndJoinRequests(groupMemberModeration.id)"
                        @select-all="selectAll"
                        @delete-sent-invite="handleDeleteSentInvite"
                        @accept-invite-request="handleAcceptInviteRequest"
                        @reject-invite-request="handleRejectInviteRequest"
                        @block-join-request="handleBlockJoinRequest"
                        @delete-blocked-request="handleDeleteBlockedRequest" />
                </template>

                <template #logs>
                    <GroupModerationLogsTab
                        ref="logsTabRef"
                        :loading="isGroupMembersLoading"
                        :table-data="tables.logs"
                        :audit-log-types="groupMemberModeration.auditLogTypes"
                        :page-sizes="pageSizes"
                        :column-context="logsColumnContext"
                        :handle-page-change="(val) => (tables.logs.pageIndex = Math.max(0, val - 1))"
                        @refresh="handleLogsRefresh"
                        @export="isGroupLogsExportDialogVisible = true" />
                </template>
            </TabsUnderline>

            <template v-if="!isCompact">
                <br />
                <br />
                <GroupModerationBulkActions v-bind="bulkActionsProps" v-on="bulkActionsListeners" />
            </template>
            <div
                v-else
                class="vrcx-moderation-actions-bar sticky z-40 flex items-center gap-2 border-t border-border bg-background"
                data-slot="moderation-actions-bar">
                <!-- Phones: the bulk actions live in a bottom sheet, opened from this bar, which stays at the bottom of
                     the page while the tables scroll. -->
                <span class="min-w-0 truncate text-sm">{{ t('dialog.group_member_moderation.selected_users') }}</span>
                <Badge variant="secondary" class="shrink-0" data-slot="moderation-selected-count">{{
                    groupMemberModeration.selectedUsersArray.length
                }}</Badge>
                <span v-if="progressCurrent" class="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
                    <Spinner />
                    {{ progressCurrent }}/{{ progressTotal }}
                </span>
                <Button class="ml-auto h-10 shrink-0" @click="isBulkActionsSheetOpen = true">{{
                    t('dialog.group_member_moderation.actions')
                }}</Button>
            </div>
            <Sheet v-if="isCompact" v-model:open="isBulkActionsSheetOpen">
                <!-- Above the entity dialog (the modal portal root is z-10000), below floating content (z-12000) so
                     the roles select still opens on top. -->
                <SheetContent side="bottom" class="z-[10001] gap-0 rounded-t-lg p-0" overlay-class="z-[10001]">
                    <SheetHeader class="border-b pb-3">
                        <SheetTitle>{{ t('dialog.group_member_moderation.actions') }}</SheetTitle>
                        <SheetDescription class="sr-only">{{
                            t('dialog.group_member_moderation.header')
                        }}</SheetDescription>
                    </SheetHeader>
                    <div class="overflow-y-auto p-4">
                        <GroupModerationBulkActions v-bind="bulkActionsProps" v-on="bulkActionsListeners" />
                    </div>
                </SheetContent>
            </Sheet>
        </div>

        <group-member-moderation-export-dialog
            v-model:isGroupLogsExportDialogVisible="isGroupLogsExportDialogVisible"
            :group-logs-moderation-table="tables.logs" />

        <group-member-moderation-ban-export-dialog
            v-model:isGroupBansExportDialogVisible="isGroupBansExportDialogVisible"
            :group-bans-moderation-table="tables.bans" />

        <group-member-moderation-ban-import-dialog
            v-model:isGroupBansImportDialogVisible="isGroupBansImportDialogVisible"
            :group-id="groupMemberModeration.id"
            @imported="getAllGroupBans(groupMemberModeration.id)" />
    </div>
</template>

<script setup>
    import { DialogHeader, DialogTitle } from '@/components/ui/dialog';
    import { computed, ref, watch } from 'vue';
    import { TabsUnderline } from '@/components/ui/tabs';
    import { Badge } from '@/components/ui/badge';
    import { Button } from '@/components/ui/button';
    import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet';
    import { Spinner } from '@/components/ui/spinner';
    import { storeToRefs } from 'pinia';
    import { useI18n } from 'vue-i18n';

    import { useAppearanceSettingsStore, useGalleryStore, useGroupStore, useUserStore } from '../../../stores';
    import {
        applyGroupMember,
        handleGroupMember,
        handleGroupMemberProps
    } from '../../../coordinators/groupCoordinator';
    import { hasGroupPermission } from '../../../shared/utils';
    import { useUserDisplay } from '../../../composables/useUserDisplay';
    import { groupDialogFilterOptions, groupDialogSortingOptions } from '../../../shared/constants';
    import { groupRequest } from '../../../api';
    import { resolveRoleNames } from './groupModerationUtils';
    import { useGroupBatchOperations } from './useGroupBatchOperations';
    import { useGroupModerationData } from './useGroupModerationData';
    import { useGroupModerationSelection } from './useGroupModerationSelection';

    import GroupMemberModerationBanExportDialog from './GroupMemberModerationBanExportDialog.vue';
    import GroupMemberModerationBanImportDialog from './GroupMemberModerationBanImportDialog.vue';
    import GroupMemberModerationExportDialog from './GroupMemberModerationExportDialog.vue';
    import GroupModerationBansTab from './GroupModerationBansTab.vue';
    import GroupModerationBulkActions from './GroupModerationBulkActions.vue';
    import GroupModerationInvitesTab from './GroupModerationInvitesTab.vue';
    import GroupModerationLogsTab from './GroupModerationLogsTab.vue';
    import GroupModerationMembersTab from './GroupModerationMembersTab.vue';
    import { showUserDialog } from '../../../coordinators/userCoordinator';
    import { useCompactDialogScrollReset } from '../useEntityDialogCompact';

    // ── Stores ───────────────────────────────────────────────────
    const { userImage, userImageFull } = useUserDisplay();
    const appearanceSettingsStore = useAppearanceSettingsStore();
    const { randomUserColours } = storeToRefs(appearanceSettingsStore);

    const { currentUser } = storeToRefs(useUserStore());
    const { groupDialog, groupMemberModeration } = storeToRefs(useGroupStore());
    const { showFullscreenImageDialog } = useGalleryStore();
    const { t } = useI18n();

    // Phones: opens at the top of the dialog scroller (it is usually reached from a scrolled Group dialog).
    const compactScrollRootRef = ref(null);
    const { isCompact } = useCompactDialogScrollReset(compactScrollRootRef);
    const isBulkActionsSheetOpen = ref(false);

    // ── Tab definitions ──────────────────────────────────────────
    const groupModerationTabs = computed(() => [
        { value: 'members', label: t('dialog.group_member_moderation.members') },
        {
            value: 'bans',
            label: t('dialog.group_member_moderation.bans'),
            disabled: !hasGroupPermission(groupMemberModeration.value?.groupRef, 'group-bans-manage')
        },
        {
            value: 'invites',
            label: t('dialog.group_member_moderation.invites'),
            disabled: !hasGroupPermission(groupMemberModeration.value?.groupRef, 'group-invites-manage')
        },
        {
            value: 'logs',
            label: t('dialog.group_member_moderation.logs'),
            disabled: !hasGroupPermission(groupMemberModeration.value?.groupRef, 'group-audit-view')
        }
    ]);

    const pageSizes = computed(() => appearanceSettingsStore.tablePageSizes);

    // ── Table data ───────────────────────────────────────────────
    const tables = groupMemberModeration.value.tables;

    // ── Selection ────────────────────────────────────────────────
    const { setSelectedUsers, deselectedUsers, onSelectionChange, deleteSelectedUser, clearAllSelected, selectAll } =
        useGroupModerationSelection(groupMemberModeration.value);

    // ── Column contexts ──────────────────────────────────────────
    const rolesText = (roleIds) => resolveRoleNames(roleIds, groupMemberModeration.value?.groupRef?.roles ?? []);

    const membersColumnContext = computed(() => ({
        randomUserColours,
        rolesText,
        userImage,
        userImageFull,
        onShowFullscreenImage: showFullscreenImageDialog,
        onShowUser: showUserDialog,
        onSelectionChange
    }));

    const bansColumnContext = computed(() => ({
        randomUserColours,
        rolesText,
        userImage,
        userImageFull,
        onShowFullscreenImage: showFullscreenImageDialog,
        onShowUser: showUserDialog,
        onSelectionChange
    }));

    const invitesColumnContext = computed(() => ({
        randomUserColours,
        userImage,
        userImageFull,
        onShowFullscreenImage: showFullscreenImageDialog,
        onShowUser: showUserDialog,
        onSelectionChange
    }));

    const logsColumnContext = computed(() => ({
        onShowUser: showUserDialog
    }));

    // ── Data fetching ────────────────────────────────────────────
    const {
        isGroupMembersLoading,
        memberFilter,
        memberSortOrder,
        memberSearch,
        loadAllGroupMembers,
        setGroupMemberSortOrder,
        setGroupMemberFilter,
        groupMembersSearch,
        selectGroupMemberUserId,
        addGroupMemberToSelection,
        getAllGroupBans,
        getAllGroupLogs,
        getAllGroupInvitesAndJoinRequests
    } = useGroupModerationData({
        groupMemberModeration,
        currentUser,
        applyGroupMember,
        handleGroupMember,
        tables,
        selection: { selectedUsers: groupMemberModeration.value.selectedUsers, setSelectedUsers },
        groupRequest
    });

    // ── Batch operations ─────────────────────────────────────────
    /**
     * @param args
     */
    function handleGroupMemberRoleChange(args) {
        if (groupDialog.value.id === args.params.groupId) {
            groupDialog.value.members.forEach((member) => {
                if (member.userId === args.params.userId) {
                    member.roleIds = args.json;
                    return true;
                }
            });
        }
    }

    const {
        progressCurrent,
        progressTotal,
        groupMembersBan,
        groupMembersUnban,
        groupMembersKick,
        groupMembersSaveNote,
        groupMembersRemoveRoles,
        groupMembersAddRoles,
        groupMembersDeleteSentInvite,
        groupMembersAcceptInviteRequest,
        groupMembersRejectInviteRequest,
        groupMembersBlockJoinRequest,
        groupMembersDeleteBlockedRequest
    } = useGroupBatchOperations({
        currentUser,
        groupMemberModeration,
        deselectedUsers,
        groupRequest,
        handleGroupMemberRoleChange,
        handleGroupMemberProps
    });

    // ── Local state ──────────────────────────────────────────────
    const selectUserId = ref('');
    const selectedRoles = ref([]);
    const note = ref('');
    const isGroupLogsExportDialogVisible = ref(false);
    const isGroupBansExportDialogVisible = ref(false);
    const isGroupBansImportDialogVisible = ref(false);
    const logsTabRef = ref(null);

    // ── Bulk actions (inline on PC, in a bottom sheet on phones) ─
    const bulkActionsProps = computed(() => ({
        selectUserId: selectUserId.value,
        selectedUsersArray: groupMemberModeration.value.selectedUsersArray,
        selectedRoles: selectedRoles.value,
        note: note.value,
        progressCurrent: progressCurrent.value,
        progressTotal: progressTotal.value,
        groupRef: groupMemberModeration.value.groupRef
    }));
    const bulkActionsListeners = {
        'update:selectUserId': (value) => (selectUserId.value = value),
        'update:note': (value) => (note.value = value),
        'update:selectedRoles': (value) => (selectedRoles.value = value),
        'select-user': () => handleSelectUser(),
        'clear-all': () => clearAllSelected(),
        'delete-user': (user) => deleteSelectedUser(user),
        'add-roles': () => handleAddRoles(),
        'remove-roles': () => handleRemoveRoles(),
        'save-note': () => handleSaveNote(),
        kick: () => handleKick(),
        ban: () => handleBan(),
        unban: () => handleUnban(),
        'cancel-progress': () => (progressTotal.value = 0)
    };

    // ── Event handlers ───────────────────────────────────────────
    function handleBan() {
        groupMembersBan({ onComplete: () => getAllGroupBans(groupMemberModeration.value.id) });
    }
    function handleUnban() {
        groupMembersUnban({ onComplete: () => getAllGroupBans(groupMemberModeration.value.id) });
    }
    function handleKick() {
        groupMembersKick({ onComplete: () => loadAllGroupMembers() });
    }
    function handleSaveNote() {
        groupMembersSaveNote(note.value);
    }
    function handleAddRoles() {
        groupMembersAddRoles(selectedRoles.value);
    }
    function handleRemoveRoles() {
        groupMembersRemoveRoles(selectedRoles.value);
    }
    function handleDeleteSentInvite() {
        groupMembersDeleteSentInvite({
            onComplete: () => getAllGroupInvitesAndJoinRequests(groupMemberModeration.value.id)
        });
    }
    function handleAcceptInviteRequest() {
        groupMembersAcceptInviteRequest({
            onComplete: () => getAllGroupInvitesAndJoinRequests(groupMemberModeration.value.id)
        });
    }
    function handleRejectInviteRequest() {
        groupMembersRejectInviteRequest({
            onComplete: () => getAllGroupInvitesAndJoinRequests(groupMemberModeration.value.id)
        });
    }
    function handleBlockJoinRequest() {
        groupMembersBlockJoinRequest({
            onComplete: () => getAllGroupInvitesAndJoinRequests(groupMemberModeration.value.id)
        });
    }
    function handleDeleteBlockedRequest() {
        groupMembersDeleteBlockedRequest({
            onComplete: () => getAllGroupInvitesAndJoinRequests(groupMemberModeration.value.id)
        });
    }
    async function handleSelectUser() {
        await selectGroupMemberUserId(selectUserId.value);
        selectUserId.value = '';
    }
    function handleLogsRefresh() {
        const eventTypes = logsTabRef.value?.selectedAuditLogTypes ?? [];
        getAllGroupLogs(groupMemberModeration.value.id, eventTypes);
    }

    function tabClick(newTab) {
        groupMemberModeration.value.activeTab = newTab;
    }

    // ── Dialog open watcher ──────────────────────────────────────
    watch(
        () => groupMemberModeration.value.visible,
        (newVal) => {
            if (newVal) {
                if (groupMemberModeration.value.openWithUserId) {
                    addGroupMemberToSelection(groupMemberModeration.value.openWithUserId);
                }
            }
        },
        { immediate: true }
    );
</script>

<style scoped>
    /* Phones: the bar spans the dialog scroller edge to edge and sits on the screen's bottom edge, above the gesture
       bar, cancelling MainDialogContainer's scroller padding (12px plus the safe areas). */
    .vrcx-moderation-actions-bar {
        bottom: calc(-12px - var(--vrcx-bottom-inset, 0px));
        margin: auto calc(-12px - var(--safe-right, 0px)) calc(-12px - var(--vrcx-bottom-inset, 0px))
            calc(-12px - var(--safe-left, 0px));
        padding: 8px calc(12px + var(--safe-right, 0px)) calc(8px + var(--vrcx-bottom-inset, 0px))
            calc(12px + var(--safe-left, 0px));
    }
</style>

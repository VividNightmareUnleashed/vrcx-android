import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick, ref } from 'vue';

// Phones: the bulk moderation actions (roles, note, kick, ban, unban) move into
// a bottom sheet opened from a bar that stays at the bottom of the page; the PC keeps them inline under the tables.

const mocks = vi.hoisted(() => ({
    isCompact: null,
    moderation: null,
    groupMembersKick: vi.fn(),
    groupMembersBan: vi.fn(),
    clearAllSelected: vi.fn(),
    selectGroupMemberUserId: vi.fn(() => Promise.resolve()),
    stub: (name) => ({ default: { name, template: `<div data-stub="${name}" />` } })
}));

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));
vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));
vi.mock('../../useEntityDialogCompact', () => ({
    useCompactDialogScrollReset: () => ({ isCompact: mocks.isCompact })
}));
vi.mock('../../../../stores', () => ({
    useAppearanceSettingsStore: () => ({ randomUserColours: ref(false), tablePageSizes: [15, 25] }),
    useGalleryStore: () => ({ showFullscreenImageDialog: vi.fn() }),
    useGroupStore: () => ({ groupDialog: ref({ id: '', members: [] }), groupMemberModeration: mocks.moderation }),
    useUserStore: () => ({ currentUser: ref({ id: 'usr_me' }) })
}));
vi.mock('../../../../coordinators/groupCoordinator', () => ({
    applyGroupMember: vi.fn(),
    handleGroupMember: vi.fn(),
    handleGroupMemberProps: vi.fn()
}));
vi.mock('../../../../coordinators/userCoordinator', () => ({ showUserDialog: vi.fn() }));
vi.mock('../../../../composables/useUserDisplay', () => ({
    useUserDisplay: () => ({ userImage: vi.fn(), userImageFull: vi.fn() })
}));
vi.mock('../../../../shared/utils', () => ({ hasGroupPermission: () => true }));
vi.mock('../../../../api', () => ({ groupRequest: {} }));
vi.mock('../useGroupModerationSelection', () => ({
    useGroupModerationSelection: () => ({
        setSelectedUsers: vi.fn(),
        deselectedUsers: vi.fn(),
        onSelectionChange: vi.fn(),
        deleteSelectedUser: vi.fn(),
        clearAllSelected: (...args) => mocks.clearAllSelected(...args),
        selectAll: vi.fn()
    })
}));
vi.mock('../useGroupModerationData', () => ({
    useGroupModerationData: () => ({
        isGroupMembersLoading: ref(false),
        memberFilter: ref({ id: null }),
        memberSortOrder: ref({}),
        memberSearch: ref(''),
        loadAllGroupMembers: vi.fn(),
        setGroupMemberSortOrder: vi.fn(),
        setGroupMemberFilter: vi.fn(),
        groupMembersSearch: vi.fn(),
        selectGroupMemberUserId: (...args) => mocks.selectGroupMemberUserId(...args),
        addGroupMemberToSelection: vi.fn(),
        getAllGroupBans: vi.fn(),
        getAllGroupLogs: vi.fn(),
        getAllGroupInvitesAndJoinRequests: vi.fn()
    })
}));
vi.mock('../useGroupBatchOperations', () => ({
    useGroupBatchOperations: () => ({
        progressCurrent: ref(0),
        progressTotal: ref(0),
        groupMembersBan: (...args) => mocks.groupMembersBan(...args),
        groupMembersUnban: vi.fn(),
        groupMembersKick: (...args) => mocks.groupMembersKick(...args),
        groupMembersSaveNote: vi.fn(),
        groupMembersRemoveRoles: vi.fn(),
        groupMembersAddRoles: vi.fn(),
        groupMembersDeleteSentInvite: vi.fn(),
        groupMembersAcceptInviteRequest: vi.fn(),
        groupMembersRejectInviteRequest: vi.fn(),
        groupMembersBlockJoinRequest: vi.fn(),
        groupMembersDeleteBlockedRequest: vi.fn()
    })
}));

vi.mock('../GroupModerationMembersTab.vue', () => mocks.stub('members-tab'));
vi.mock('../GroupModerationBansTab.vue', () => mocks.stub('bans-tab'));
vi.mock('../GroupModerationInvitesTab.vue', () => mocks.stub('invites-tab'));
vi.mock('../GroupModerationLogsTab.vue', () => mocks.stub('logs-tab'));
vi.mock('../GroupMemberModerationExportDialog.vue', () => mocks.stub('export-dialog'));
vi.mock('../GroupMemberModerationBanExportDialog.vue', () => mocks.stub('ban-export-dialog'));
vi.mock('../GroupMemberModerationBanImportDialog.vue', () => mocks.stub('ban-import-dialog'));
vi.mock('../GroupModerationBulkActions.vue', () => ({
    default: {
        name: 'GroupModerationBulkActions',
        props: ['selectUserId', 'selectedUsersArray', 'progressCurrent', 'groupRef'],
        emits: ['kick', 'ban', 'clear-all', 'select-user', 'update:selectUserId'],
        template: `<div data-stub="bulk-actions" :data-selected="selectedUsersArray.length" :data-user-id="selectUserId">
            <button data-action="kick" @click="$emit('kick')" />
            <button data-action="ban" @click="$emit('ban')" />
            <button data-action="clear" @click="$emit('clear-all')" />
            <button data-action="type-id" @click="$emit('update:selectUserId', 'usr_typed')" />
            <button data-action="select-user" @click="$emit('select-user')" />
        </div>`
    }
}));
vi.mock('@/components/ui/tabs', () => ({
    TabsUnderline: { template: '<div data-stub="tabs"><slot name="members" /></div>' }
}));
vi.mock('@/components/ui/dialog', () => ({
    DialogHeader: { template: '<div><slot /></div>' },
    DialogTitle: { template: '<div><slot /></div>' }
}));
vi.mock('@/components/ui/sheet', () => ({
    Sheet: {
        props: ['open'],
        emits: ['update:open'],
        template: '<div data-stub="sheet" :data-open="String(open)"><slot v-if="open" /></div>'
    },
    SheetContent: { template: '<div data-stub="sheet-content"><slot /></div>' },
    SheetHeader: { template: '<div><slot /></div>' },
    SheetTitle: { template: '<div><slot /></div>' },
    SheetDescription: { template: '<div><slot /></div>' }
}));

import GroupMemberModerationDialog from '../GroupMemberModerationDialog.vue';

function moderationState() {
    return ref({
        id: 'grp_00000000-0000-4000-8000-00000000c001',
        visible: true,
        openWithUserId: '',
        activeTab: 'members',
        groupRef: { name: 'Harbor Lights', roles: [] },
        auditLogTypes: [],
        selectedUsers: {},
        selectedUsersArray: [{ id: 'usr_a' }, { id: 'usr_b' }],
        tables: {
            members: { data: [] },
            bans: { data: [] },
            invites: { data: [] },
            joinRequests: { data: [] },
            blocked: { data: [] },
            logs: { data: [] }
        }
    });
}

describe('GroupMemberModerationDialog.vue bulk actions', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mocks.moderation = moderationState();
    });

    it('keeps the PC layout: the actions sit inline under the tables, with no bar or sheet', () => {
        mocks.isCompact = ref(false);
        const wrapper = mount(GroupMemberModerationDialog);

        expect(wrapper.findAll('[data-stub="bulk-actions"]')).toHaveLength(1);
        expect(wrapper.find('[data-slot="moderation-actions-bar"]').exists()).toBe(false);
        expect(wrapper.find('[data-stub="sheet"]').exists()).toBe(false);
    });

    it('shows a bar with the selection count on phones and opens the actions in a bottom sheet', async () => {
        mocks.isCompact = ref(true);
        const wrapper = mount(GroupMemberModerationDialog);

        const bar = wrapper.find('[data-slot="moderation-actions-bar"]');
        expect(bar.exists()).toBe(true);
        expect(bar.classes()).toContain('sticky');
        expect(wrapper.find('[data-slot="moderation-selected-count"]').text()).toBe('2');
        expect(wrapper.find('[data-stub="bulk-actions"]').exists()).toBe(false);

        await bar.find('button').trigger('click');
        await nextTick();

        expect(wrapper.find('[data-stub="sheet"]').attributes('data-open')).toBe('true');
        const actions = wrapper.find('[data-stub="sheet-content"] [data-stub="bulk-actions"]');
        expect(actions.exists()).toBe(true);
        expect(actions.attributes('data-selected')).toBe('2');
    });

    it('runs the same handlers from the sheet as from the PC section', async () => {
        mocks.isCompact = ref(true);
        const wrapper = mount(GroupMemberModerationDialog);
        await wrapper.find('[data-slot="moderation-actions-bar"] button').trigger('click');
        await nextTick();
        const actions = () => wrapper.find('[data-stub="sheet-content"] [data-stub="bulk-actions"]');

        await actions().find('[data-action="kick"]').trigger('click');
        await actions().find('[data-action="ban"]').trigger('click');
        await actions().find('[data-action="clear"]').trigger('click');
        await actions().find('[data-action="type-id"]').trigger('click');
        await nextTick();
        expect(actions().attributes('data-user-id')).toBe('usr_typed');
        await actions().find('[data-action="select-user"]').trigger('click');

        expect(mocks.groupMembersKick).toHaveBeenCalledTimes(1);
        expect(mocks.groupMembersBan).toHaveBeenCalledTimes(1);
        expect(mocks.clearAllSelected).toHaveBeenCalledTimes(1);
        expect(mocks.selectGroupMemberUserId).toHaveBeenCalledWith('usr_typed');
    });
});

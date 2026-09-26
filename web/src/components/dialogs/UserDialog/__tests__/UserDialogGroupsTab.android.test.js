import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import { userDialogGroupSortingOptions } from '../../../../shared/constants';

// Android: the in-game group order lives in VRChat's registry on the PC, so the
// user dialog's Groups tab has no "In-game" sorting, no reorder buttons in edit mode and no "hold Shift" hint, and the
// current user's groups sort by name.

const mocks = vi.hoisted(() => ({
    userDialog: null,
    currentUserGroups: null,
    updateInGameGroupOrder: vi.fn(),
    getGroups: vi.fn(),
    showGroupDialog: vi.fn(),
    isCoarsePointer: null
}));

vi.mock('../../../../shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true,
    hasLocalGame: false,
    hasLocalVrchatFiles: false
}));
vi.mock('../../../../composables/useCompactLayout', () => ({
    useCompactLayout: () => ({ isCompact: ref(false), isCoarsePointer: mocks.isCoarsePointer })
}));
vi.mock('pinia', async (i) => ({ ...(await i()), storeToRefs: (s) => s }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k) => k }) }));
vi.mock('vue-sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));
vi.mock('../../../../stores', () => ({
    useUserStore: () => ({
        userDialog: mocks.userDialog,
        currentUser: ref({ id: 'usr_me' }),
        isLocalUserVrcPlusSupporter: ref(false)
    }),
    useGroupStore: () => ({
        currentUserGroups: mocks.currentUserGroups,
        inGameGroupOrder: ref([]),
        showCreateGroupDialog: vi.fn()
    }),
    useAuthStore: () => ({ cachedConfig: ref({}) }),
    useUiStore: () => ({ shiftHeld: ref(false) })
}));
vi.mock('../../../../coordinators/groupCoordinator', () => ({
    showGroupDialog: (...args) => mocks.showGroupDialog(...args),
    applyGroup: (group) => group,
    saveCurrentUserGroups: vi.fn(),
    updateInGameGroupOrder: mocks.updateInGameGroupOrder,
    isSoleGroupOwner: () => false,
    leaveGroup: vi.fn(),
    leaveGroupPrompt: vi.fn(),
    setGroupVisibility: vi.fn(),
    handleGroupList: vi.fn()
}));
vi.mock('../../../../shared/utils', () => ({
    compareByName: (a, b) => String(a.name).localeCompare(String(b.name)),
    compareByMemberCount: () => 0
}));
vi.mock('../../../../api', () => ({ groupRequest: { getGroups: mocks.getGroups } }));
vi.mock('@/components/ui/select', () => ({
    Select: { template: '<div><slot /></div>' },
    SelectTrigger: { template: '<div><slot /></div>' },
    SelectValue: { template: '<span />' },
    SelectContent: { template: '<div><slot /></div>' },
    SelectItem: {
        props: ['value', 'disabled'],
        template: '<div data-testid="select-item" :data-value="value"><slot /></div>'
    }
}));
vi.mock('@/components/ui/button', () => ({
    Button: {
        emits: ['click'],
        template: '<button data-testid="btn" @click="$emit(\'click\')"><slot /></button>'
    }
}));
vi.mock('../UserDialogGroupCard.vue', () => ({ default: { template: '<div data-testid="group-card" />' } }));
vi.mock('@/components/ui/quick-actions', () => ({
    QuickActionsToggle: { template: '<button data-testid="quick-actions">quick actions</button>' }
}));

import UserDialogGroupsTab from '../UserDialogGroupsTab.vue';

function emptyGroups() {
    return { groups: [], ownGroups: [], mutualGroups: [], remainingGroups: [] };
}

describe('UserDialogGroupsTab.vue on Android', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mocks.isCoarsePointer = ref(false);
        mocks.userDialog = ref({
            id: 'usr_me',
            isGroupsLoading: false,
            userGroups: emptyGroups(),
            groupSorting: userDialogGroupSortingOptions.alphabetical
        });
        mocks.currentUserGroups = ref(
            new Map([
                ['grp_b', { id: 'grp_b', name: 'Beta', myMember: { visibility: 'visible' } }],
                ['grp_a', { id: 'grp_a', name: 'Alpha', myMember: { visibility: 'visible' } }]
            ])
        );
        mocks.getGroups.mockResolvedValue({
            json: [
                { id: 'grp_b', name: 'Beta', ownerId: 'usr_other' },
                { id: 'grp_a', name: 'Alpha', ownerId: 'usr_other' }
            ]
        });
    });

    it('has no quick actions toggle outside edit mode', () => {
        const wrapper = mount(UserDialogGroupsTab);

        expect(wrapper.find('[data-testid="quick-actions"]').exists()).toBe(false);
    });

    it('does not offer the in-game sort order', () => {
        const wrapper = mount(UserDialogGroupsTab);
        const values = wrapper.findAll('[data-testid="select-item"]').map((item) => item.attributes('data-value'));

        expect(values).toContain('alphabetical');
        expect(values).toContain('members');
        expect(values).not.toContain('inGame');
        expect(wrapper.text()).not.toContain('dialog.user.groups.sorting.in_game');
    });

    it('edit mode has no reorder buttons and no Shift hint', async () => {
        const wrapper = mount(UserDialogGroupsTab);
        const editButton = wrapper.findAll('button').find((b) => b.text() === 'dialog.user.groups.edit_mode');
        await editButton.trigger('click');
        await flushPromises();

        expect(wrapper.text()).toContain('dialog.user.groups.exit_edit_mode');
        expect(wrapper.text()).toContain('Alpha');
        expect(wrapper.text()).not.toContain('dialog.user.groups.hold_shift');
        // Instead of holding Shift, the quick actions toggle makes leave/delete instant (docs/DESIGN.md §3.3).
        expect(wrapper.find('[data-testid="quick-actions"]').exists()).toBe(true);
        // The reorder buttons (move to top/bottom, up/down) are gone.
        expect(wrapper.find('.lucide-arrow-up').exists()).toBe(false);
        expect(wrapper.find('.lucide-arrow-down').exists()).toBe(false);
        expect(wrapper.find('.lucide-download').exists()).toBe(false);
    });

    it("sorts the current user's groups by name instead of the in-game order", async () => {
        mocks.userDialog.value.groupSorting = userDialogGroupSortingOptions.members;
        const wrapper = mount(UserDialogGroupsTab);
        await wrapper.vm.getUserGroups('usr_me');
        await flushPromises();

        expect(mocks.userDialog.value.groupSorting.value).toBe('alphabetical');
        expect(mocks.userDialog.value.userGroups.remainingGroups.map((g) => g.name)).toEqual(['Alpha', 'Beta']);
        expect(mocks.updateInGameGroupOrder).not.toHaveBeenCalled();
    });

    describe('edit mode selection on touch', () => {
        async function openEditMode() {
            const wrapper = mount(UserDialogGroupsTab, { attachTo: document.body });
            const editButton = wrapper.findAll('button').find((b) => b.text() === 'dialog.user.groups.edit_mode');
            await editButton.trigger('click');
            await flushPromises();
            return wrapper;
        }

        function alphaRow(wrapper) {
            return wrapper.findAll('[data-slot="group-select-area"]')[0];
        }

        it('selects the group from a tap next to its checkbox instead of opening the group', async () => {
            mocks.isCoarsePointer = ref(true);
            const wrapper = await openEditMode();
            const area = alphaRow(wrapper);
            const checkbox = area.find('[role="checkbox"]');
            expect(area.classes()).toContain('pointer-coarse:size-10');
            expect(checkbox.attributes('aria-checked')).toBe('false');

            await area.trigger('click');
            await flushPromises();

            expect(checkbox.attributes('aria-checked')).toBe('true');
            expect(mocks.showGroupDialog).not.toHaveBeenCalled();

            // A tap on the checkbox itself toggles once, not twice.
            await checkbox.trigger('click');
            await flushPromises();
            expect(checkbox.attributes('aria-checked')).toBe('false');
            expect(mocks.showGroupDialog).not.toHaveBeenCalled();
            wrapper.unmount();
        });

        it('keeps the PC behaviour with a mouse: only the checkbox toggles', async () => {
            const wrapper = await openEditMode();
            const area = alphaRow(wrapper);

            await area.trigger('click');
            await flushPromises();

            expect(area.find('[role="checkbox"]').attributes('aria-checked')).toBe('false');
            expect(mocks.showGroupDialog).not.toHaveBeenCalled();
            wrapper.unmount();
        });
    });
});

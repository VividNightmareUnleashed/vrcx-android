import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { ref } from 'vue';

// Android: Show/Hide Avatar are local moderations stored in VRChat's files on
// the PC, so the user dialog menu drops them. The API-backed moderations (block, mute, chatbox, interaction) stay.

vi.mock('@/shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true,
    hasLocalGame: false,
    hasLocalVrchatFiles: false
}));
vi.mock('pinia', async (i) => ({ ...(await i()), storeToRefs: (s) => s }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k) => k }) }));
vi.mock('@/shared/utils', () => ({ invertHexColor: (colour) => colour }));
vi.mock('../../../../stores', () => ({
    useUserStore: () => ({
        userDialog: ref({
            id: 'usr_2',
            ref: { id: 'usr_2', $isModerator: false },
            theme: { iconColor: 'var(--muted-foreground)', buttonColor: 'var(--primary)' },
            isFriend: true,
            isFavorite: false,
            incomingRequest: false,
            outgoingRequest: false,
            isBlock: false,
            isMute: false,
            isMuteChat: false,
            isInteractOff: false,
            isHideAvatar: false,
            isShowAvatar: false
        }),
        currentUser: ref({ id: 'usr_1', isBoopingEnabled: true })
    }),
    useGameStore: () => ({ isGameRunning: ref(false) }),
    useLocationStore: () => ({ lastLocation: ref({ location: 'wrld_1:1' }) })
}));
vi.mock('../../../../composables/useInviteChecks', () => ({
    useInviteChecks: () => ({ checkCanInvite: () => true })
}));
vi.mock('../../../../composables/useRecentActions', () => ({
    isActionRecent: () => false
}));
vi.mock('../../../ui/dropdown-menu', () => ({
    DropdownMenu: { template: '<div><slot /></div>' },
    DropdownMenuTrigger: { template: '<div><slot /></div>' },
    DropdownMenuContent: { template: '<div><slot /></div>' },
    DropdownMenuSeparator: { template: '<hr />' },
    DropdownMenuShortcut: { template: '<span><slot /></span>' },
    DropdownMenuSub: { template: '<div><slot /></div>' },
    DropdownMenuSubTrigger: { template: '<div><slot /></div>' },
    DropdownMenuSubContent: { template: '<div><slot /></div>' },
    DropdownMenuItem: {
        emits: ['click'],
        template: '<button data-testid="dd-item" @click="$emit(\'click\')"><slot /></button>'
    }
}));
vi.mock('@/components/ui/button', () => ({
    Button: {
        emits: ['click'],
        template: '<button data-testid="btn" @click="$emit(\'click\')"><slot /></button>'
    }
}));
vi.mock('../../../ui/tooltip', () => ({
    TooltipWrapper: { template: '<div><slot /></div>' }
}));

import UserActionDropdown from '../UserActionDropdown.vue';

describe('UserActionDropdown.vue on Android', () => {
    it('hides Show Avatar and Hide Avatar and keeps the API moderations', () => {
        const wrapper = mount(UserActionDropdown, { props: { userDialogCommand: vi.fn() } });
        const text = wrapper.text();

        expect(text).not.toContain('dialog.user.actions.moderation_show_avatar');
        expect(text).not.toContain('dialog.user.actions.moderation_hide_avatar');

        expect(text).toContain('dialog.user.actions.moderation_block');
        expect(text).toContain('dialog.user.actions.moderation_mute');
        expect(text).toContain('dialog.user.actions.moderation_disable_chatbox');
        expect(text).toContain('dialog.user.actions.moderation_disable_avatar_interaction');
    });
});

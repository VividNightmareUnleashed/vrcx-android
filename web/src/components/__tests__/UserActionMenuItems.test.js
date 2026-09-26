import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

const mocks = vi.hoisted(() => ({
    isGameRunning: null,
    showUserDialog: vi.fn(),
    showSendBoopDialog: vi.fn(),
    showLaunchDialog: vi.fn(),
    selfInvite: vi.fn(() => Promise.resolve({})),
    sendRequestInvite: vi.fn(() => Promise.resolve({})),
    toastSuccess: vi.fn()
}));

vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));
vi.mock('vue-sonner', () => ({ toast: { success: (...args) => mocks.toastSuccess(...args) } }));
vi.mock('../../stores', () => ({
    useUserStore: () => ({
        showSendBoopDialog: (...args) => mocks.showSendBoopDialog(...args),
        currentUser: ref({ id: 'usr_me', isBoopingEnabled: true })
    }),
    useLaunchStore: () => ({ showLaunchDialog: (...args) => mocks.showLaunchDialog(...args) }),
    useLocationStore: () => ({
        lastLocation: ref({ location: 'wrld_here:1' }),
        lastLocationDestination: ref('')
    }),
    useGameStore: () => ({ isGameRunning: mocks.isGameRunning })
}));
vi.mock('../../composables/useInviteChecks', () => ({
    useInviteChecks: () => ({ checkCanInvite: () => true, checkCanInviteSelf: () => true })
}));
vi.mock('../../composables/useRecentActions', () => ({
    isActionRecent: () => false,
    recordRecentAction: vi.fn()
}));
vi.mock('../../coordinators/userCoordinator', () => ({
    showUserDialog: (...args) => mocks.showUserDialog(...args)
}));
vi.mock('../../api', () => ({
    instanceRequest: { selfInvite: (...args) => mocks.selfInvite(...args) },
    notificationRequest: { sendRequestInvite: (...args) => mocks.sendRequestInvite(...args), sendInvite: vi.fn() },
    queryRequest: { fetch: vi.fn() }
}));

function itemStub(kind) {
    return {
        props: ['disabled'],
        emits: ['click'],
        template: `<button data-kind="${kind}" :disabled="disabled" @click="$emit('click')"><slot /></button>`
    };
}

vi.mock('../ui/context-menu', () => ({
    ContextMenu: { template: '<div><slot /></div>' },
    ContextMenuTrigger: { template: '<div data-testid="trigger"><slot /></div>' },
    ContextMenuContent: { template: '<div data-testid="content"><slot /></div>' },
    ContextMenuItem: itemStub('context-item'),
    ContextMenuSeparator: { template: '<hr data-kind="context-separator" />' },
    ContextMenuShortcut: { template: '<span><slot /></span>' }
}));
vi.mock('../ui/dropdown-menu', () => ({
    DropdownMenu: { template: '<div><slot /></div>' },
    DropdownMenuTrigger: { template: '<div data-testid="kebab-trigger"><slot /></div>' },
    DropdownMenuContent: { template: '<div data-testid="kebab-content"><slot /></div>' },
    DropdownMenuItem: itemStub('dropdown-item'),
    DropdownMenuSeparator: { template: '<hr data-kind="dropdown-separator" />' },
    DropdownMenuShortcut: { template: '<span><slot /></span>' }
}));

import UserActionMenuButton from '../UserActionMenuButton.vue';
import UserActionMenuItems from '../UserActionMenuItems.vue';
import UserContextMenu from '../UserContextMenu.vue';

const ONLINE = { userId: 'usr_friend', state: 'online', location: 'wrld_a:123~region(eu)' };

/**
 * @param {import('@vue/test-utils').VueWrapper} wrapper
 * @returns {string[]} Rendered items and separators, in order
 */
function outline(wrapper) {
    return wrapper
        .findAll('[data-kind]')
        .map((el) => (el.attributes('data-kind').endsWith('separator') ? '---' : el.text()));
}

describe('UserActionMenuItems.vue', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mocks.isGameRunning = ref(false);
    });

    it('renders the PC right-click menu for an online friend in an instance', () => {
        const wrapper = mount(UserActionMenuItems, { props: ONLINE });

        expect(outline(wrapper)).toEqual([
            'common.actions.view_details',
            '---',
            'dialog.user.actions.request_invite',
            'dialog.user.actions.send_boop',
            '---',
            'dialog.user.info.launch_invite_tooltip',
            'dialog.user.info.self_invite_tooltip'
        ]);
        expect(wrapper.findAll('[data-kind="context-item"]')).toHaveLength(5);
    });

    it('adds Invite while the game runs, and drops the online-only items for offline users', () => {
        mocks.isGameRunning = ref(true);
        const wrapper = mount(UserActionMenuItems, { props: { userId: 'usr_friend', state: 'offline' } });

        expect(outline(wrapper)).toEqual([
            'common.actions.view_details',
            '---',
            'dialog.user.actions.invite',
            'dialog.user.actions.send_boop'
        ]);
    });

    it('renders dropdown items for the kebab variant', () => {
        const wrapper = mount(UserActionMenuItems, { props: { ...ONLINE, variant: 'dropdown' } });

        expect(wrapper.findAll('[data-kind="dropdown-item"]')).toHaveLength(5);
        expect(wrapper.findAll('[data-kind="context-item"]')).toHaveLength(0);
    });

    it('can show only some items, with a leading separator when appended to another menu', () => {
        const wrapper = mount(UserActionMenuItems, {
            props: { ...ONLINE, variant: 'dropdown', items: ['join', 'self-invite'], separatorBefore: true }
        });

        expect(outline(wrapper)).toEqual([
            '---',
            'dialog.user.info.launch_invite_tooltip',
            'dialog.user.info.self_invite_tooltip'
        ]);
    });

    it('renders nothing for join-only items when the user has no instance', () => {
        const wrapper = mount(UserActionMenuItems, {
            props: { userId: 'usr_friend', state: 'online', location: 'private', items: ['join', 'self-invite'] }
        });

        expect(outline(wrapper)).toEqual([]);
    });

    it('runs the actions', async () => {
        const wrapper = mount(UserActionMenuItems, { props: ONLINE });
        const byText = (text) => wrapper.findAll('[data-kind]').find((el) => el.text() === text);

        await byText('common.actions.view_details').trigger('click');
        await byText('dialog.user.actions.send_boop').trigger('click');
        await byText('dialog.user.info.launch_invite_tooltip').trigger('click');
        await byText('dialog.user.info.self_invite_tooltip').trigger('click');
        await byText('dialog.user.actions.request_invite').trigger('click');
        await flushPromises();

        expect(mocks.showUserDialog).toHaveBeenCalledWith('usr_friend');
        expect(mocks.showSendBoopDialog).toHaveBeenCalledWith('usr_friend');
        expect(mocks.showLaunchDialog).toHaveBeenCalledWith('wrld_a:123~region(eu)');
        expect(mocks.selfInvite).toHaveBeenCalledWith({ instanceId: '123~region(eu)', worldId: 'wrld_a' });
        expect(mocks.sendRequestInvite).toHaveBeenCalledWith({ platform: 'standalonewindows' }, 'usr_friend');
        expect(mocks.toastSuccess).toHaveBeenCalledWith('message.user.request_invite_sent');
    });
});

describe('UserContextMenu.vue', () => {
    beforeEach(() => {
        mocks.isGameRunning = ref(false);
    });

    it('wraps the row and keeps the append slot after the shared items', () => {
        const wrapper = mount(UserContextMenu, {
            props: ONLINE,
            slots: { default: '<span data-testid="row">Aurora</span>', append: '<i data-kind="extra-item">Extra</i>' }
        });

        expect(wrapper.find('[data-testid="trigger"] [data-testid="row"]').exists()).toBe(true);
        const items = outline(wrapper);
        expect(items[0]).toBe('common.actions.view_details');
        expect(items.at(-1)).toBe('Extra');
        expect(wrapper.findAll('[data-kind="context-item"]')).toHaveLength(5);
    });
});

describe('UserActionMenuButton.vue', () => {
    beforeEach(() => {
        mocks.isGameRunning = ref(false);
    });

    it('is a labelled kebab that opens the same items as a dropdown', () => {
        const wrapper = mount(UserActionMenuButton, { props: ONLINE });
        const button = wrapper.find('[data-slot="user-action-menu-button"]');

        expect(button.exists()).toBe(true);
        expect(button.attributes('aria-label')).toBe('android.entity_dialogs.more_actions');
        expect(wrapper.findAll('[data-testid="kebab-content"] [data-kind="dropdown-item"]')).toHaveLength(5);
    });

    it('does not let a tap on the kebab reach the row underneath', async () => {
        const onRowClick = vi.fn();
        const wrapper = mount(
            {
                components: { UserActionMenuButton },
                template: '<div @click="onRowClick"><UserActionMenuButton v-bind="props" /></div>',
                setup: () => ({ onRowClick, props: ONLINE })
            },
            { attachTo: document.body }
        );

        await wrapper.find('[data-slot="user-action-menu-button"]').trigger('click');

        expect(onRowClick).not.toHaveBeenCalled();
        wrapper.unmount();
    });
});

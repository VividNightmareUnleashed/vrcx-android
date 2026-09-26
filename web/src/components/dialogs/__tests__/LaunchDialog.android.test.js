import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { ref } from 'vue';

const mocks = vi.hoisted(() => ({
    selfInvite: vi.fn(async () => ({})),
    writeText: vi.fn(),
    getBool: vi.fn(async () => false),
    launchDialogData: {
        value: {
            visible: true,
            loading: true,
            tag: 'wrld_1:123',
            shortName: 'abc'
        }
    },
    canLaunchGame: false
}));

// Android (docs/ARCHITECTURE.md §9): Launch only when a VRChat app on the device handles vrchat://launch, and never
// the "start as desktop" / VR mode menu, which starts the PC client.
vi.mock('../../../shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true,
    hasLocalGame: false,
    hasLocalVrchatFiles: false
}));

Object.assign(globalThis, {
    navigator: { clipboard: { writeText: (...a) => mocks.writeText(...a) } }
});

vi.mock('pinia', async (i) => ({ ...(await i()), storeToRefs: (s) => s }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k) => k }) }));
vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
vi.mock('../../../stores', () => ({
    useFriendStore: () => ({ friends: ref(new Map()) }),
    useGameStore: () => ({ isGameRunning: ref(false) }),
    useInviteStore: () => ({ canOpenInstanceInGame: ref(false) }),
    useLaunchStore: () => ({
        launchDialogData: mocks.launchDialogData,
        canLaunchGame: ref(mocks.canLaunchGame),
        launchGame: vi.fn(),
        tryOpenInstanceInVrc: vi.fn()
    }),
    useLocationStore: () => ({ lastLocation: ref({ friendList: new Map() }) }),
    useModalStore: () => ({ confirm: vi.fn() })
}));
vi.mock('../../../shared/utils', () => ({
    getLaunchURL: () => 'vrchat://launch',
    isRealInstance: () => true,
    parseLocation: () => ({
        isRealInstance: true,
        worldId: 'wrld_1',
        instanceId: '123',
        tag: 'wrld_1:123'
    })
}));
vi.mock('../../../composables/useInviteChecks', () => ({
    useInviteChecks: () => ({ checkCanInvite: () => true })
}));
vi.mock('../../../api', () => ({
    instanceRequest: {
        selfInvite: (...a) => mocks.selfInvite(...a),
        getInstanceShortName: vi.fn()
    },
    queryRequest: { fetch: vi.fn() }
}));
vi.mock('../../../services/config', () => ({
    default: { getBool: (...a) => mocks.getBool(...a), setBool: vi.fn() }
}));
vi.mock('@/components/ui/dialog', () => ({
    Dialog: { template: '<div><slot /></div>' },
    DialogContent: { template: '<div><slot /></div>' },
    DialogHeader: { template: '<div><slot /></div>' },
    DialogTitle: { template: '<div><slot /></div>' },
    DialogDescription: { template: '<div><slot /></div>' },
    DialogFooter: { template: '<div><slot /></div>' }
}));
vi.mock('@/components/ui/dropdown-menu', () => ({
    DropdownMenu: { template: '<div data-testid="launch-mode-menu"><slot /></div>' },
    DropdownMenuTrigger: { template: '<div><slot /></div>' },
    DropdownMenuContent: { template: '<div><slot /></div>' },
    DropdownMenuItem: { template: '<div><slot /></div>' }
}));
vi.mock('@/components/ui/field', () => ({
    Field: { template: '<div><slot /></div>' },
    FieldGroup: { template: '<div><slot /></div>' },
    FieldLabel: { template: '<div><slot /></div>' },
    FieldContent: { template: '<div><slot /></div>' },
    FieldDescription: { template: '<p><slot /></p>' }
}));
vi.mock('@/components/ui/button', () => ({
    Button: {
        emits: ['click'],
        template: '<button data-testid="btn" @click="$emit(\'click\')"><slot /></button>'
    }
}));
vi.mock('@/components/ui/button-group', () => ({
    ButtonGroup: { template: '<div><slot /></div>' }
}));
vi.mock('@/components/ui/input-group', () => ({
    InputGroupField: { template: '<input />' }
}));
vi.mock('@/components/ui/tooltip', () => ({
    TooltipWrapper: { template: '<div><slot /></div>' }
}));
vi.mock('../InviteDialog/InviteDialog.vue', () => ({
    default: { template: '<div />' }
}));
vi.mock('lucide-vue-next', () => ({
    Copy: { template: '<i />' },
    Info: { template: '<i />' },
    MoreHorizontal: { template: '<i />' }
}));

import LaunchDialog from '../LaunchDialog.vue';

describe('LaunchDialog.vue on Android', () => {
    beforeEach(() => {
        mocks.canLaunchGame = false;
    });

    it('hides the Launch group when no app on the device can launch VRChat', async () => {
        const wrapper = mount(LaunchDialog);
        await Promise.resolve();
        expect(wrapper.text()).toContain('dialog.launch.self_invite');
        expect(wrapper.text()).not.toContain('dialog.launch.open_ingame');
        expect(wrapper.text()).not.toContain('dialog.launch.launch');
        expect(wrapper.find('[data-testid="launch-mode-menu"]').exists()).toBe(false);
    });

    it('shows Launch without the PC launch-mode menu when VRChat can be launched', async () => {
        mocks.canLaunchGame = true;
        const wrapper = mount(LaunchDialog);
        await Promise.resolve();
        expect(wrapper.text()).toContain('dialog.launch.launch');
        expect(wrapper.text()).not.toContain('dialog.launch.start_as_desktop');
        expect(wrapper.find('[data-testid="launch-mode-menu"]').exists()).toBe(false);
    });

    // Phones (DESIGN.md §3.2): three equal footer columns are too narrow for "Launch VRChat" at 360px, so Invite and
    // Self invite share a row and the Launch group gets a full-width row, with labels that may wrap.
    it('gives the Launch group its own footer row on phones', async () => {
        mocks.canLaunchGame = true;
        const wrapper = mount(LaunchDialog);
        await Promise.resolve();
        const launch = wrapper.findAll('button').find((button) => button.text() === 'dialog.launch.launch');
        const group = launch.element.parentElement;
        const footer = group.parentElement;
        expect(group.className).toContain('compact:col-span-2');
        expect(footer.className).toContain('compact:grid-cols-2');
        expect(footer.className).not.toContain('auto-cols-fr');
        expect(footer.className).toContain('compact:[&_button]:whitespace-normal');
        expect(footer.className).toContain('compact-landscape:flex');
    });
});

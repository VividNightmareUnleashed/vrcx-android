import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick, ref } from 'vue';

// Touch screens: the player-count tooltip holds the only "Close instance"
// button, and tooltips cannot hold a button on touch, so the details open in a popover on tap instead.

const mocks = vi.hoisted(() => ({
    isCoarsePointer: null,
    closeInstance: vi.fn(() => Promise.resolve({ json: { id: 'inst_closed' } })),
    modalConfirm: vi.fn(() => Promise.resolve({ ok: true })),
    applyInstance: vi.fn(),
    toastSuccess: vi.fn()
}));

vi.mock('../../composables/useCompactLayout', () => ({
    useCompactLayout: () => ({ isCoarsePointer: mocks.isCoarsePointer })
}));
vi.mock('pinia', async (importOriginal) => ({
    ...(await importOriginal()),
    storeToRefs: (store) => store
}));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));
vi.mock('vue-sonner', () => ({ toast: { success: (...args) => mocks.toastSuccess(...args) } }));
vi.mock('../../stores', () => ({
    useLocationStore: () => ({ lastLocation: { location: 'wrld_here:1', playerList: new Set() } }),
    useUserStore: () => ({ currentUser: { id: 'usr_me' } }),
    useGroupStore: () => ({ cachedGroups: new Map() }),
    useInstanceStore: () => ({
        instanceJoinHistory: ref(new Map()),
        applyInstance: (...args) => mocks.applyInstance(...args)
    }),
    useModalStore: () => ({ confirm: (...args) => mocks.modalConfirm(...args) }),
    useLaunchStore: () => ({ isOpeningInstance: ref(false), showLaunchDialog: vi.fn(), tryOpenInstanceInVrc: vi.fn() }),
    useInviteStore: () => ({ canOpenInstanceInGame: ref(false) })
}));
vi.mock('../../composables/useInviteChecks', () => ({
    useInviteChecks: () => ({ checkCanInviteSelf: () => true })
}));
vi.mock('../../shared/utils', () => ({
    parseLocation: () => ({ isRealInstance: true, instanceId: 'inst_1', worldId: 'wrld_1', tag: 'wrld_1:inst_1' }),
    hasGroupPermission: () => false
}));
vi.mock('../../api', () => ({
    instanceRequest: { selfInvite: vi.fn(() => Promise.resolve({})) },
    miscRequest: { closeInstance: (...args) => mocks.closeInstance(...args) }
}));
vi.mock('../dialogs/InstanceAnnouncementDialog.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../ui/popover', () => ({
    Popover: { template: '<div data-testid="popover"><slot /></div>' },
    PopoverTrigger: { template: '<div data-testid="popover-trigger"><slot /></div>' },
    PopoverContent: { template: '<div data-testid="popover-content"><slot /></div>' }
}));

import InstanceActionBar from '../InstanceActionBar.vue';

const instance = {
    ownerId: 'usr_me',
    capacity: 16,
    userCount: 4,
    hasCapacityForYou: true,
    platforms: { standalonewindows: 1, android: 2, ios: 1 },
    gameServerVersion: 1234,
    $disabledContentSettings: []
};

function mountBar() {
    return mount(InstanceActionBar, {
        props: {
            location: 'wrld_1:inst_1',
            instanceLocation: 'wrld_1:inst_close',
            instance,
            friendcount: 0,
            showButtons: true,
            showInstanceInfo: true,
            onRefresh: vi.fn()
        },
        global: {
            stubs: {
                TooltipWrapper: {
                    props: ['content'],
                    template:
                        '<div data-testid="tooltip"><slot /><div data-testid="tooltip-content"><slot name="content" /></div></div>'
                },
                Timer: { template: '<span />' }
            }
        }
    });
}

describe('InstanceActionBar.vue on touch screens', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('opens the instance details in a popover with a labelled Close instance button', async () => {
        mocks.isCoarsePointer = ref(true);
        const wrapper = mountBar();

        const trigger = wrapper.find('[data-slot="instance-info-trigger"]');
        expect(trigger.exists()).toBe(true);
        expect(trigger.element.tagName).toBe('BUTTON');
        expect(trigger.attributes('aria-label')).toBe('android.entity_dialogs.instance_details');
        expect(trigger.text()).toContain('4/16');

        const content = wrapper.find('[data-testid="popover-content"]');
        expect(content.text()).toContain('dialog.user.info.instance_game_version');
        const close = content.findAll('button').find((b) => b.text().includes('dialog.user.info.close_instance'));
        expect(close).toBeTruthy();

        await close.trigger('click');
        await Promise.resolve();
        await Promise.resolve();
        await nextTick();

        expect(mocks.modalConfirm).toHaveBeenCalled();
        expect(mocks.closeInstance).toHaveBeenCalledWith({ location: 'wrld_1:inst_close', hardClose: false });
    });

    it('gives the instance buttons a 40px touch size', () => {
        mocks.isCoarsePointer = ref(true);
        const wrapper = mountBar();
        const buttons = wrapper.findAll('[data-slot="button"]').filter((b) => b.classes().includes('rounded-full'));

        expect(buttons.length).toBeGreaterThan(0);
        for (const button of buttons) {
            expect(button.classes()).toContain('pointer-coarse:size-10');
        }
    });

    it('keeps the PC tooltip with a pointer', () => {
        mocks.isCoarsePointer = ref(false);
        const wrapper = mountBar();

        expect(wrapper.find('[data-testid="popover"]').exists()).toBe(false);
        expect(wrapper.find('[data-slot="instance-info-trigger"]').exists()).toBe(false);
        const details = wrapper
            .findAll('[data-testid="tooltip-content"]')
            .filter((el) => el.text().includes('dialog.user.info.instance_game_version'));
        expect(details).toHaveLength(1);
    });
});

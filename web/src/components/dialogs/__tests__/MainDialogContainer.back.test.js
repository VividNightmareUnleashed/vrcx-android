// The Android back button against the real entity dialog host and real reka layers (docs/DESIGN.md §6 step 1):
// the main dialog steps back a crumb only when reka considers it the top layer, whatever the DOM order.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, reactive, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';

const state = vi.hoisted(() => ({ ui: null, user: null }));

vi.mock('@/shared/utils/platform', async (importOriginal) => ({ ...(await importOriginal()), isAndroid: true }));
vi.mock('@/composables/useCompactLayout', async () => {
    const { ref: vueRef } = await import('vue');
    return {
        useCompactLayout: () => ({
            isCompact: vueRef(true),
            isCompactLandscape: vueRef(false),
            isCoarsePointer: vueRef(true)
        })
    };
});
vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));
vi.mock('@/stores/settings/general', () => ({
    useGeneralSettingsStore: () => ({ disableGpuAcceleration: ref(false) })
}));
vi.mock('@/stores', async () => {
    const { ref: vueRef } = await import('vue');
    return {
        useUiStore: () => state.ui,
        useUserStore: () => state.user,
        useWorldStore: () => ({ worldDialog: { visible: false } }),
        useAvatarStore: () => ({ avatarDialog: { visible: false } }),
        useGroupStore: () => ({ groupDialog: { visible: false }, groupMemberModeration: { visible: false } }),
        useInstanceStore: () => ({
            previousInstancesInfoDialog: vueRef({ visible: false }),
            previousInstancesListDialog: vueRef({ visible: false, variant: 'user' })
        }),
        useAppearanceSettingsStore: () => ({ displayVRCProfileBackgrounds: false, isDarkMode: false })
    };
});
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));
vi.mock('@/shared/utils/user', () => ({ getReadableProfileThemeColor: (color) => color }));
vi.mock('../AvatarDialog/AvatarDialog.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../GroupDialog/GroupDialog.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../GroupDialog/GroupMemberModerationDialog.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../PreviousInstancesDialog/PreviousInstancesInfoDialog.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../PreviousInstancesDialog/PreviousInstancesListDialog.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../WorldDialog/WorldDialog.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../UserDialog/UserDialog.vue', () => ({
    default: { template: '<div data-testid="user-dialog"><button type="button">Inside</button></div>' }
}));

import { HoverCard, HoverCardContent, HoverCardTrigger } from '../../ui/hover-card';
import { createBackHandler } from '../../../platform/android/shell/backHandler';
import MainDialogContainer from '../MainDialogContainer.vue';

async function settle() {
    await flushPromises();
    await nextTick();
    // The back handler sends at most one Escape per task.
    await new Promise((resolve) => setTimeout(resolve, 0));
}

function createHandler() {
    return createBackHandler({
        getRouter: () => null,
        shell: { friendsPanelOpen: false, navSheetOpen: false },
        closeShellPanels: () => false,
        doc: document,
        getHistoryState: () => null
    });
}

describe('MainDialogContainer and the Android back button', () => {
    let wrapper;

    beforeEach(() => {
        state.user = reactive({ userDialog: { visible: true, publicProfileRef: null } });
        state.ui = reactive({
            dialogCrumbs: [
                { type: 'user', id: 'usr_1', label: 'User' },
                { type: 'world', id: 'wrld_1', label: 'World' }
            ],
            jumpBackDialogCrumb: vi.fn(() => state.ui.dialogCrumbs.pop()),
            closeMainDialog: vi.fn(() => {
                state.user.userDialog.visible = false;
                state.ui.dialogCrumbs = [];
            }),
            handleBreadcrumbClick: vi.fn()
        });
    });

    afterEach(() => {
        wrapper?.unmount();
        wrapper = null;
        document.body.innerHTML = '';
    });

    it('steps back one crumb, then closes on the last one', async () => {
        wrapper = mount(MainDialogContainer, { attachTo: document.body });
        await settle();
        expect(document.querySelector('[data-vrcx-main-dialog]')).not.toBeNull();

        const handleBack = createHandler();
        expect(handleBack()).toBe(true);
        await settle();
        expect(state.ui.jumpBackDialogCrumb).toHaveBeenCalledTimes(1);
        expect(state.ui.closeMainDialog).not.toHaveBeenCalled();
        expect(document.querySelector('[data-testid="user-dialog"]')).not.toBeNull();

        expect(handleBack()).toBe(true);
        await settle();
        expect(state.ui.jumpBackDialogCrumb).toHaveBeenCalledTimes(1);
        expect(state.ui.closeMainDialog).toHaveBeenCalledTimes(1);
    });

    it('keeps a real Escape key closing the whole dialog, as on PC', async () => {
        wrapper = mount(MainDialogContainer, { attachTo: document.body });
        await settle();

        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
        await settle();

        expect(state.ui.jumpBackDialogCrumb).not.toHaveBeenCalled();
        expect(state.ui.closeMainDialog).toHaveBeenCalledTimes(1);
    });

    it('steps back a crumb when a hover card opened before the dialog is still mounted under it', async () => {
        state.user.userDialog.visible = false;
        const hoverOpen = ref(true);
        const Host = defineComponent({
            setup: () => () => [
                // A hover card that stays open (touch off, so nothing closes it when the dialog takes focus).
                h(
                    HoverCard,
                    {
                        open: hoverOpen.value,
                        enableTouch: false,
                        'onUpdate:open': (value) => (hoverOpen.value = value)
                    },
                    () => [
                        h(HoverCardTrigger, { asChild: true }, () => h('p', 'Sender')),
                        h(HoverCardContent, null, () => 'Details')
                    ]
                ),
                h(MainDialogContainer)
            ]
        });
        wrapper = mount(Host, { attachTo: document.body });
        await settle();
        expect(document.querySelector('[data-slot="hover-card-content"]')).not.toBeNull();

        // The entity dialog opens after the hover card: reka puts it on top of its layer stack.
        state.user.userDialog.visible = true;
        await settle();
        expect(document.querySelector('[data-vrcx-main-dialog]')).not.toBeNull();

        const handleBack = createHandler();
        expect(handleBack()).toBe(true);
        await settle();

        expect(state.ui.jumpBackDialogCrumb).toHaveBeenCalledTimes(1);
        expect(state.ui.closeMainDialog).not.toHaveBeenCalled();
        expect(document.querySelector('[data-testid="user-dialog"]')).not.toBeNull();
    });
});

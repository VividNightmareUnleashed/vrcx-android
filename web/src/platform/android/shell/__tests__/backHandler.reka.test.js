// The back handler against real reka layers: the synthetic Escape must close the top layer through reka's own
// dismiss path (so v-model and modal promises resolve normally), one layer per press, in reka's own layer order.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';

// DialogOverlay reads the GPU-acceleration setting; the real store pulls in the whole app.
vi.mock('@/stores/settings/general', () => ({
    useGeneralSettingsStore: () => ({ disableGpuAcceleration: ref(false) })
}));
vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));

import { Dialog, DialogContent, DialogTitle } from '../../../../components/ui/dialog';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger
} from '../../../../components/ui/dropdown-menu';
import { HoverCard, HoverCardContent, HoverCardTrigger } from '../../../../components/ui/hover-card';
import { Sheet, SheetContent, SheetTitle } from '../../../../components/ui/sheet';
import { createBackHandler, handleMainDialogEscape } from '../backHandler';

function createUi(crumbs = []) {
    return { dialogCrumbs: crumbs, jumpBackDialogCrumb: vi.fn(), closeMainDialog: vi.fn() };
}

function createHandler() {
    return createBackHandler({
        getRouter: () => null,
        shell: { friendsPanelOpen: false, navSheetOpen: false },
        closeShellPanels: vi.fn(() => false),
        doc: document,
        getHistoryState: () => null
    });
}

/** The entity dialog host wiring (components/dialogs/MainDialogContainer.vue). */
function mainDialogContent(ui, children) {
    return h(
        DialogContent,
        { 'data-vrcx-main-dialog': '', onEscapeKeyDown: (event) => handleMainDialogEscape(event, ui) },
        children
    );
}

async function settle() {
    await flushPromises();
    await nextTick();
    // The back handler sends at most one Escape per task.
    await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('Android back handler with reka layers', () => {
    let wrapper;

    afterEach(() => {
        wrapper?.unmount();
        wrapper = null;
        document.body.innerHTML = '';
    });

    it('closes an open dialog through its v-model, and reports false once nothing is open', async () => {
        const open = ref(true);
        const Host = defineComponent({
            setup: () => () =>
                h(Dialog, { open: open.value, 'onUpdate:open': (value) => (open.value = value) }, () =>
                    h(DialogContent, null, () => [h(DialogTitle, null, () => 'Title'), h('p', 'Body')])
                )
        });
        wrapper = mount(Host, { attachTo: document.body });
        await settle();
        expect(document.querySelector('[data-dismissable-layer]')).not.toBeNull();

        const handleBack = createHandler();
        expect(handleBack()).toBe(true);
        await settle();

        expect(open.value).toBe(false);
        expect(handleBack()).toBe(false);
    });

    it('closes the newest layer first: a dialog opened over a sheet', async () => {
        const sheetOpen = ref(true);
        const dialogOpen = ref(false);
        const Host = defineComponent({
            setup: () => () => [
                h(Sheet, { open: sheetOpen.value, 'onUpdate:open': (value) => (sheetOpen.value = value) }, () =>
                    h(SheetContent, { side: 'right' }, () => [h(SheetTitle, null, () => 'Sheet')])
                ),
                h(Dialog, { open: dialogOpen.value, 'onUpdate:open': (value) => (dialogOpen.value = value) }, () =>
                    h(DialogContent, null, () => [h(DialogTitle, null, () => 'Dialog')])
                )
            ]
        });
        wrapper = mount(Host, { attachTo: document.body });
        await settle();
        dialogOpen.value = true;
        await settle();

        const handleBack = createHandler();
        expect(handleBack()).toBe(true);
        await settle();
        expect(dialogOpen.value).toBe(false);
        expect(sheetOpen.value).toBe(true);
    });

    it('steps back through the entity dialog crumbs without closing it', async () => {
        const open = ref(true);
        const ui = createUi([{ type: 'user' }, { type: 'world' }]);
        const Host = defineComponent({
            setup: () => () =>
                h(Dialog, { open: open.value, 'onUpdate:open': (value) => (open.value = value) }, () =>
                    mainDialogContent(ui, () => [h(DialogTitle, null, () => 'World'), h('p', 'Body')])
                )
        });
        wrapper = mount(Host, { attachTo: document.body });
        await settle();

        const handleBack = createHandler();
        expect(handleBack()).toBe(true);
        await settle();

        expect(ui.jumpBackDialogCrumb).toHaveBeenCalledTimes(1);
        expect(open.value).toBe(true);
    });

    it('closes a menu opened inside the entity dialog before stepping back a crumb', async () => {
        const open = ref(true);
        const menuOpen = ref(false);
        const ui = createUi([{ type: 'user' }, { type: 'world' }]);
        const Host = defineComponent({
            setup: () => () =>
                h(Dialog, { open: open.value, 'onUpdate:open': (value) => (open.value = value) }, () =>
                    mainDialogContent(ui, () => [
                        h(DialogTitle, null, () => 'User'),
                        h(
                            DropdownMenu,
                            { open: menuOpen.value, 'onUpdate:open': (value) => (menuOpen.value = value) },
                            () => [
                                h(DropdownMenuTrigger, null, () => 'More'),
                                h(DropdownMenuContent, null, () => h(DropdownMenuItem, null, () => 'Share'))
                            ]
                        )
                    ])
                )
        });
        wrapper = mount(Host, { attachTo: document.body });
        await settle();
        menuOpen.value = true;
        await settle();

        const handleBack = createHandler();
        expect(handleBack()).toBe(true);
        await settle();
        expect(menuOpen.value).toBe(false);
        expect(open.value).toBe(true);
        expect(ui.jumpBackDialogCrumb).not.toHaveBeenCalled();

        expect(handleBack()).toBe(true);
        await settle();
        expect(ui.jumpBackDialogCrumb).toHaveBeenCalledTimes(1);
        expect(open.value).toBe(true);
    });

    it('treats a dialog opened after a hover card as the top layer, although the card comes later in the DOM', async () => {
        const open = ref(false);
        const hoverOpen = ref(true);
        const ui = createUi([{ type: 'user' }, { type: 'world' }]);
        const Host = defineComponent({
            setup: () => () => [
                h(
                    HoverCard,
                    { open: hoverOpen.value, enableTouch: false, 'onUpdate:open': (value) => (hoverOpen.value = value) },
                    () => [
                        h(HoverCardTrigger, { asChild: true }, () => h('p', 'Sender')),
                        h(HoverCardContent, null, () => 'Details')
                    ]
                ),
                h(Dialog, { open: open.value, 'onUpdate:open': (value) => (open.value = value) }, () =>
                    mainDialogContent(ui, () => [h(DialogTitle, null, () => 'World')])
                )
            ]
        });
        wrapper = mount(Host, { attachTo: document.body });
        await settle();
        open.value = true;
        await settle();
        const layers = [...document.querySelectorAll('[data-dismissable-layer]')];
        expect(layers.at(-1).matches('[data-slot="hover-card-content"]')).toBe(true);

        const handleBack = createHandler();
        expect(handleBack()).toBe(true);
        await settle();
        expect(ui.jumpBackDialogCrumb).toHaveBeenCalledTimes(1);
        expect(open.value).toBe(true);
        expect(hoverOpen.value).toBe(true);

        // On the last crumb the dialog closes, and the next press reaches the hover card.
        ui.dialogCrumbs = [{ type: 'user' }];
        expect(handleBack()).toBe(true);
        await settle();
        expect(open.value).toBe(false);
        expect(hoverOpen.value).toBe(true);

        expect(handleBack()).toBe(true);
        await settle();
        expect(hoverOpen.value).toBe(false);
    });

    it('leaves a dialog that blocks Escape open, but still handles the press', async () => {
        const open = ref(true);
        const Host = defineComponent({
            setup: () => () =>
                h(Dialog, { open: open.value, 'onUpdate:open': (value) => (open.value = value) }, () =>
                    h(DialogContent, { onEscapeKeyDown: (event) => event.preventDefault() }, () => [
                        h(DialogTitle, null, () => 'Upgrading')
                    ])
                )
        });
        wrapper = mount(Host, { attachTo: document.body });
        await settle();

        const handleBack = createHandler();
        expect(handleBack()).toBe(true);
        await settle();
        expect(open.value).toBe(true);
    });
});

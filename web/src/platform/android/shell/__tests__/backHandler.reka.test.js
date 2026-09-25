// The back handler against real reka layers: the synthetic Escape must close the top layer through reka's own
// dismiss path (so v-model and modal promises resolve normally), one layer per press.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';

// DialogOverlay reads the GPU-acceleration setting; the real store pulls in the whole app.
vi.mock('@/stores/settings/general', () => ({
    useGeneralSettingsStore: () => ({ disableGpuAcceleration: ref(false) })
}));
vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));

import { Dialog, DialogContent, DialogTitle } from '../../../../components/ui/dialog';
import { Sheet, SheetContent, SheetTitle } from '../../../../components/ui/sheet';
import { createBackHandler } from '../backHandler';

function createHandler({ crumbs = [] } = {}) {
    const ui = { dialogCrumbs: crumbs, jumpBackDialogCrumb: vi.fn(), closeMainDialog: vi.fn() };
    const shell = { friendsPanelOpen: false, navSheetOpen: false };
    const handleBack = createBackHandler({
        getUiStore: () => ui,
        getRouter: () => null,
        shell,
        closeShellPanels: vi.fn(() => false),
        doc: document,
        getHistoryState: () => null
    });
    return { ui, handleBack };
}

async function settle() {
    await flushPromises();
    await nextTick();
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

        const { handleBack } = createHandler();
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

        const { handleBack } = createHandler();
        expect(handleBack()).toBe(true);
        await settle();
        expect(dialogOpen.value).toBe(false);
        expect(sheetOpen.value).toBe(true);
    });

    it('steps back through the entity dialog crumbs without closing it', async () => {
        const open = ref(true);
        const Host = defineComponent({
            setup: () => () =>
                h(Dialog, { open: open.value, 'onUpdate:open': (value) => (open.value = value) }, () =>
                    h(DialogContent, { 'data-vrcx-main-dialog': '' }, () => [
                        h(DialogTitle, null, () => 'World'),
                        h('p', 'Body')
                    ])
                )
        });
        wrapper = mount(Host, { attachTo: document.body });
        await settle();

        const { ui, handleBack } = createHandler({ crumbs: [{ type: 'user' }, { type: 'world' }] });
        expect(handleBack()).toBe(true);
        await settle();

        expect(ui.jumpBackDialogCrumb).toHaveBeenCalledTimes(1);
        expect(open.value).toBe(true);
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

        const { handleBack } = createHandler();
        expect(handleBack()).toBe(true);
        await settle();
        expect(open.value).toBe(true);
    });
});

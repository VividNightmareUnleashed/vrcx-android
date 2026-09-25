// Touch hover cards against the real reka components (docs/DESIGN.md §3.3): a tap on the trigger's own information
// toggles the card, a tap on a button or clickable name inside the trigger does not, and a modal opened later closes it.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';

const platform = vi.hoisted(() => ({ isAndroid: true }));
vi.mock('@/shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    get isAndroid() {
        return platform.isAndroid;
    }
}));
// DialogOverlay reads the GPU-acceleration setting; the real store pulls in the whole app.
vi.mock('@/stores/settings/general', () => ({
    useGeneralSettingsStore: () => ({ disableGpuAcceleration: ref(false) })
}));
vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));

import { Dialog, DialogContent, DialogTitle } from '../../dialog';
import { HoverCard, HoverCardContent, HoverCardTrigger } from '..';
import { HOVER_CARD_TAP_MAX_MS } from '../touch';

function pointer(type, target, { pointerType = 'touch', x = 10, y = 10, timeStamp } = {}) {
    const event = new PointerEvent(type, { bubbles: true, cancelable: true, pointerType, clientX: x, clientY: y });
    if (timeStamp !== undefined) {
        Object.defineProperty(event, 'timeStamp', { value: timeStamp });
    }
    target.dispatchEvent(event);
}

function tap(target, options = {}) {
    pointer('pointerdown', target, options);
    pointer('pointerup', target, options);
    target.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
}

async function settle() {
    await flushPromises();
    await nextTick();
}

function mountCard({ onAccept = () => {}, onOpenDialog = () => {} } = {}) {
    const open = ref(false);
    const Host = defineComponent({
        setup: () => () =>
            h(
                HoverCard,
                { open: open.value, 'onUpdate:open': (value) => (open.value = value), openDelay: 400 },
                () => [
                    h(HoverCardTrigger, { asChild: true }, () =>
                        h('div', { 'data-testid': 'trigger' }, [
                            h('p', { 'data-testid': 'message' }, 'Invite to a world'),
                            h('button', { type: 'button', 'data-testid': 'accept', onClick: onAccept }, 'Accept'),
                            h(
                                'span',
                                { 'data-testid': 'sender', style: 'cursor: pointer', onClick: onOpenDialog },
                                'Sender'
                            )
                        ])
                    ),
                    h(HoverCardContent, null, () => 'Details')
                ]
            )
    });
    const wrapper = mount(Host, { attachTo: document.body });
    const get = (id) => wrapper.get(`[data-testid="${id}"]`).element;
    return { wrapper, open, get };
}

describe('HoverCard touch toggle', () => {
    let wrapper;

    beforeEach(() => {
        platform.isAndroid = true;
    });

    afterEach(() => {
        wrapper?.unmount();
        wrapper = null;
        document.body.innerHTML = '';
    });

    it('opens and closes on taps on the trigger information', async () => {
        const card = mountCard();
        wrapper = card.wrapper;

        tap(card.get('message'));
        await settle();
        expect(card.open.value).toBe(true);
        expect(document.querySelector('[data-slot="hover-card-content"]')).not.toBeNull();

        tap(card.get('message'));
        await settle();
        expect(card.open.value).toBe(false);
    });

    it('leaves taps on a button inside the trigger to the button', async () => {
        const onAccept = vi.fn();
        const card = mountCard({ onAccept });
        wrapper = card.wrapper;

        tap(card.get('accept'));
        await settle();

        expect(onAccept).toHaveBeenCalledTimes(1);
        expect(card.open.value).toBe(false);
    });

    it('leaves taps on a clickable name (cursor-pointer) to it', async () => {
        const onOpenDialog = vi.fn();
        const card = mountCard({ onOpenDialog });
        wrapper = card.wrapper;

        tap(card.get('sender'));
        await settle();

        expect(onOpenDialog).toHaveBeenCalledTimes(1);
        expect(card.open.value).toBe(false);
    });

    it('ignores mouse pointers and long-presses', async () => {
        const card = mountCard();
        wrapper = card.wrapper;

        tap(card.get('message'), { pointerType: 'mouse' });
        pointer('pointerdown', card.get('message'), { timeStamp: 1000 });
        pointer('pointerup', card.get('message'), { timeStamp: 1000 + HOVER_CARD_TAP_MAX_MS + 1 });
        await settle();

        expect(card.open.value).toBe(false);
    });

    it('is off in desktop builds (reka default)', async () => {
        platform.isAndroid = false;
        const card = mountCard();
        wrapper = card.wrapper;

        tap(card.get('message'));
        await settle();

        expect(card.open.value).toBe(false);
    });

    it('closes when a dialog opened after it takes focus', async () => {
        const dialogOpen = ref(false);
        const open = ref(false);
        const Host = defineComponent({
            setup: () => () => [
                h(HoverCard, { open: open.value, 'onUpdate:open': (value) => (open.value = value) }, () => [
                    h(HoverCardTrigger, { asChild: true }, () => h('p', { 'data-testid': 'message' }, 'Info')),
                    h(HoverCardContent, null, () => 'Details')
                ]),
                h(Dialog, { open: dialogOpen.value, 'onUpdate:open': (value) => (dialogOpen.value = value) }, () =>
                    h(DialogContent, null, () => [
                        h(DialogTitle, null, () => 'Dialog'),
                        h('button', { type: 'button' }, 'Inside')
                    ])
                )
            ]
        });
        wrapper = mount(Host, { attachTo: document.body });
        await settle();

        tap(wrapper.get('[data-testid="message"]').element);
        await settle();
        expect(open.value).toBe(true);

        dialogOpen.value = true;
        await settle();
        // reka's FocusScope focuses the dialog; make sure focus really moved in jsdom.
        document.querySelector('[role="dialog"] button')?.focus();
        await settle();

        expect(dialogOpen.value).toBe(true);
        expect(open.value).toBe(false);
    });
});

import { afterEach, describe, expect, it } from 'vitest';

import { AUTOFOCUS_ON_MOUNT_EVENT, installFocusGuard } from '../focusGuard';

function mountDialog(html, slot = 'dialog-content') {
    const container = document.createElement('div');
    container.setAttribute('data-slot', slot);
    container.innerHTML = html;
    document.body.appendChild(container);
    return container;
}

function dispatchMountAutoFocus(container) {
    // reka's FocusScope event: non-bubbling and cancelable, dispatched on the container.
    const event = new CustomEvent(AUTOFOCUS_ON_MOUNT_EVENT, { bubbles: false, cancelable: true });
    container.dispatchEvent(event);
    return event;
}

describe('dialog auto-focus guard', () => {
    let uninstall = null;

    afterEach(() => {
        uninstall?.();
        uninstall = null;
        document.body.innerHTML = '';
    });

    it('keeps the keyboard down: cancels auto-focus on a text field and focuses the dialog', () => {
        uninstall = installFocusGuard({ isActive: () => true });
        const dialog = mountDialog('<input type="text" /><button>OK</button>');

        const event = dispatchMountAutoFocus(dialog);

        expect(event.defaultPrevented).toBe(true);
        expect(document.activeElement).toBe(dialog);
        expect(dialog.getAttribute('tabindex')).toBe('-1');
    });

    it('leaves dialogs that exist to take text input alone', () => {
        uninstall = installFocusGuard({ isActive: () => true });

        const search = mountDialog('<input data-slot="command-input" />');
        expect(dispatchMountAutoFocus(search).defaultPrevented).toBe(false);

        const otp = mountDialog('<input autofocus />');
        expect(dispatchMountAutoFocus(otp).defaultPrevented).toBe(false);

        const prompt = mountDialog('<input />');
        prompt.setAttribute('data-mobile-autofocus', '');
        expect(dispatchMountAutoFocus(prompt).defaultPrevented).toBe(false);
    });

    it('does nothing on desktop-sized layouts or for popovers', () => {
        let active = false;
        uninstall = installFocusGuard({ isActive: () => active });
        const dialog = mountDialog('<input />');
        expect(dispatchMountAutoFocus(dialog).defaultPrevented).toBe(false);

        active = true;
        const popover = mountDialog('<input />', 'popover-content');
        expect(dispatchMountAutoFocus(popover).defaultPrevented).toBe(false);
    });
});

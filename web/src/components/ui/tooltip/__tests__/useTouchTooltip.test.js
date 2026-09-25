import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { TOOLTIP_LONG_PRESS_MS, isInfoOnlyTrigger, useTouchTooltip } from '../useTouchTooltip';

function pointer(type, target, init = {}) {
    return { pointerType: 'touch', clientX: 10, clientY: 10, currentTarget: target, ...init, type };
}

describe('useTouchTooltip', () => {
    beforeEach(() => {
        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();
        document.body.innerHTML = '';
    });

    it('opens on long-press and swallows the click that ends it', () => {
        const button = document.createElement('button');
        document.body.appendChild(button);
        const { open, triggerListeners: on } = useTouchTooltip({ isEnabled: () => true });

        on.onPointerdownCapture(pointer('pointerdown', button));
        on.onPointerdown(pointer('pointerdown', button));
        vi.advanceTimersByTime(TOOLTIP_LONG_PRESS_MS);
        expect(open.value).toBe(true);

        on.onPointerup(pointer('pointerup', button));
        const click = { preventDefault: vi.fn(), stopImmediatePropagation: vi.fn() };
        on.onClickCapture(click);
        expect(click.preventDefault).toHaveBeenCalled();
        expect(click.stopImmediatePropagation).toHaveBeenCalled();
        expect(open.value).toBe(true);
    });

    it('cancels the long-press when the finger moves (scrolling)', () => {
        const span = document.createElement('span');
        span.textContent = 'label';
        const { open, triggerListeners: on } = useTouchTooltip({ isEnabled: () => true });

        on.onPointerdown(pointer('pointerdown', span));
        on.onPointermove(pointer('pointermove', span, { clientY: 40 }));
        vi.advanceTimersByTime(TOOLTIP_LONG_PRESS_MS * 2);
        expect(open.value).toBe(false);
    });

    it('toggles on tap for information-only triggers and leaves normal taps alone', () => {
        const icon = document.createElement('span'); // an icon: no text, nothing to activate
        const button = document.createElement('button');
        button.textContent = 'Go';
        const { open, triggerListeners: on } = useTouchTooltip({ isEnabled: () => true });

        on.onPointerdownCapture(pointer('pointerdown', icon));
        on.onPointerdown(pointer('pointerdown', icon));
        on.onPointerup(pointer('pointerup', icon));
        expect(open.value).toBe(true);

        on.onPointerdownCapture(pointer('pointerdown', icon));
        on.onPointerdown(pointer('pointerdown', icon));
        on.onPointerup(pointer('pointerup', icon));
        expect(open.value).toBe(false);

        on.onPointerdownCapture(pointer('pointerdown', button));
        on.onPointerdown(pointer('pointerdown', button));
        on.onPointerup(pointer('pointerup', button));
        const click = { preventDefault: vi.fn(), stopImmediatePropagation: vi.fn() };
        on.onClickCapture(click);
        expect(open.value).toBe(false);
        expect(click.preventDefault).not.toHaveBeenCalled();
    });

    it('leaves the long-press to a surrounding context menu, and ignores mouse input', () => {
        const trigger = document.createElement('div');
        trigger.setAttribute('data-slot', 'context-menu-trigger');
        const inner = document.createElement('span');
        inner.textContent = 'row';
        trigger.appendChild(inner);
        const { open, triggerListeners: on } = useTouchTooltip({ isEnabled: () => true });

        on.onPointerdown(pointer('pointerdown', inner));
        vi.advanceTimersByTime(TOOLTIP_LONG_PRESS_MS * 2);
        expect(open.value).toBe(false);

        on.onPointerdown(pointer('pointerdown', document.createElement('span'), { pointerType: 'mouse' }));
        vi.advanceTimersByTime(TOOLTIP_LONG_PRESS_MS * 2);
        expect(open.value).toBe(false);
    });

    it('stays closed while disabled', () => {
        const { open, triggerListeners: on } = useTouchTooltip({ isEnabled: () => false });
        on.onPointerdown(pointer('pointerdown', document.createElement('span')));
        vi.advanceTimersByTime(TOOLTIP_LONG_PRESS_MS * 2);
        expect(open.value).toBe(false);
    });
});

describe('isInfoOnlyTrigger', () => {
    it('is true for text-less, non-interactive elements only', () => {
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        expect(isInfoOnlyTrigger(svg)).toBe(true);
        const text = document.createElement('span');
        text.textContent = '9/25';
        expect(isInfoOnlyTrigger(text)).toBe(false);
        const button = document.createElement('button');
        const inner = document.createElement('i');
        button.appendChild(inner);
        expect(isInfoOnlyTrigger(inner)).toBe(false);
        expect(isInfoOnlyTrigger(null)).toBe(false);
    });

    it('is false for wrappers around something tappable and inside clickable rows', () => {
        // <TooltipWrapper><span><Button size="icon" /></span></TooltipWrapper>
        const wrapper = document.createElement('span');
        const button = document.createElement('button');
        button.appendChild(document.createElement('svg'));
        wrapper.appendChild(button);
        document.body.appendChild(wrapper);
        expect(isInfoOnlyTrigger(wrapper)).toBe(false);

        // A status dot inside a row whose click opens a dialog.
        const row = document.createElement('div');
        row.style.cursor = 'pointer';
        const dot = document.createElement('i');
        row.appendChild(dot);
        document.body.appendChild(row);
        expect(isInfoOnlyTrigger(dot)).toBe(false);

        const plainDot = document.createElement('i');
        document.body.appendChild(plainDot);
        expect(isInfoOnlyTrigger(plainDot)).toBe(true);
        document.body.innerHTML = '';
    });
});

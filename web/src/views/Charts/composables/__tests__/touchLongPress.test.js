import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
    LONG_PRESS_DELAY,
    LONG_PRESS_MOVE_TOLERANCE,
    createLongPress,
    getClientPoint,
    isTouchLikeEvent
} from '../touchLongPress';

describe('isTouchLikeEvent', () => {
    it('recognises touch and pen input only', () => {
        expect(isTouchLikeEvent({ type: 'touchstart' })).toBe(true);
        expect(isTouchLikeEvent({ type: 'pointerdown', pointerType: 'touch' })).toBe(true);
        expect(isTouchLikeEvent({ type: 'pointerdown', pointerType: 'pen' })).toBe(true);
        expect(isTouchLikeEvent({ type: 'pointerdown', pointerType: 'mouse' })).toBe(false);
        expect(isTouchLikeEvent({ type: 'mousedown' })).toBe(false);
        expect(isTouchLikeEvent(null)).toBe(false);
        expect(isTouchLikeEvent(undefined)).toBe(false);
    });
});

describe('getClientPoint', () => {
    it('reads the first finger, the lifted finger or the pointer', () => {
        expect(getClientPoint({ touches: [{ clientX: 10, clientY: 20 }] })).toEqual({ x: 10, y: 20 });
        expect(getClientPoint({ touches: [], changedTouches: [{ clientX: 3, clientY: 4 }] })).toEqual({ x: 3, y: 4 });
        expect(getClientPoint({ clientX: 7, clientY: 8 })).toEqual({ x: 7, y: 8 });
        expect(getClientPoint({})).toBeNull();
        expect(getClientPoint(null)).toBeNull();
    });
});

describe('createLongPress', () => {
    beforeEach(() => {
        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    it('fires once for a press held still', () => {
        const onLongPress = vi.fn();
        const press = createLongPress({ onLongPress });

        press.begin('node-a', { x: 100, y: 100 });
        expect(press.isPending()).toBe(true);
        vi.advanceTimersByTime(LONG_PRESS_DELAY - 1);
        expect(onLongPress).not.toHaveBeenCalled();
        press.move({ x: 103, y: 104 });
        vi.advanceTimersByTime(1);

        expect(onLongPress).toHaveBeenCalledTimes(1);
        expect(onLongPress).toHaveBeenCalledWith('node-a', { x: 100, y: 100 });
        expect(press.isPending()).toBe(false);
    });

    it('turns into a pan once the finger travels past the tolerance', () => {
        const onLongPress = vi.fn();
        const press = createLongPress({ onLongPress });

        press.begin('node-a', { x: 0, y: 0 });
        press.move({ x: LONG_PRESS_MOVE_TOLERANCE + 1, y: 0 });
        vi.advanceTimersByTime(LONG_PRESS_DELAY * 2);

        expect(onLongPress).not.toHaveBeenCalled();
        expect(press.consumeFired()).toBe(false);
    });

    it('stops when cancelled (finger lifted, second finger)', () => {
        const onLongPress = vi.fn();
        const press = createLongPress({ onLongPress });

        press.begin('node-a', { x: 0, y: 0 });
        press.cancel();
        vi.advanceTimersByTime(LONG_PRESS_DELAY * 2);

        expect(onLongPress).not.toHaveBeenCalled();
    });

    it('swallows only the tap that ends a long-press', () => {
        const press = createLongPress({ onLongPress: () => {} });

        press.begin('node-a', { x: 0, y: 0 });
        vi.advanceTimersByTime(LONG_PRESS_DELAY);
        expect(press.consumeFired()).toBe(true);
        expect(press.consumeFired()).toBe(false);

        // A long-press whose tap never came does not eat the next ordinary tap.
        press.begin('node-b', { x: 0, y: 0 });
        vi.advanceTimersByTime(LONG_PRESS_DELAY);
        press.begin('node-c', { x: 0, y: 0 });
        press.cancel();
        expect(press.consumeFired()).toBe(false);
    });

    it('restarts for a new target and ignores a press without coordinates', () => {
        const onLongPress = vi.fn();
        const press = createLongPress({ onLongPress, delay: 100 });

        press.begin('node-a', { x: 0, y: 0 });
        vi.advanceTimersByTime(60);
        press.begin('node-b', { x: 5, y: 5 });
        vi.advanceTimersByTime(60);
        expect(onLongPress).not.toHaveBeenCalled();
        vi.advanceTimersByTime(40);
        expect(onLongPress).toHaveBeenCalledWith('node-b', { x: 5, y: 5 });

        press.begin('node-c', null);
        vi.advanceTimersByTime(200);
        expect(onLongPress).toHaveBeenCalledTimes(1);
    });
});

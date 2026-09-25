import { describe, expect, it } from 'vitest';

import { DOUBLE_TAP_SCALE, MAX_SCALE, doubleTapState, isDoubleTap, pinchState, zoomAtPoint } from '../zoomPan';

const identity = { scale: 1, rotate: 0, tx: 0, ty: 0 };

/** Screen position of an image-space point under a view state (translate, then scale and rotate about the centre). */
function project(state, point) {
    const r = (state.rotate * Math.PI) / 180;
    const x = point.x * state.scale;
    const y = point.y * state.scale;
    return {
        x: x * Math.cos(r) - y * Math.sin(r) + state.tx,
        y: x * Math.sin(r) + y * Math.cos(r) + state.ty
    };
}

describe('zoomAtPoint', () => {
    it('keeps the point under the finger fixed', () => {
        const point = { x: 60, y: -40 };
        const next = zoomAtPoint(identity, point, 2);
        expect(next.scale).toBe(2);
        const imagePoint = { x: 60, y: -40 }; // image point under `point` before zooming
        const screen = project(next, imagePoint);
        expect(screen.x).toBeCloseTo(point.x);
        expect(screen.y).toBeCloseTo(point.y);
    });

    it('also works on a rotated image', () => {
        const rotated = { scale: 1.5, rotate: 90, tx: 10, ty: -5 };
        const point = { x: -30, y: 25 };
        // image point currently under `point`
        const r = (90 * Math.PI) / 180;
        const vx = point.x - rotated.tx;
        const vy = point.y - rotated.ty;
        const imagePoint = {
            x: (vx * Math.cos(r) + vy * Math.sin(r)) / rotated.scale,
            y: (-vx * Math.sin(r) + vy * Math.cos(r)) / rotated.scale
        };
        const next = zoomAtPoint(rotated, point, 1.7);
        const screen = project(next, imagePoint);
        expect(screen.x).toBeCloseTo(point.x);
        expect(screen.y).toBeCloseTo(point.y);
    });

    it('clamps the scale', () => {
        expect(zoomAtPoint(identity, { x: 0, y: 0 }, 1000).scale).toBe(MAX_SCALE);
    });
});

describe('pinch', () => {
    it('scales by the finger distance ratio and follows the midpoint', () => {
        const start = { x: 0, y: 0 };
        const next = pinchState(identity, start, 100, { x: 20, y: 10 }, 200);
        expect(next.scale).toBe(2);
        expect(next.tx).toBeCloseTo(20);
        expect(next.ty).toBeCloseTo(10);
    });

    it('is a no-op when the fingers do not move', () => {
        const state = { scale: 1.4, rotate: 0, tx: 12, ty: 3 };
        const mid = { x: 5, y: 5 };
        const next = pinchState(state, mid, 80, mid, 80);
        expect(next.scale).toBeCloseTo(1.4);
        expect(next.tx).toBeCloseTo(12);
        expect(next.ty).toBeCloseTo(3);
    });
});

describe('double tap', () => {
    it('zooms in on the tapped point, then back to fit', () => {
        const zoomed = doubleTapState(identity, { x: 40, y: 0 });
        expect(zoomed.scale).toBe(DOUBLE_TAP_SCALE);
        expect(project(zoomed, { x: 40, y: 0 }).x).toBeCloseTo(40);

        expect(doubleTapState(zoomed, { x: 0, y: 0 })).toEqual({ ...zoomed, scale: 1, tx: 0, ty: 0 });
    });

    it('needs two taps close in time and space', () => {
        const first = { x: 100, y: 100, time: 1000 };
        expect(isDoubleTap(null, first)).toBe(false);
        expect(isDoubleTap(first, { x: 105, y: 102, time: 1200 })).toBe(true);
        expect(isDoubleTap(first, { x: 105, y: 102, time: 1400 })).toBe(false);
        expect(isDoubleTap(first, { x: 180, y: 100, time: 1100 })).toBe(false);
    });
});

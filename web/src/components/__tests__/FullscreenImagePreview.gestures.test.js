// Pointer gestures of the fullscreen image viewer (docs/DESIGN.md §3.3).
//
// jsdom has no pointer capture, so the tests dispatch each event where the browser would deliver it: with a pointer
// captured by an element, pointerup and the click that follows are retargeted to that element.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

const mocks = vi.hoisted(() => ({
    dialog: {
        value: {
            visible: true,
            imageUrl: 'https://example.com/a.png',
            fileName: 'a.png'
        }
    }
}));

vi.mock('pinia', async (i) => ({ ...(await i()), storeToRefs: (s) => s }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k) => k }) }));
vi.mock('@/stores/settings/general', () => ({
    useGeneralSettingsStore: () => ({ disableGpuAcceleration: { value: false } })
}));
vi.mock('../../stores', () => ({
    useGalleryStore: () => ({ fullscreenImageDialog: mocks.dialog, showFullscreenImageDialog: vi.fn() })
}));
vi.mock('@/lib/modalPortalLayers', () => ({
    acquireModalPortalLayer: () => ({ element: 'body', bringToFront: vi.fn(), release: vi.fn() })
}));
vi.mock('@/lib/utils', () => ({ cn: (...a) => a.filter(Boolean).join(' ') }));
vi.mock('../../shared/utils', () => ({ escapeTag: (s) => s, extractFileId: () => 'f1' }));
vi.mock('vue-sonner', () => ({
    toast: { info: vi.fn(() => 'id'), success: vi.fn(), error: vi.fn(), dismiss: vi.fn() }
}));
vi.mock('@/components/ui/dialog', () => ({ Dialog: { template: '<div><slot /></div>' } }));
// The dialog content closes the viewer on any click that reaches it, like reka's content with @click="closeDialog".
vi.mock('reka-ui', () => ({
    DialogPortal: { template: '<div><slot /></div>' },
    DialogOverlay: { template: '<div />' },
    DialogContent: {
        emits: ['click'],
        template: '<div data-testid="content" @click="$emit(\'click\', $event)"><slot /></div>'
    }
}));
vi.mock('@/components/ui/button', () => ({
    Button: {
        emits: ['click'],
        template: '<button :aria-label="$attrs[\'aria-label\']" @click="$emit(\'click\')"><slot /></button>'
    }
}));
vi.mock('lucide-vue-next', () => {
    const icon = { template: '<i />' };
    return {
        Copy: icon,
        Download: icon,
        RefreshCcw: icon,
        RotateCcw: icon,
        RotateCw: icon,
        X: icon,
        ZoomIn: icon,
        ZoomOut: icon
    };
});

import FullscreenImagePreview from '../FullscreenImagePreview.vue';

let pointerId = 1;

function pointer(type, target, { pointerType = 'touch', x = 100, y = 100, id = pointerId } = {}) {
    target.dispatchEvent(
        new PointerEvent(type, {
            bubbles: true,
            cancelable: true,
            pointerType,
            pointerId: id,
            button: 0,
            clientX: x,
            clientY: y
        })
    );
}

function click(target) {
    target.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
}

/** A tap as the browser delivers it when the stage captured the touch pointer. */
function touchTap(downTarget, stage, point = {}) {
    pointer('pointerdown', downTarget, point);
    pointer('pointerup', stage, point);
    click(stage);
}

describe('FullscreenImagePreview gestures', () => {
    let wrapper;
    let img;
    let stage;

    beforeEach(() => {
        // Only the timers: Vue ignores an event whose timestamp is not newer than the time its listener was attached,
        // which a frozen fake Date would make true for every bubbling handler.
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
        pointerId += 1;
        mocks.dialog.value.visible = true;
        wrapper = mount(FullscreenImagePreview, { attachTo: document.body });
        img = wrapper.get('img').element;
        stage = wrapper.get('[data-slot="viewer-stage"]').element;
        img.setPointerCapture = vi.fn();
        img.releasePointerCapture = vi.fn();
        stage.setPointerCapture = vi.fn();
        stage.releasePointerCapture = vi.fn();
    });

    afterEach(() => {
        wrapper.unmount();
        vi.useRealTimers();
        document.body.innerHTML = '';
    });

    it('captures a mouse drag on the image itself, so the click after it cannot close the viewer', async () => {
        pointer('pointerdown', img, { pointerType: 'mouse' });
        expect(img.setPointerCapture).toHaveBeenCalledTimes(1);
        expect(stage.setPointerCapture).not.toHaveBeenCalled();

        // Captured by the image: pointerup and click are delivered to it.
        pointer('pointermove', img, { pointerType: 'mouse', x: 140, y: 120 });
        pointer('pointerup', img, { pointerType: 'mouse', x: 140, y: 120 });
        click(img);
        await nextTick();

        expect(mocks.dialog.value.visible).toBe(true);
        expect(img.releasePointerCapture).toHaveBeenCalledTimes(1);
        expect(img.getAttribute('style')).toContain('translate(40px, 20px)');
    });

    it('still closes on a mouse click on the backdrop (upstream behaviour)', () => {
        pointer('pointerdown', stage, { pointerType: 'mouse' });
        pointer('pointerup', stage, { pointerType: 'mouse' });
        click(stage);

        expect(stage.setPointerCapture).not.toHaveBeenCalled();
        expect(mocks.dialog.value.visible).toBe(false);
    });

    it('keeps the viewer open after a single tap on the image, even when the click is retargeted to the stage', () => {
        touchTap(img, stage);
        vi.advanceTimersByTime(1000);

        expect(mocks.dialog.value.visible).toBe(true);
    });

    it('zooms in on a double-tap on the image and back out on the next one', async () => {
        touchTap(img, stage);
        touchTap(img, stage);
        await nextTick();

        expect(mocks.dialog.value.visible).toBe(true);
        expect(img.getAttribute('style')).toContain('scale(2.5)');

        touchTap(img, stage);
        touchTap(img, stage);
        await nextTick();
        expect(img.getAttribute('style')).toContain('scale(1)');
        vi.advanceTimersByTime(1000);
        expect(mocks.dialog.value.visible).toBe(true);
    });

    it('closes on a backdrop tap only once the double-tap window has passed', () => {
        touchTap(stage, stage);
        expect(mocks.dialog.value.visible).toBe(true);

        vi.advanceTimersByTime(299);
        expect(mocks.dialog.value.visible).toBe(true);
        vi.advanceTimersByTime(1);
        expect(mocks.dialog.value.visible).toBe(false);
    });

    it('treats a double-tap beside the image as a zoom, not a close', async () => {
        touchTap(stage, stage);
        touchTap(stage, stage);
        vi.advanceTimersByTime(1000);
        await nextTick();

        expect(mocks.dialog.value.visible).toBe(true);
        expect(img.getAttribute('style')).toContain('scale(2.5)');
    });

    it('does not close after a one-finger pan that ends beside the image', () => {
        pointer('pointerdown', img, { x: 100, y: 100 });
        pointer('pointermove', stage, { x: 180, y: 100 });
        pointer('pointerup', stage, { x: 180, y: 100 });
        click(stage);
        vi.advanceTimersByTime(1000);

        expect(mocks.dialog.value.visible).toBe(true);
    });

    it('lets the next toolbar tap through after a gesture that produced no click', async () => {
        // A pan: the browser sends no click at its end.
        pointer('pointerdown', img, { x: 100, y: 100 });
        pointer('pointermove', stage, { x: 180, y: 100 });
        pointer('pointerup', stage, { x: 180, y: 100 });

        const close = wrapper.get('button[aria-label="dialog.shared_feed_filters.close"]').element;
        pointer('pointerdown', close, { id: pointerId + 100 });
        pointer('pointerup', close, { id: pointerId + 100 });
        click(close);
        await nextTick();

        expect(mocks.dialog.value.visible).toBe(false);
    });
});

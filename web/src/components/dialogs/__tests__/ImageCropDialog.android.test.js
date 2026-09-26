import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

const mocks = vi.hoisted(() => ({
    refresh: vi.fn(),
    observers: []
}));

// Android: after a rotation the phone layout changes the cropper's box once the <html> classes follow the new
// orientation, which is after the window resize event the cropper refreshes on. The dialog refreshes it from a
// ResizeObserver instead.
vi.mock('../../../shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true
}));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k) => k }) }));
vi.mock('../../../composables/useImageCropper', async () => {
    const { ref } = await import('vue');
    const cropperRef = ref(null);
    const cropperImageSrc = ref('blob://img');
    return {
        useImageCropper: () => ({
            cropperRef,
            cropperImageSrc,
            resetCropState: vi.fn(),
            loadImageForCrop: vi.fn(),
            getCroppedBlob: vi.fn()
        })
    };
});
vi.mock('@/components/ui/dialog', () => ({
    Dialog: { template: '<div><slot /></div>' },
    DialogContent: { template: '<div><slot /></div>' },
    DialogHeader: { template: '<div><slot /></div>' },
    DialogTitle: { template: '<div><slot /></div>' },
    DialogFooter: { template: '<div><slot /></div>' }
}));
vi.mock('@/components/ui/button', () => ({
    Button: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' }
}));
vi.mock('@/components/ui/slider', () => ({ Slider: { template: '<div />' } }));
vi.mock('@/components/ui/spinner', () => ({ Spinner: { template: '<div />' } }));
vi.mock('@/components/ui/tooltip/TooltipWrapper.vue', () => ({ default: { template: '<div><slot /></div>' } }));
vi.mock('vue-advanced-cropper', () => ({
    Cropper: {
        template: '<div class="cropper-stub" />',
        methods: {
            refresh() {
                mocks.refresh();
            }
        }
    }
}));
vi.mock('lucide-vue-next', () =>
    Object.fromEntries(
        [
            'Expand',
            'FlipHorizontal',
            'FlipVertical',
            'Frame',
            'RefreshCw',
            'RotateCcw',
            'RotateCw',
            'ZoomIn',
            'ZoomOut'
        ].map((name) => [name, { template: '<i />' }])
    )
);

import ImageCropDialog from '../ImageCropDialog.vue';

class FakeResizeObserver {
    constructor(callback) {
        this.callback = callback;
        this.targets = [];
        this.disconnect = vi.fn();
        mocks.observers.push(this);
    }

    observe(target) {
        this.targets.push(target);
    }

    fire() {
        this.callback(this.targets.map((target) => ({ target })));
    }
}

describe('ImageCropDialog.vue on Android', () => {
    beforeEach(() => {
        mocks.refresh.mockClear();
        mocks.observers.length = 0;
        vi.stubGlobal('ResizeObserver', FakeResizeObserver);
        vi.stubGlobal('requestAnimationFrame', (callback) => {
            callback();
            return 1;
        });
        vi.stubGlobal('cancelAnimationFrame', () => {});
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('measures the cropper again when its box changes size, not for the initial size', async () => {
        const wrapper = mount(ImageCropDialog, { props: { open: true, title: 'Crop', aspectRatio: 1, file: null } });
        await nextTick();

        expect(mocks.observers).toHaveLength(1);
        const [observer] = mocks.observers;
        expect(observer.targets).toEqual([wrapper.find('.cropper-stub').element]);

        observer.fire();
        expect(mocks.refresh).not.toHaveBeenCalled();

        observer.fire();
        expect(mocks.refresh).toHaveBeenCalledTimes(1);

        wrapper.unmount();
        expect(observer.disconnect).toHaveBeenCalled();
    });
});

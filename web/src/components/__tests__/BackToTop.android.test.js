import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

// Android: the scroll position is read once per animation frame instead of in every scroll event.
vi.mock('@/shared/utils/platform', async (importOriginal) => ({ ...(await importOriginal()), isAndroid: true }));
vi.mock('@/components/ui/tooltip', () => ({
    Tooltip: { template: '<div><slot /></div>' },
    TooltipTrigger: { template: '<div><slot /></div>' },
    TooltipContent: { template: '<div><slot /></div>' }
}));
vi.mock('@/components/ui/button', () => ({
    Button: { template: '<button data-testid="back-btn"><slot /></button>' }
}));
vi.mock('lucide-vue-next', () => ({ ArrowUp: { template: '<i />' } }));
vi.mock('vue-i18n', async (importOriginal) => ({
    ...(await importOriginal()),
    useI18n: () => ({ t: (key) => key })
}));

import BackToTop from '../BackToTop.vue';

describe('BackToTop on Android', () => {
    let frames;

    beforeEach(() => {
        frames = [];
        vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
            frames.push(callback);
            return frames.length;
        });
        vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => {});
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('reads the scroll position once per frame', async () => {
        const container = document.createElement('div');
        let reads = 0;
        Object.defineProperty(container, 'scrollTop', {
            configurable: true,
            get: () => {
                reads++;
                return 500;
            }
        });
        document.body.appendChild(container);
        const wrapper = mount(BackToTop, {
            props: { target: container, visibilityHeight: 100, teleport: false, tooltip: false }
        });
        reads = 0;

        for (let i = 0; i < 5; i++) container.dispatchEvent(new Event('scroll'));
        expect(reads).toBe(0);
        expect(frames).toHaveLength(1);

        frames[0]();
        await nextTick();
        // One position check for the five events (getScrollTop looks at scrollTop twice).
        expect(reads).toBe(2);
        expect(wrapper.find('[data-testid="back-btn"]').exists()).toBe(true);

        container.dispatchEvent(new Event('scroll'));
        expect(frames).toHaveLength(2);

        wrapper.unmount();
        expect(window.cancelAnimationFrame).toHaveBeenCalledWith(2);
        container.remove();
    });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, ref } from 'vue';
import { mount } from '@vue/test-utils';

const mocks = vi.hoisted(() => ({
    getString: vi.fn(async (_key, fallback) => fallback),
    setString: vi.fn()
}));

vi.mock('../../../../services/config.js', () => ({
    default: {
        getString: (...args) => mocks.getString(...args),
        setString: (...args) => mocks.setString(...args)
    }
}));

import { resolveCardMinWidth, useFavoritesCardScaling } from '../useFavoritesCardScaling';

function mountComposable() {
    let api;
    const Comp = defineComponent({
        setup() {
            api = useFavoritesCardScaling({
                configKey: 'scale-key',
                spacingConfigKey: 'spacing-key'
            });
            return () => h('div');
        }
    });
    mount(Comp);
    return api;
}

describe('useFavoritesCardScaling', () => {
    beforeEach(() => {
        mocks.getString.mockClear();
        mocks.setString.mockClear();
    });

    it('builds grid style css vars from scale/spacing', async () => {
        const api = mountComposable();
        api.cardScale.value = 0.8;
        api.cardSpacing.value = 1.2;

        const style = api.gridStyle.value(3, { preferredColumns: 2 });

        expect(style['--favorites-card-scale']).toBe('0.80');
        expect(style['--favorites-card-spacing-scale']).toBe('1.20');
        expect(Number(style['--favorites-grid-columns'])).toBeGreaterThanOrEqual(1);
        expect(mocks.setString).toHaveBeenCalledWith('scale-key', '0.8');
        expect(mocks.setString).toHaveBeenCalledWith('spacing-key', '1.2');
    });
});

describe('resolveCardMinWidth', () => {
    it('keeps the slider width unless the grid is a single column', () => {
        expect(resolveCardMinWidth(260, 330, Infinity)).toBe(260);
        expect(resolveCardMinWidth(260, 330, 2)).toBe(260);
        expect(resolveCardMinWidth(260, 330, 1)).toBe(260);
    });

    it('caps a single column at the width the list can use', () => {
        // 250 - 16 reserve
        expect(resolveCardMinWidth(260, 250, 1)).toBe(234);
        expect(resolveCardMinWidth(260, 250, 1, { reserve: 10 })).toBe(240);
    });

    it('waits for the container to be measured', () => {
        expect(resolveCardMinWidth(260, 0, 1)).toBe(260);
    });
});

describe('useFavoritesCardScaling maxColumns', () => {
    async function mountMeasured(width, options) {
        let api;
        const Comp = defineComponent({
            setup() {
                api = useFavoritesCardScaling(options);
                return () => h('div', { ref: api.containerRef });
            }
        });
        mount(Comp);
        // The composable measures the container on the tick after it is attached.
        api.containerRef.value = api.containerRef.value.cloneNode();
        Object.defineProperty(api.containerRef.value, 'clientWidth', { configurable: true, value: width });
        await nextTick();
        await nextTick();
        return api;
    }

    it('lays out full-width rows on a phone, the PC grid otherwise', async () => {
        const portrait = ref(true);
        const api = await mountMeasured(700, { maxColumns: () => (portrait.value ? 1 : Infinity) });

        const phone = api.gridStyle.value(5);
        expect(phone['--favorites-grid-columns']).toBe('1');
        expect(phone['--favorites-card-min-width']).toBe('260px');
        expect(phone['--favorites-card-target-width']).toBe('700px');

        portrait.value = false;
        const pc = api.gridStyle.value(5);
        expect(pc['--favorites-grid-columns']).toBe('2');
        expect(pc['--favorites-card-min-width']).toBe('260px');
    });

    it('keeps the smallest card scale on one column', async () => {
        const api = await mountMeasured(330, { maxColumns: 1 });
        api.cardScale.value = 0.6;
        expect(api.gridStyle.value(5)['--favorites-grid-columns']).toBe('1');
    });

    it('never makes a single column wider than a narrow container', async () => {
        const api = await mountMeasured(250, { maxColumns: ref(1) });
        const style = api.gridStyle.value(3);
        expect(style['--favorites-grid-columns']).toBe('1');
        expect(style['--favorites-card-min-width']).toBe('234px');
    });

    it('keeps the upstream grid without the option', async () => {
        const api = await mountMeasured(330, {});
        api.cardScale.value = 0.6;
        // 156 px cards: two per row, as on PC.
        expect(api.gridStyle.value(5)['--favorites-grid-columns']).toBe('2');
        expect(api.gridStyle.value(5)['--favorites-card-min-width']).toBe('156px');
    });
});

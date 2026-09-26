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
    it('keeps the slider width when one column is enough or nothing is measured', () => {
        expect(resolveCardMinWidth(260, 330, 12, 1)).toBe(260);
        expect(resolveCardMinWidth(260, 0, 12, 2)).toBe(260);
    });

    it('shrinks cards so the wanted columns share a row', () => {
        // (330 - 16 reserve - 12 gap) / 2 = 151
        expect(resolveCardMinWidth(260, 330, 12, 2)).toBe(151);
        // Already narrow enough: unchanged.
        expect(resolveCardMinWidth(140, 700, 12, 2)).toBe(140);
    });

    it('does not force columns narrower than the floor', () => {
        expect(resolveCardMinWidth(260, 200, 12, 2)).toBe(260);
        expect(resolveCardMinWidth(260, 200, 12, 2, { floorWidth: 80 })).toBe(86);
    });
});

describe('useFavoritesCardScaling minColumns', () => {
    it('lays out two cards per row on a phone-width container, one column otherwise', async () => {
        const compact = ref(true);
        let api;
        const Comp = defineComponent({
            setup() {
                api = useFavoritesCardScaling({ minColumns: () => (compact.value ? 2 : 1) });
                return () => h('div', { ref: api.containerRef });
            }
        });
        mount(Comp);
        Object.defineProperty(api.containerRef.value, 'clientWidth', { configurable: true, value: 330 });
        // The composable measures the container on the tick after it is attached.
        api.containerRef.value = api.containerRef.value.cloneNode();
        Object.defineProperty(api.containerRef.value, 'clientWidth', { configurable: true, value: 330 });
        await nextTick();
        await nextTick();

        const phone = api.gridStyle.value(5);
        expect(phone['--favorites-grid-columns']).toBe('2');
        expect(phone['--favorites-card-min-width']).toBe('151px');

        compact.value = false;
        const pc = api.gridStyle.value(5);
        expect(pc['--favorites-grid-columns']).toBe('1');
        expect(pc['--favorites-card-min-width']).toBe('260px');
    });
});

import { describe, expect, test, vi } from 'vitest';

// echarts leaves the start-up chunk: Android loads it with the first chart, desktop right after start
// (shared/utils/chart.js).
describe('echarts loading', () => {
    test('Android does not load echarts until a chart asks for it', async () => {
        vi.resetModules();
        const factory = vi.fn(() => ({ marker: 'mock-echarts', init: vi.fn() }));
        vi.doMock('echarts', factory);
        vi.doMock('../platform', () => ({ isAndroid: true }));
        const chart = await import('../chart.js');
        await new Promise((resolve) => setTimeout(resolve, 0));

        expect(factory).not.toHaveBeenCalled();
        expect(chart.echarts).toBeNull();

        const module = await chart.loadEcharts();
        expect(module).toMatchObject({ marker: 'mock-echarts' });
        // The live binding the chart components read.
        expect(chart.echarts).toBe(module);
        vi.doUnmock('../platform');
    });

    test('desktop loads echarts right after start, as the static imports did', async () => {
        vi.resetModules();
        vi.doMock('echarts', () => ({ marker: 'mock-echarts' }));
        vi.doMock('../platform', () => ({ isAndroid: false }));
        const chart = await import('../chart.js');
        await vi.waitFor(() => expect(chart.echarts).toMatchObject({ marker: 'mock-echarts' }));
        vi.doUnmock('../platform');
    });
});

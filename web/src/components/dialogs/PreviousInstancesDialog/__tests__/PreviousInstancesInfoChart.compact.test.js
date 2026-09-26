// Phones: the Previous Instances activity chart uses a narrower label column.
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

import { formatInstanceChartLabel, getInstanceChartLayout } from '../previousInstancesChartLayout';

const mocks = vi.hoisted(() => ({ compact: null, chart: null }));

vi.mock('echarts', () => ({ init: vi.fn(() => mocks.chart) }));
vi.mock('@/composables/useCompactLayout', async () => {
    const { ref: vueRef } = await import('vue');
    mocks.compact = vueRef(false);
    return { useCompactLayout: () => ({ isCompact: mocks.compact }) };
});
vi.mock('../../../../stores', async () => {
    const { ref: vueRef } = await import('vue');
    return {
        useAppearanceSettingsStore: () => ({ isDarkMode: vueRef(false), dtHour12: vueRef(false) }),
        useUserStore: () => ({ currentUser: vueRef({ id: 'usr_me' }) }),
        useGameLogStore: () => ({ gameLogIsFriend: () => false, gameLogIsFavorite: () => false })
    };
});
vi.mock('../../../../shared/utils', () => ({ timeToText: () => '1m' }));
vi.mock('../../../../coordinators/userCoordinator', () => ({ showUserDialog: vi.fn() }));
vi.mock('@/components/ui/data-table', () => ({ DataTableEmpty: { template: '<div />' } }));

import PreviousInstancesInfoChart from '../PreviousInstancesInfoChart.vue';

const CHART_DATA = [
    {
        created_at: '2026-01-01T11:00:00.000Z',
        display_name: 'VeryLongDisplayNameForTesting',
        user_id: 'usr_a',
        time: 60000
    },
    { created_at: '2026-01-01T11:30:00.000Z', display_name: 'Short', user_id: 'usr_b', time: 120000 }
];

function fakeChart() {
    return {
        resize: vi.fn(),
        off: vi.fn(),
        on: vi.fn(),
        clear: vi.fn(),
        setOption: vi.fn(),
        dispose: vi.fn()
    };
}

async function renderedOption() {
    const wrapper = mount(PreviousInstancesInfoChart, { props: { chartData: CHART_DATA } });
    await nextTick();
    await nextTick();
    vi.advanceTimersByTime(60);
    const calls = mocks.chart.setOption.mock.calls;
    wrapper.unmount();
    return calls[calls.length - 1][0];
}

describe('previousInstancesChartLayout', () => {
    test('keeps the PC grid and labels outside compact layout', () => {
        expect(getInstanceChartLayout(false)).toEqual({
            grid: { top: 50, left: 160, right: 90 },
            labelMaxLength: 20,
            confineTooltip: false
        });
    });

    test('narrows the label column on phones', () => {
        expect(getInstanceChartLayout(true)).toEqual({
            grid: { top: 50, left: 96, right: 16 },
            labelMaxLength: 12,
            confineTooltip: true
        });
    });

    test('cuts long names and keeps the icon', () => {
        expect(formatInstanceChartLabel('⭐', 'VeryLongDisplayNameForTesting', 12)).toBe('⭐ VeryLongDisp...');
        expect(formatInstanceChartLabel('', 'Short', 12)).toBe(' Short');
        expect(formatInstanceChartLabel('💚', 'ExactlyTwelv', 12)).toBe('💚 ExactlyTwelv');
    });
});

describe('PreviousInstancesInfoChart', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        mocks.chart = fakeChart();
        mocks.compact.value = false;
        globalThis.ResizeObserver = class {
            observe() {}
            disconnect() {}
        };
    });

    test('renders the PC layout on desktop', async () => {
        const option = await renderedOption();
        expect(option.grid).toEqual({ top: 50, left: 160, right: 90 });
        expect(option.tooltip).not.toHaveProperty('confine');
        expect(option.yAxis.axisLabel.formatter('VeryLongDisplayNameForTesting')).toBe(' VeryLongDisplayNameF...');
    });

    test('renders the phone layout in compact layout', async () => {
        mocks.compact.value = true;
        const option = await renderedOption();
        expect(option.grid).toEqual({ top: 50, left: 96, right: 16 });
        expect(option.tooltip.confine).toBe(true);
        expect(option.yAxis.axisLabel.formatter('VeryLongDisplayNameForTesting')).toBe(' VeryLongDisp...');
    });

    test('re-renders when the layout changes', async () => {
        const wrapper = mount(PreviousInstancesInfoChart, { props: { chartData: CHART_DATA } });
        await nextTick();
        await nextTick();
        vi.advanceTimersByTime(60);
        mocks.compact.value = true;
        await nextTick();
        vi.advanceTimersByTime(60);
        const calls = mocks.chart.setOption.mock.calls;
        expect(calls[calls.length - 1][0].grid.left).toBe(96);
        wrapper.unmount();
    });
});

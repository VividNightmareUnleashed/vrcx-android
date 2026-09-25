import { describe, expect, it } from 'vitest';

import {
    COMPACT_LABEL_MAX_WIDTH,
    COMPACT_LABEL_MIN_WIDTH,
    applyCompactChartLayout,
    getCompactLabelWidth
} from '../compactChartLayout';

function baseOption() {
    return {
        tooltip: { trigger: 'axis', formatter: () => '' },
        grid: { top: 50, left: 160, right: 90 },
        yAxis: { type: 'category', axisLabel: { interval: 0, rich: { filtered: { opacity: 0.4 } } }, data: ['A'] },
        xAxis: { type: 'value', min: 0, max: 86_400_000, interval: 3 * 3_600_000 },
        series: []
    };
}

describe('getCompactLabelWidth', () => {
    it('follows the chart width within the phone bounds', () => {
        expect(getCompactLabelWidth(330)).toBe(Math.round(330 * 0.32));
        expect(getCompactLabelWidth(100)).toBe(COMPACT_LABEL_MIN_WIDTH);
        expect(getCompactLabelWidth(2000)).toBe(COMPACT_LABEL_MAX_WIDTH);
    });

    it('assumes a phone width before the chart is measured', () => {
        expect(getCompactLabelWidth(0)).toBe(getCompactLabelWidth(360));
        expect(getCompactLabelWidth(undefined)).toBe(getCompactLabelWidth(360));
    });
});

describe('applyCompactChartLayout', () => {
    it('returns the PC option untouched when not compact', () => {
        const option = baseOption();
        expect(applyCompactChartLayout(option, { compact: false, width: 330 })).toBe(option);
    });

    it('keeps the name labels inside a narrow grid and truncates them', () => {
        const option = baseOption();
        const result = applyCompactChartLayout(option, { compact: true, width: 330 });

        expect(result.grid).toEqual({
            top: 50,
            left: 8,
            right: 16,
            outerBoundsMode: 'same',
            outerBoundsContain: 'axisLabel'
        });
        expect(result.yAxis.axisLabel).toMatchObject({
            interval: 0,
            rich: option.yAxis.axisLabel.rich,
            width: getCompactLabelWidth(330),
            overflow: 'truncate'
        });
        expect(result.yAxis.data).toBe(option.yAxis.data);
        expect(result.tooltip.confine).toBe(true);
        expect(result.tooltip.formatter).toBe(option.tooltip.formatter);
        // The input is not mutated (the PC option object stays as built).
        expect(option.grid.left).toBe(160);
        expect(option.yAxis.axisLabel.overflow).toBeUndefined();
    });

    it('doubles the time tick interval on narrow charts only', () => {
        expect(applyCompactChartLayout(baseOption(), { compact: true, width: 330 }).xAxis.interval).toBe(
            6 * 3_600_000
        );
        expect(applyCompactChartLayout(baseOption(), { compact: true, width: 700 }).xAxis.interval).toBe(
            3 * 3_600_000
        );
    });

    it('leaves automatic x ticks alone', () => {
        const option = baseOption();
        delete option.xAxis.interval;
        const result = applyCompactChartLayout(option, { compact: true, width: 330 });
        expect(result.xAxis).toBe(option.xAxis);
    });

    it('passes through placeholder options without axes', () => {
        const placeholder = { title: { text: 'No data' } };
        expect(applyCompactChartLayout(placeholder, { compact: true, width: 330 })).toBe(placeholder);
    });
});

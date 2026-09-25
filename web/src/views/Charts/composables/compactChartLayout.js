// Phone layout for the Instance Activity bar charts (docs/DESIGN.md §3.4).
//
// On PC the charts reserve fixed 160/90 px margins for the world and player names. A phone is ~330 px wide, so the
// names get a width that follows the chart, are truncated with an ellipsis, and the grid keeps them inside the canvas
// (ECharts 6 outer bounds, the successor of grid.containLabel). The tooltip stays inside the chart.

/** Narrowest and widest name column on phones (px). */
export const COMPACT_LABEL_MIN_WIDTH = 72;
export const COMPACT_LABEL_MAX_WIDTH = 140;

/** Below this chart width the 3-hour time ticks of the day chart are doubled (px). */
export const COMPACT_NARROW_WIDTH = 400;

/**
 * @param {number} chartWidth Width of the chart element (px); 0 when not measured yet
 * @returns {number} Width of the y-axis name labels (px)
 */
export function getCompactLabelWidth(chartWidth) {
    const width = Number(chartWidth) > 0 ? Number(chartWidth) : 360;
    return Math.round(Math.min(COMPACT_LABEL_MAX_WIDTH, Math.max(COMPACT_LABEL_MIN_WIDTH, width * 0.32)));
}

/**
 * Returns the ECharts option adapted to a phone, or the option itself when not compact (the PC chart is unchanged).
 *
 * @param {object} option ECharts option with `grid`, `yAxis`, `xAxis` and `tooltip` objects
 * @param {object} context
 * @param {boolean} context.compact Phone layout
 * @param {number} [context.width] Chart element width (px)
 * @returns {object}
 */
export function applyCompactChartLayout(option, { compact, width = 0 }) {
    if (!compact || !option || !option.yAxis) {
        return option;
    }
    const xAxis = option.xAxis ?? {};
    const narrow = Number(width) > 0 && Number(width) < COMPACT_NARROW_WIDTH;
    return {
        ...option,
        grid: {
            ...option.grid,
            left: 8,
            right: 16,
            outerBoundsMode: 'same',
            outerBoundsContain: 'axisLabel'
        },
        yAxis: {
            ...option.yAxis,
            axisLabel: {
                ...option.yAxis.axisLabel,
                width: getCompactLabelWidth(width),
                overflow: 'truncate'
            }
        },
        xAxis: narrow && typeof xAxis.interval === 'number' ? { ...xAxis, interval: xAxis.interval * 2 } : xAxis,
        tooltip: { ...option.tooltip, confine: true }
    };
}

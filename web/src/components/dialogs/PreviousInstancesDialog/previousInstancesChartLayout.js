// Layout of the Previous Instances activity chart (PreviousInstancesInfoChart.vue): the PC values, and a phone
// variant for compact layout that leaves the bars most of a 360 px screen.

const PC_LAYOUT = Object.freeze({
    grid: Object.freeze({ top: 50, left: 160, right: 90 }),
    labelMaxLength: 20,
    confineTooltip: false
});

const COMPACT_LAYOUT = Object.freeze({
    grid: Object.freeze({ top: 50, left: 96, right: 16 }),
    labelMaxLength: 12,
    // The tooltip stays inside the chart instead of running off the side of the screen.
    confineTooltip: true
});

/**
 * @param {boolean} compact Phone (compact) layout
 * @returns {{ grid: { top: number; left: number; right: number }; labelMaxLength: number; confineTooltip: boolean }}
 */
export function getInstanceChartLayout(compact) {
    return compact ? COMPACT_LAYOUT : PC_LAYOUT;
}

/**
 * Y-axis label: the friend/favourite icon, then the display name cut to `maxLength` characters.
 *
 * @param {string} icon '⭐', '💚' or ''
 * @param {string} value Display name
 * @param {number} maxLength
 * @returns {string}
 */
export function formatInstanceChartLabel(icon, value, maxLength) {
    return `${icon} ${value.length > maxLength ? `${value.substring(0, maxLength)}...` : value}`;
}

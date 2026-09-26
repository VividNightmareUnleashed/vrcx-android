import { isAndroid } from './platform';

// The echarts module once loaded (a live binding: importers see it as soon as loadEcharts resolved). Chart components
// check it before echarts.init and call loadEcharts first while it is null.
let echarts = null;

// lazy load echarts
function loadEcharts() {
    if (echarts) {
        return Promise.resolve(echarts);
    }
    return import('echarts').then((module) => {
        echarts = module;
        return echarts;
    });
}

// Desktop loads echarts right away, as when the chart components imported it statically. Android keeps its 1.1 MB out
// of start-up and loads it with the first chart (docs/ARCHITECTURE.md §7).
if (!isAndroid) {
    loadEcharts().catch((error) => console.error('Failed to load echarts', error));
}

export { echarts, loadEcharts };

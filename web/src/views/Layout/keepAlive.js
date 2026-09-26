// KeepAlive contract of the routed page (MainLayout → RoutedView).

/** Views that are rebuilt on every visit (heavy charts). */
export const KEEP_ALIVE_EXCLUDE = 'ChartsInstance, ChartsMutual';

/** The phone layout caps the cache (docs/ARCHITECTURE.md §7); the PC frame keeps every view alive. */
export const COMPACT_KEEP_ALIVE_MAX = 6;

/** Android tablets use the PC frame, but a tablet's memory is capped too (docs/DESIGN.md §7). */
export const TABLET_KEEP_ALIVE_MAX = 8;

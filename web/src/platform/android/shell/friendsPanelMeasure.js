// Row measurement of the friends list (views/Sidebar/components/FriendsSidebar.vue) in the phone frame.
//
// The phone friends panel stays mounted while it is closed (docs/DESIGN.md §2.2). Measuring its rows forces a
// synchronous layout on every friend update, which nobody sees while the panel is closed (about 0.75 s of start-up
// on a slow phone with 500 friends). So while it is closed the rows keep the virtualizer's size estimates, and the
// list is measured once when the panel opens.
import { computed, nextTick, watch } from 'vue';

import { useCompactLayout } from '../../../composables/useCompactLayout';
import { shellState } from './shellState';

/**
 * @param {import('vue').Ref<{ measureElement: (el: Element | null) => void; measure: () => void } | undefined>} virtualizer
 * @param {object} [deps]
 * @param {import('vue').Ref<boolean>} [deps.isCompact]
 * @param {{ friendsPanelOpen: boolean }} [deps.shell]
 * @returns {{ measureRow: (el: Element | null) => void; isPanelClosed: import('vue').ComputedRef<boolean> }}
 */
export function useFriendsPanelMeasure(
    virtualizer,
    { isCompact = useCompactLayout().isCompact, shell = shellState } = {}
) {
    const isPanelClosed = computed(() => isCompact.value && !shell.friendsPanelOpen);

    /**
     * Function ref of a row. `null` (a row leaving) always passes through, so the virtualizer drops rows it
     * measured earlier.
     *
     * @param {Element | null} el
     */
    function measureRow(el) {
        if (el && isPanelClosed.value) return;
        virtualizer.value?.measureElement(el);
    }

    watch(isPanelClosed, (closed) => {
        if (!closed) {
            nextTick(() => virtualizer.value?.measure?.());
        }
    });

    return { measureRow, isPanelClosed };
}

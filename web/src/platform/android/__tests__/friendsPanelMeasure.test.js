import { describe, expect, test, vi } from 'vitest';
import { nextTick, reactive, ref } from 'vue';

// Row measurement of the phone friends panel while it is closed (docs/DESIGN.md §7).
import { useFriendsPanelMeasure } from '../shell/friendsPanelMeasure.js';

describe('useFriendsPanelMeasure', () => {
    function setup({ compact = true, open = false } = {}) {
        const virtualizer = ref({ measureElement: vi.fn(), measure: vi.fn() });
        const isCompact = ref(compact);
        const shell = reactive({ friendsPanelOpen: open });
        const { measureRow, isPanelClosed } = useFriendsPanelMeasure(virtualizer, { isCompact, shell });
        return { virtualizer, isCompact, shell, measureRow, isPanelClosed };
    }

    test('does not measure rows of the closed phone panel, but lets leaving rows through', () => {
        const { virtualizer, measureRow, isPanelClosed } = setup();
        expect(isPanelClosed.value).toBe(true);
        const row = document.createElement('div');
        measureRow(row);
        expect(virtualizer.value.measureElement).not.toHaveBeenCalled();
        measureRow(null);
        expect(virtualizer.value.measureElement).toHaveBeenCalledWith(null);
    });

    test('measures the list once when the panel opens, then every row', async () => {
        const { virtualizer, shell, measureRow } = setup();
        shell.friendsPanelOpen = true;
        await nextTick();
        await nextTick();
        expect(virtualizer.value.measure).toHaveBeenCalledTimes(1);
        const row = document.createElement('div');
        measureRow(row);
        expect(virtualizer.value.measureElement).toHaveBeenCalledWith(row);
    });

    test('the tablet frame (not compact) always measures', () => {
        const { virtualizer, measureRow } = setup({ compact: false });
        const row = document.createElement('div');
        measureRow(row);
        expect(virtualizer.value.measureElement).toHaveBeenCalledWith(row);
    });
});

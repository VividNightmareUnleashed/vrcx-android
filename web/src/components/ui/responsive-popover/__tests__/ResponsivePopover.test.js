import { afterEach, describe, expect, test, vi } from 'vitest';
import { h, nextTick, ref } from 'vue';
import { mount } from '@vue/test-utils';

const layout = vi.hoisted(() => ({ isCompact: null }));

vi.mock('../../../../composables/useCompactLayout', async () => {
    const { ref: vueRef } = await import('vue');
    layout.isCompact = vueRef(false);
    return { useCompactLayout: () => ({ isCompact: layout.isCompact }) };
});

import ResponsivePopover from '../ResponsivePopover.vue';

function mountPopover(open) {
    return mount(ResponsivePopover, {
        attachTo: document.body,
        props: {
            open: open.value,
            title: 'Filter',
            contentClass: 'w-auto',
            'onUpdate:open': (value) => {
                open.value = value;
            }
        },
        slots: {
            trigger: () => h('button', { class: 'trigger' }, 'Filter'),
            default: () => h('div', { class: 'popover-body' }, 'Calendar')
        }
    });
}

describe('ResponsivePopover', () => {
    afterEach(() => {
        document.body.innerHTML = '';
        layout.isCompact.value = false;
    });

    test('PC: a popover without a sheet header', async () => {
        const open = ref(true);
        const wrapper = mountPopover(open);
        await nextTick();

        expect(document.querySelector('[data-slot="popover-content"] .popover-body')).not.toBeNull();
        expect(document.querySelector('[data-testid="responsive-popover-sheet"]')).toBeNull();
        wrapper.unmount();
    });

    test('phones: the same content in a bottom sheet with a title', async () => {
        layout.isCompact.value = true;
        const open = ref(true);
        const wrapper = mountPopover(open);
        await nextTick();

        const sheet = document.querySelector('[data-testid="responsive-popover-sheet"]');
        expect(sheet).not.toBeNull();
        expect(sheet.querySelector('.popover-body')).not.toBeNull();
        expect(sheet.textContent).toContain('Filter');
        expect(document.querySelector('[data-slot="popover-content"]')).toBeNull();
        wrapper.unmount();
    });

    test('the trigger toggles the open state in both layouts', async () => {
        for (const compact of [false, true]) {
            layout.isCompact.value = compact;
            const open = ref(false);
            const wrapper = mountPopover(open);
            await wrapper.find('.trigger').trigger('click');
            expect(open.value).toBe(true);
            wrapper.unmount();
        }
    });
});

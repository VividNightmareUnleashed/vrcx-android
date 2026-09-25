import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { ref } from 'vue';

const compact = vi.hoisted(() => ({ isCompact: null }));

vi.mock('../../composables/useCompactLayout', () => ({
    useCompactLayout: () => ({ isCompact: compact.isCompact })
}));

vi.mock('../ui/dropdown-menu', () => ({
    DropdownMenuSub: { template: '<div data-testid="sub"><slot /></div>' },
    DropdownMenuSubTrigger: {
        emits: ['click', 'pointerdown'],
        template:
            '<button data-testid="sub-trigger" @pointerdown="$emit(\'pointerdown\', $event)" @click="$emit(\'click\', $event)"><slot /></button>'
    },
    DropdownMenuSubContent: {
        props: ['side', 'align'],
        template: '<div data-testid="sub-content" :data-side="side" :data-align="align"><slot /></div>'
    },
    DropdownMenuLabel: { template: '<div data-testid="label"><slot /></div>' },
    DropdownMenuGroup: { template: '<div data-testid="group"><slot /></div>' },
    DropdownMenuSeparator: { template: '<hr data-testid="separator" />' }
}));

import CompactDropdownMenuSub from '../CompactDropdownMenuSub.vue';

function mountSub(props = {}) {
    return mount(CompactDropdownMenuSub, {
        props: { label: 'Share', align: 'start', ...props },
        slots: { default: '<button data-testid="item">Copy URL</button>' }
    });
}

/**
 * @param {string} pointerType
 * @returns {MouseEvent}
 */
function clickEvent(pointerType) {
    const event = new MouseEvent('click', { bubbles: true });
    Object.defineProperty(event, 'pointerType', { value: pointerType });
    return event;
}

describe('CompactDropdownMenuSub.vue', () => {
    beforeEach(() => {
        compact.isCompact = ref(false);
    });

    it('is the PC submenu outside the phone layout', () => {
        const wrapper = mountSub();

        expect(wrapper.find('[data-testid="sub"]').exists()).toBe(true);
        expect(wrapper.find('[data-testid="sub-trigger"]').text()).toContain('Share');
        expect(wrapper.find('[data-testid="sub-content"]').attributes('data-side')).toBe('right');
        expect(wrapper.find('[data-testid="sub-content"]').attributes('data-align')).toBe('start');
        expect(wrapper.find('[data-testid="label"]').exists()).toBe(false);
    });

    it('runs the trigger action on a mouse click, as on PC', () => {
        const wrapper = mountSub();
        wrapper.find('[data-testid="sub-trigger"]').element.dispatchEvent(clickEvent('mouse'));

        expect(wrapper.emitted('trigger-click')).toHaveLength(1);
    });

    it('runs the trigger action on a keyboard click', () => {
        const wrapper = mountSub();
        wrapper.find('[data-testid="sub-trigger"]').element.dispatchEvent(clickEvent(''));

        expect(wrapper.emitted('trigger-click')).toHaveLength(1);
    });

    it('only opens the submenu on a tap', () => {
        const wrapper = mountSub();
        wrapper.find('[data-testid="sub-trigger"]').element.dispatchEvent(clickEvent('touch'));
        wrapper.find('[data-testid="sub-trigger"]').element.dispatchEvent(clickEvent('pen'));

        expect(wrapper.emitted('trigger-click')).toBeUndefined();
    });

    it('remembers a touch pointerdown when the click carries no pointer type', async () => {
        const wrapper = mountSub();
        const trigger = wrapper.find('[data-testid="sub-trigger"]');
        const down = new Event('pointerdown', { bubbles: true });
        Object.defineProperty(down, 'pointerType', { value: 'touch' });
        trigger.element.dispatchEvent(down);
        trigger.element.dispatchEvent(new MouseEvent('click', { bubbles: true }));

        expect(wrapper.emitted('trigger-click')).toBeUndefined();

        // The next mouse click works again.
        trigger.element.dispatchEvent(clickEvent('mouse'));
        expect(wrapper.emitted('trigger-click')).toHaveLength(1);
    });

    it('flattens into a labelled group on phones', () => {
        compact.isCompact = ref(true);
        const wrapper = mountSub();

        expect(wrapper.find('[data-testid="sub"]').exists()).toBe(false);
        expect(wrapper.find('[data-testid="label"]').text()).toContain('Share');
        expect(wrapper.find('[data-testid="group"] [data-testid="item"]').exists()).toBe(true);
        expect(wrapper.findAll('[data-testid="separator"]')).toHaveLength(1);
    });

    it('can close the flattened group with a separator', () => {
        compact.isCompact = ref(true);
        const wrapper = mountSub({ separator: false, separatorAfter: true });
        const html = wrapper.html();

        expect(wrapper.findAll('[data-testid="separator"]')).toHaveLength(1);
        expect(html.indexOf('data-testid="separator"')).toBeGreaterThan(html.indexOf('data-testid="group"'));
    });
});

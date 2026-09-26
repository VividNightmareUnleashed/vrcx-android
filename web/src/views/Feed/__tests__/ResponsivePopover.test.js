import { beforeEach, describe, expect, test, vi } from 'vitest';
import { defineComponent, h, KeepAlive, nextTick, ref } from 'vue';
import { mount } from '@vue/test-utils';

const mocks = vi.hoisted(() => ({ isCompact: require('vue').ref(true) }));

vi.mock('../../../composables/useCompactLayout', () => ({ useCompactLayout: () => ({ isCompact: mocks.isCompact }) }));
vi.mock('../../../components/ui/sheet', () => {
    const { h } = require('vue');
    const passthrough = (name) => ({
        name,
        render() {
            return h('div', { 'data-stub': name }, this.$slots.default?.());
        }
    });
    return {
        Sheet: {
            name: 'Sheet',
            props: ['open'],
            emits: ['update:open'],
            render() {
                return h('div', { 'data-stub': 'Sheet', 'data-open': String(this.open) }, this.$slots.default?.());
            }
        },
        SheetContent: passthrough('SheetContent'),
        SheetDescription: passthrough('SheetDescription'),
        SheetHeader: passthrough('SheetHeader'),
        SheetTitle: passthrough('SheetTitle'),
        SheetTrigger: passthrough('SheetTrigger')
    };
});
vi.mock('../../../components/ui/popover', () => {
    const { h } = require('vue');
    const passthrough = (name) => ({
        name,
        render() {
            return h('div', { 'data-stub': name }, this.$slots.default?.());
        }
    });
    return {
        Popover: {
            name: 'Popover',
            props: ['open'],
            emits: ['update:open'],
            render() {
                return h('div', { 'data-stub': 'Popover', 'data-open': String(this.open) }, this.$slots.default?.());
            }
        },
        PopoverContent: passthrough('PopoverContent'),
        PopoverTrigger: passthrough('PopoverTrigger')
    };
});

import ResponsivePopover from '../components/ResponsivePopover.vue';

/** A kept-alive view holding the popover, and a second view to switch to (a route change). */
function mountInKeptAliveView({ controlled }) {
    const open = ref(false);
    const View = defineComponent({
        name: 'View',
        setup: () => () =>
            h(
                ResponsivePopover,
                controlled
                    ? { title: 'Filter', open: open.value, 'onUpdate:open': (value) => (open.value = value) }
                    : { title: 'Filter' },
                { trigger: () => h('button', 'open'), default: () => h('p', 'content') }
            )
    });
    const Other = defineComponent({ name: 'Other', render: () => h('p', 'other') });
    const current = ref('view');
    const wrapper = mount(
        defineComponent({
            setup: () => () => h(KeepAlive, null, [current.value === 'view' ? h(View) : h(Other)])
        })
    );
    return { wrapper, open, current };
}

describe('ResponsivePopover', () => {
    beforeEach(() => {
        mocks.isCompact.value = true;
    });

    test('phones: the bottom sheet closes when its kept-alive view is left', async () => {
        const { wrapper, open, current } = mountInKeptAliveView({ controlled: true });
        open.value = true;
        await nextTick();
        expect(wrapper.find('[data-stub="Sheet"]').attributes('data-open')).toBe('true');

        current.value = 'other';
        await nextTick();
        expect(open.value).toBe(false);

        current.value = 'view';
        await nextTick();
        expect(wrapper.find('[data-stub="Sheet"]').attributes('data-open')).toBe('false');
    });

    test('phones: an uncontrolled sheet closes too', async () => {
        const { wrapper, current } = mountInKeptAliveView({ controlled: false });
        await wrapper.findComponent({ name: 'Sheet' }).vm.$emit('update:open', true);
        await nextTick();
        expect(wrapper.find('[data-stub="Sheet"]').attributes('data-open')).toBe('true');

        current.value = 'other';
        await nextTick();
        current.value = 'view';
        await nextTick();
        expect(wrapper.find('[data-stub="Sheet"]').attributes('data-open')).toBe('false');
    });

    test('PC: the popover keeps its upstream behaviour', async () => {
        mocks.isCompact.value = false;
        const { wrapper, open, current } = mountInKeptAliveView({ controlled: true });
        open.value = true;
        await nextTick();
        expect(wrapper.find('[data-stub="Popover"]').attributes('data-open')).toBe('true');

        current.value = 'other';
        await nextTick();
        expect(open.value).toBe(true);
    });
});

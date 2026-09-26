import { beforeEach, describe, expect, test, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';

const mocks = vi.hoisted(() => ({ isCompact: { value: false }, isAndroid: { value: false } }));

vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));
vi.mock('../../../plugins', () => ({ i18n: { global: { t: (key) => key, te: () => false } } }));
vi.mock('../../../stores', () => ({
    useUiStore: () => ({ shiftHeld: { value: false } }),
    useUserStore: () => ({ currentUser: { value: { id: 'usr_00000000-0000-4000-8000-00000000000a' } } })
}));
vi.mock('../../../coordinators/userCoordinator', () => ({ showUserDialog: vi.fn() }));
vi.mock('../../../shared/utils', () => ({ formatDateFilter: (value, format) => `${format}:${value}` }));
vi.mock('../../../shared/utils/platform', () => ({
    get isAndroid() {
        return mocks.isAndroid.value;
    }
}));
vi.mock('../../../composables/useCompactLayout', () => ({ useCompactLayout: () => ({ isCompact: mocks.isCompact }) }));
vi.mock('../../../components/ui/badge', () => ({ Badge: 'Badge' }));
vi.mock('../../../components/ui/button', () => ({ Button: 'Button' }));
vi.mock('../../../components/ui/tooltip', () => {
    const passthrough = (name) => ({
        name,
        render() {
            return h('div', { 'data-stub': name }, this.$slots.default?.());
        }
    });
    return {
        Tooltip: passthrough('Tooltip'),
        TooltipContent: passthrough('TooltipContent'),
        TooltipTrigger: passthrough('TooltipTrigger'),
        TooltipWrapper: {
            name: 'TooltipWrapper',
            props: ['content', 'side'],
            render() {
                return h(
                    'div',
                    { 'data-stub': 'TooltipWrapper', 'data-content': this.content },
                    this.$slots.default?.()
                );
            }
        }
    };
});

import { createColumns as createFriendLogColumns } from '../../FriendLog/columns.jsx';
import { createColumns as createModerationColumns } from '../columns.jsx';

const CREATED = '2026-09-26T10:00:00Z';

function mountDateCell(columns, id) {
    const column = columns.find((col) => (col.id ?? col.accessorKey) === id);
    const row = { original: { created: CREATED, created_at: CREATED }, getValue: () => CREATED };
    return mount(defineComponent({ render: () => h('div', [column.cell({ row })]) }));
}

describe.each([
    ['friend log', () => createFriendLogColumns({ onDelete: vi.fn(), onDeletePrompt: vi.fn() }), 'created_at'],
    ['moderation', () => createModerationColumns({ onDelete: vi.fn(), onDeletePrompt: vi.fn() }), 'created']
])('%s date cell', (_name, create, id) => {
    beforeEach(() => {
        mocks.isCompact.value = false;
        mocks.isAndroid.value = false;
    });

    test('phones show the exact date inline (no tooltip to reach on touch)', () => {
        mocks.isCompact.value = true;
        mocks.isAndroid.value = true;
        const wrapper = mountDateCell(create(), id);
        expect(wrapper.find('[data-testid="compact-long-date"]').text()).toBe(`long:${CREATED}`);
        expect(wrapper.find('[data-stub]').exists()).toBe(false);
    });

    test('Android tablets show it on long-press (TooltipWrapper)', () => {
        mocks.isAndroid.value = true;
        const wrapper = mountDateCell(create(), id);
        const tooltip = wrapper.find('[data-stub="TooltipWrapper"]');
        expect(tooltip.attributes('data-content')).toBe(`long:${CREATED}`);
        expect(tooltip.text()).toBe(`short:${CREATED}`);
    });

    test('PC keeps the upstream hover tooltip', () => {
        const wrapper = mountDateCell(create(), id);
        expect(wrapper.find('[data-stub="TooltipWrapper"]').exists()).toBe(false);
        expect(wrapper.find('[data-stub="TooltipTrigger"]').text()).toBe(`short:${CREATED}`);
        expect(wrapper.find('[data-stub="TooltipContent"]').text()).toBe(`long:${CREATED}`);
    });
});

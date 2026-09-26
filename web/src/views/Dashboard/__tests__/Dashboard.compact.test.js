import { beforeEach, describe, expect, test, vi } from 'vitest';
import { mount } from '@vue/test-utils';

const mocks = vi.hoisted(() => ({
    // A real ref: the template unwraps it (a plain { value } object would always be truthy there).
    isCompact: require('vue').ref(true),
    dashboard: null,
    updateDashboard: vi.fn(),
    replace: vi.fn()
}));

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: mocks.replace }) }));
vi.mock('vue-sonner', () => ({ toast: vi.fn() }));
vi.mock('@/composables/useCompactLayout', () => ({ useCompactLayout: () => ({ isCompact: mocks.isCompact }) }));
vi.mock('@/stores', () => ({
    useDashboardStore: () => ({
        getDashboard: () => mocks.dashboard,
        updateDashboard: mocks.updateDashboard,
        clearEditingDashboardId: vi.fn(),
        editingDashboardId: null,
        dashboards: [mocks.dashboard]
    }),
    useModalStore: () => ({ confirm: vi.fn() })
}));

const { stub } = vi.hoisted(() => {
    const { defineComponent, h } = require('vue');
    return {
        stub: (name, props = []) =>
            defineComponent({
                name,
                props,
                emits: ['select', 'update-panel'],
                setup(componentProps, { slots }) {
                    return () =>
                        h(
                            'div',
                            { 'data-stub': name, 'data-panel': JSON.stringify(componentProps.panelData ?? null) },
                            slots.default?.()
                        );
                }
            })
    };
});

vi.mock('@/components/ui/button', () => ({ Button: stub('Button') }));
vi.mock('@/components/ui/resizable', () => ({
    ResizablePanelGroup: stub('ResizablePanelGroup'),
    ResizablePanel: stub('ResizablePanel'),
    ResizableHandle: stub('ResizableHandle')
}));
vi.mock('../components/DashboardEditToolbar.vue', () => ({ default: stub('DashboardEditToolbar') }));
vi.mock('../components/DashboardPanel.vue', () => ({ default: stub('DashboardPanel', ['panelData']) }));
vi.mock('../components/DashboardRow.vue', () => ({
    default: stub('DashboardRow', ['row', 'rowIndex', 'dashboardId', 'isEditing'])
}));

import Dashboard from '../Dashboard.vue';

const ROWS = [
    {
        direction: 'horizontal',
        panels: [
            { key: 'widget:feed', config: {} },
            { key: 'widget:game-log', config: {} }
        ]
    },
    { direction: 'horizontal', panels: ['friends-locations'] },
    { direction: 'vertical', panels: [null, { key: 'widget:instance', config: {} }] }
];

describe('Dashboard in the phone layout', () => {
    beforeEach(() => {
        mocks.isCompact.value = true;
        mocks.dashboard = { id: 'dashboard-1', name: 'Home', rows: JSON.parse(JSON.stringify(ROWS)) };
        mocks.updateDashboard.mockClear();
    });

    test('stacks every panel of every row in one column, without resizable splitters', () => {
        const wrapper = mount(Dashboard, { props: { id: 'dashboard-1' } });
        const panels = wrapper.findAll('[data-testid="dashboard-compact-panel"]');

        expect(panels).toHaveLength(5);
        expect(
            panels.map((panel) => JSON.parse(panel.find('[data-stub="DashboardPanel"]').attributes('data-panel')))
        ).toEqual([
            { key: 'widget:feed', config: {} },
            { key: 'widget:game-log', config: {} },
            'friends-locations',
            null,
            { key: 'widget:instance', config: {} }
        ]);
        expect(wrapper.find('[data-stub="ResizablePanelGroup"]').exists()).toBe(false);
        expect(wrapper.find('[data-stub="ResizableHandle"]').exists()).toBe(false);
        expect(wrapper.find('[data-stub="DashboardRow"]').exists()).toBe(false);
    });

    test('widgets take about half a screen, pages most of one, empty panels little', () => {
        const wrapper = mount(Dashboard, { props: { id: 'dashboard-1' } });
        const classes = wrapper.findAll('[data-testid="dashboard-compact-panel"]').map((panel) => panel.classes());

        expect(classes[0]).toContain('h-[45dvh]');
        expect(classes[2]).toContain('h-[75dvh]');
        expect(classes[3]).toContain('h-24');
        for (const panelClasses of classes) {
            expect(panelClasses).toContain('shrink-0');
        }
    });

    test('a panel picked on a phone updates the right row and slot', async () => {
        const wrapper = mount(Dashboard, { props: { id: 'dashboard-1' } });
        const emptyPanel = wrapper.findAllComponents({ name: 'DashboardPanel' })[3];

        emptyPanel.vm.$emit('select', { key: 'widget:feed', config: {} });
        await Promise.resolve();

        expect(mocks.updateDashboard).toHaveBeenCalledTimes(1);
        const [id, { rows }] = mocks.updateDashboard.mock.calls[0];
        expect(id).toBe('dashboard-1');
        expect(rows[2].panels).toEqual([
            { key: 'widget:feed', config: {} },
            { key: 'widget:instance', config: {} }
        ]);
        // The PC row layout is kept.
        expect(rows.map((row) => row.direction)).toEqual(['horizontal', 'horizontal', 'vertical']);
    });

    test('PC keeps the resizable rows', () => {
        mocks.isCompact.value = false;
        const wrapper = mount(Dashboard, { props: { id: 'dashboard-1' } });

        expect(wrapper.find('[data-testid="dashboard-compact-panel"]').exists()).toBe(false);
        expect(wrapper.find('[data-stub="ResizablePanelGroup"]').exists()).toBe(true);
        expect(wrapper.findAll('[data-stub="DashboardRow"]')).toHaveLength(3);
        expect(wrapper.findAll('[data-stub="ResizableHandle"]')).toHaveLength(2);
    });
});

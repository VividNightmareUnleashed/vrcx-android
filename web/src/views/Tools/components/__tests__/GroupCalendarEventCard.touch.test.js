// Touch: the calendar event details are a tap toggle instead of a hover popover (docs/DESIGN.md §3.3).
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';

const mocks = vi.hoisted(() => ({ triggerClicks: 0, coarse: null }));

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k) => k }) }));
vi.mock('../../../../stores', () => ({
    useGalleryStore: () => ({ showFullscreenImageDialog: vi.fn() }),
    useGroupStore: () => ({ cachedGroups: new Map(), showEditGroupEventDialog: vi.fn() })
}));
vi.mock('../../../../services/appConfig', () => ({ AppDebug: { endpointDomain: 'https://api.example.com' } }));
vi.mock('../../../../shared/utils', () => ({
    formatDateFilter: () => '12:00',
    hasGroupPermission: () => false
}));
vi.mock('../../../../api', () => ({ groupRequest: {}, queryRequest: {} }));
vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
vi.mock('@/composables/useCompactLayout', async () => {
    const { ref: vueRef } = await import('vue');
    mocks.coarse = vueRef(true);
    return {
        useCompactLayout: () => ({
            isCompact: vueRef(true),
            isCompactLandscape: vueRef(false),
            isCoarsePointer: mocks.coarse
        })
    };
});
vi.mock('@/components/ui/tooltip/TooltipWrapper.vue', () => ({ default: { template: '<span><slot /></span>' } }));
// The popover is controlled: the stub shows the `open` prop and counts the trigger's own toggle clicks.
vi.mock('@/components/ui/popover', () => ({
    Popover: {
        props: ['open'],
        emits: ['update:open'],
        template: '<div data-testid="popover" :data-open="String(open)"><slot /></div>'
    },
    PopoverTrigger: {
        methods: {
            onClick() {
                mocks.triggerClicks += 1;
            }
        },
        template: '<div data-testid="trigger" @click="onClick"><slot /></div>'
    },
    PopoverContent: { template: '<div data-testid="content"><slot /></div>' }
}));
vi.mock('@/components/ui/card', () => ({ Card: { template: '<div class="card"><slot /></div>' } }));
vi.mock('@/components/ui/button', () => ({
    Button: {
        emits: ['click'],
        template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>'
    }
}));

import GroupCalendarEventCard from '../GroupCalendarEventCard.vue';

function mountCard() {
    return mount(GroupCalendarEventCard, {
        props: {
            event: {
                id: 'gcal_00000000-0000-4000-8000-000000000001',
                ownerId: 'grp_00000000-0000-4000-8000-000000000001',
                title: 'Event One',
                startsAt: '2026-01-01T10:00:00Z',
                endsAt: '2026-01-01T12:00:00Z',
                accessType: 'public',
                category: 'social',
                interestedUserCount: 2,
                closeInstanceAfterEndMinutes: 30,
                createdAt: '2026-01-01',
                description: 'desc',
                imageUrl: ''
            },
            mode: 'grid'
        }
    });
}

const isOpen = (wrapper) => wrapper.get('[data-testid="popover"]').attributes('data-open') === 'true';

describe('GroupCalendarEventCard on touch screens', () => {
    beforeEach(() => {
        mocks.triggerClicks = 0;
        if (mocks.coarse) {
            mocks.coarse.value = true;
        }
    });

    it('toggles the details from the info button', async () => {
        const wrapper = mountCard();
        const info = wrapper.get('button[aria-label="android.group_calendar.event_details"]');
        await info.trigger('click');
        expect(isOpen(wrapper)).toBe(true);
        await info.trigger('click');
        expect(isOpen(wrapper)).toBe(false);
        expect(mocks.triggerClicks).toBe(0);
    });

    it('toggles the details from the time line without reaching the popover trigger', async () => {
        const wrapper = mountCard();
        await wrapper.get('.event-info').trigger('click');
        expect(isOpen(wrapper)).toBe(true);
        expect(mocks.triggerClicks).toBe(0);
        await wrapper.get('.event-info').trigger('click');
        expect(isOpen(wrapper)).toBe(false);
    });

    it('ignores hover and closes on an outside tap', async () => {
        const wrapper = mountCard();
        await wrapper.get('.card').trigger('mouseenter');
        expect(isOpen(wrapper)).toBe(false);

        await wrapper.get('.event-info').trigger('click');
        expect(isOpen(wrapper)).toBe(true);
        wrapper.getComponent('[data-testid="popover"]').vm.$emit('update:open', false);
        await wrapper.vm.$nextTick();
        expect(isOpen(wrapper)).toBe(false);
    });

    it('keeps the PC hover popover for a mouse', async () => {
        mocks.coarse.value = false;
        const wrapper = mountCard();
        expect(wrapper.find('button[aria-label="android.group_calendar.event_details"]').exists()).toBe(false);
        await wrapper.get('.event-info').trigger('click');
        expect(isOpen(wrapper)).toBe(false);
        expect(mocks.triggerClicks).toBe(1);
        await wrapper.get('.card').trigger('mouseenter');
        expect(isOpen(wrapper)).toBe(true);
    });
});

import { beforeEach, describe, expect, test, vi } from 'vitest';
import { mount } from '@vue/test-utils';

const mocks = vi.hoisted(() => {
    const { ref } = require('vue');
    return { isCompact: ref(false), isCoarsePointer: ref(false) };
});

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));
vi.mock('../../../stores', () => ({ useGameStore: () => ({ isGameRunning: false }) }));
vi.mock('../../../shared/utils', () => ({
    formatDateFilter: (value) => String(value),
    timeToText: (ms) => `${ms}ms`
}));
vi.mock('../../../composables/useCompactLayout', () => ({
    useCompactLayout: () => ({ isCompact: mocks.isCompact, isCoarsePointer: mocks.isCoarsePointer })
}));
vi.mock('../../../components/ui/badge', () => ({ Badge: { template: '<span><slot /></span>' } }));
vi.mock('../../../components/Location.vue', () => ({ default: { template: '<span data-stub="Location" />' } }));
vi.mock('../components/GameLogSessionsEvent.vue', () => ({ default: { template: '<div data-stub="event" />' } }));
vi.mock('../components/GameLogRowMenu.vue', () => ({
    default: { template: '<button type="button" data-stub="row-menu" @click.stop />' }
}));

import GameLogSessionsSegment from '../components/GameLogSessionsSegment.vue';

const SEGMENT = {
    location: 'wrld_00000000-0000-4000-8000-000000000001:12345~region(eu)',
    worldName: 'Lantern Harbor',
    created_at: '2026-09-26T10:00:00Z',
    duration: 60000,
    events: [{ type: 'OnPlayerJoined', created_at: '2026-09-26T10:01:00Z' }]
};

const mountSegment = () => mount(GameLogSessionsSegment, { props: { segment: SEGMENT } });

describe('game log session header on touch tablets and PC', () => {
    beforeEach(() => {
        mocks.isCompact.value = false;
        mocks.isCoarsePointer.value = false;
    });

    test('PC: the upstream <button> header without the world menu; its keys are left alone', async () => {
        const wrapper = mountSegment();
        const header = wrapper.find('button.sticky');
        expect(header.exists()).toBe(true);
        expect(header.attributes('type')).toBe('button');
        expect(wrapper.find('[data-stub="row-menu"]').exists()).toBe(false);

        const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
        header.element.dispatchEvent(enter);
        const space = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true });
        header.element.dispatchEvent(space);
        // The native button activation must not be cancelled (it is what toggles the session on PC).
        expect(enter.defaultPrevented).toBe(false);
        expect(space.defaultPrevented).toBe(false);
    });

    test('touch tablets: a div header with button semantics and the explicit world menu', async () => {
        mocks.isCoarsePointer.value = true;
        const wrapper = mountSegment();
        const header = wrapper.find('div.sticky[role="button"]');
        expect(header.exists()).toBe(true);
        expect(header.attributes('tabindex')).toBe('0');
        expect(header.attributes('aria-expanded')).toBe('true');
        expect(wrapper.findAll('[data-stub="event"]')).toHaveLength(1);

        // The menu button does not toggle the session.
        await wrapper.find('[data-stub="row-menu"]').trigger('click');
        expect(wrapper.findAll('[data-stub="event"]')).toHaveLength(1);

        await header.trigger('keydown', { key: 'Enter' });
        expect(header.attributes('aria-expanded')).toBe('false');
        expect(wrapper.findAll('[data-stub="event"]')).toHaveLength(0);

        await header.trigger('keydown', { key: ' ' });
        expect(header.attributes('aria-expanded')).toBe('true');

        await header.trigger('click');
        expect(header.attributes('aria-expanded')).toBe('false');
    });
});

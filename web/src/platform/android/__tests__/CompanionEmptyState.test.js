import { beforeEach, describe, expect, test, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

const push = vi.fn();

vi.mock('vue-router', async (importOriginal) => ({
    ...(await importOriginal()),
    useRouter: () => ({ push })
}));
vi.mock('vue-i18n', async (importOriginal) => ({
    ...(await importOriginal()),
    useI18n: () => ({ t: (key, params) => (params ? `${key}|${JSON.stringify(params)}` : key) })
}));

import CompanionEmptyState from '../components/CompanionEmptyState.vue';
import { useCompanionStore } from '../companionStore.js';

const pc = { id: 'pc-1', name: 'DESKTOP', hosts: ['10.0.0.2'], port: 49460 };

describe('CompanionEmptyState', () => {
    let pinia;

    beforeEach(() => {
        pinia = createPinia();
        setActivePinia(pinia);
        push.mockClear();
    });

    function mountState(props = {}) {
        return mount(CompanionEmptyState, { props, global: { plugins: [pinia] } });
    }

    test('asks to set up the companion when no PC is paired', async () => {
        const wrapper = mountState();
        expect(wrapper.find('[data-testid="companion-empty-state"]').exists()).toBe(true);
        expect(wrapper.text()).toContain('android.empty.unpaired_title');
        expect(wrapper.text()).toContain('android.empty.game_log_description');

        await wrapper.find('[data-testid="companion-empty-action"]').trigger('click');
        expect(push).toHaveBeenCalledWith({ name: 'settings', query: { tab: 'companion' } });
    });

    test('uses the player list description', () => {
        const wrapper = mountState({ kind: 'playerList' });
        expect(wrapper.text()).toContain('android.empty.player_list_description');
    });

    test('says the paired PC is not connected', () => {
        useCompanionStore().applyState({ status: 'idle', activeId: 'pc-1', paired: [pc] });
        const wrapper = mountState();
        expect(wrapper.text()).toContain('android.empty.disconnected_title');
        expect(wrapper.text()).toContain('"name":"DESKTOP"');
        expect(wrapper.text()).toContain('android.empty.disconnected_action');
    });

    test('shows the normal empty state while connected', async () => {
        useCompanionStore().applyState({ status: 'connected', activeId: 'pc-1', paired: [pc] });
        const wrapper = mountState();
        await flushPromises();
        expect(wrapper.find('[data-testid="companion-empty-state"]').exists()).toBe(false);
        expect(wrapper.text()).toContain('common.no_data');
    });

    test('shows the normal empty state when a filter hides everything', () => {
        const wrapper = mountState({ filtered: true });
        expect(wrapper.find('[data-testid="companion-empty-state"]').exists()).toBe(false);
    });

    test('renders nothing while loading', () => {
        const wrapper = mountState({ loading: true });
        expect(wrapper.text()).toBe('');
    });

    test('widget variant falls back to the widget text', () => {
        useCompanionStore().applyState({ status: 'connected', activeId: 'pc-1', paired: [pc] });
        const wrapper = mountState({ variant: 'widget', fallbackText: 'Not in game' });
        expect(wrapper.find('[data-testid="companion-empty-fallback"]').text()).toBe('Not in game');
    });

    test('widget variant shows the compact companion message', () => {
        const wrapper = mountState({ variant: 'widget', fallbackText: 'No data' });
        expect(wrapper.find('[data-testid="companion-empty-state"]').text()).toContain('android.empty.unpaired_action');
    });
});
